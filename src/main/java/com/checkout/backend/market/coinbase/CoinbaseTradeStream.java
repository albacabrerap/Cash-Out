package com.checkout.backend.market.coinbase;

import com.checkout.backend.market.live.LivePriceService;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * WebSocket publico de Coinbase: cada operacion de los pares del catalogo llega
 * aqui y pasa a LivePriceService.
 *
 * Se suscribe tambien a los latidos: Coinbase cierra los canales que pasan un
 * rato sin mensajes, y un par poco operado puede pasar un minuto sin ninguno.
 * Si la conexion se cae, reintenta con espera creciente hasta un minuto.
 */
@Component
public class CoinbaseTradeStream {

    private static final Logger log = LoggerFactory.getLogger(CoinbaseTradeStream.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration FIRST_RETRY = Duration.ofSeconds(2);
    private static final Duration MAX_RETRY = Duration.ofMinutes(1);

    record StreamTrade(String productId, BigDecimal price, Instant time) {
    }

    private final CoinbaseProperties properties;
    private final LivePriceService livePrices;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ScheduledExecutorService retries = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "coinbase-stream");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private volatile Duration nextRetry = FIRST_RETRY;
    private volatile WebSocket socket;
    private volatile boolean stopped;

    public CoinbaseTradeStream(CoinbaseProperties properties, LivePriceService livePrices) {
        this.properties = properties;
        this.livePrices = livePrices;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (properties.enabled()) {
            connect();
        }
    }

    @PreDestroy
    public void stop() {
        stopped = true;
        retries.shutdownNow();
        WebSocket current = socket;
        if (current != null) {
            current.sendClose(WebSocket.NORMAL_CLOSURE, "cierre");
        }
    }

    private void connect() {
        if (stopped) {
            return;
        }
        http.newWebSocketBuilder()
                .connectTimeout(properties.timeout())
                .buildAsync(properties.streamUrl(), new Listener())
                .thenCompose(ws -> {
                    socket = ws;
                    // Un envio tiene que terminar antes del siguiente: por eso van encadenados.
                    return ws.sendText(subscription("market_trades"), true)
                            .thenCompose(sent -> sent.sendText(subscription("heartbeats"), true));
                })
                .whenComplete((ws, error) -> {
                    if (error != null) {
                        log.warn("No se pudo conectar al stream de Coinbase: {}", error.getMessage());
                        retry();
                    } else {
                        log.info("Stream de Coinbase conectado: {}", properties.products());
                        nextRetry = FIRST_RETRY;
                    }
                });
    }

    private void retry() {
        if (stopped || !reconnecting.compareAndSet(false, true)) {
            return;
        }
        Duration wait = nextRetry;
        nextRetry = wait.multipliedBy(2).compareTo(MAX_RETRY) > 0 ? MAX_RETRY : wait.multipliedBy(2);
        retries.schedule(() -> {
            reconnecting.set(false);
            connect();
        }, wait.toMillis(), TimeUnit.MILLISECONDS);
    }

    private String subscription(String channel) {
        return JSON.writeValueAsString(Map.of(
                "type", "subscribe",
                "channel", channel,
                "product_ids", properties.products()));
    }

    /** Las operaciones de un mensaje del canal market_trades; cualquier otro mensaje da una lista vacia. */
    static List<StreamTrade> parseTrades(String message) {
        List<StreamTrade> trades = new ArrayList<>();
        try {
            JsonNode root = JSON.readTree(message);
            if (!"market_trades".equals(root.path("channel").asString(null))) {
                return trades;
            }
            // "snapshot" trae las ultimas operaciones al suscribirse; "update", las nuevas.
            for (JsonNode event : root.path("events")) {
                for (JsonNode trade : event.path("trades")) {
                    trades.add(new StreamTrade(
                            trade.path("product_id").asString(),
                            new BigDecimal(trade.path("price").asString()),
                            Instant.parse(trade.path("time").asString())));
                }
            }
        } catch (RuntimeException e) {
            // JSON roto, un precio que no es numero o una fecha mal formada.
            log.debug("Mensaje de Coinbase ignorado: {}", e.getMessage());
            return List.of();
        }
        return trades;
    }

    private final class Listener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                livePrices.markAlive();
                for (StreamTrade trade : parseTrades(buffer.toString())) {
                    livePrices.record(trade.productId(), trade.price(), trade.time());
                }
                buffer.setLength(0);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            if (!stopped) {
                log.warn("Coinbase cerro el stream ({} {}); se reintenta", statusCode, reason);
                retry();
            }
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.warn("Error en el stream de Coinbase: {}", error.getMessage());
            retry();
        }
    }

}
