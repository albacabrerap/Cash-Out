package com.checkout.backend.market.live;

import com.checkout.backend.market.MarketDataUnavailableException;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Ultimo precio de cada par de cripto y su recorrido segundo a segundo.
 *
 * Lo alimenta CoinbaseTradeStream con cada operacion. Una vez por segundo se toma
 * una muestra por par —sin operaciones en ese segundo, la grafica avanza con el
 * ultimo precio, como en un broker real— y se envia a las graficas abiertas por SSE.
 */
@Service
public class LivePriceService {

    /** Lo que recibe una grafica recien abierta: los ultimos 15 minutos. */
    static final int HISTORY_SECONDS = 900;

    /** Coinbase manda un latido por segundo; sin mensajes en este tiempo, el stream se da por caido. */
    private static final Duration STREAM_SILENCE = Duration.ofSeconds(30);

    private static final long EMITTER_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();
    private static final int MAX_SUBSCRIBERS = 500;

    public record LivePoint(long time, BigDecimal price) {
    }

    private record Trade(BigDecimal price, Instant at) {
    }

    private final Clock clock;
    private final Map<String, Trade> latest = new ConcurrentHashMap<>();
    private final Map<String, Deque<LivePoint>> series = new ConcurrentHashMap<>();
    private final Map<String, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final AtomicInteger subscriberCount = new AtomicInteger();
    private volatile Instant lastMessageAt = Instant.EPOCH;

    public LivePriceService(Clock clock) {
        this.clock = clock;
    }

    /** Cualquier mensaje del stream, aunque sea un latido, prueba que sigue conectado. */
    public void markAlive() {
        lastMessageAt = clock.instant();
    }

    /** Una operacion. Las que llegan fuera de orden no pisan a una mas reciente. */
    public void record(String productId, BigDecimal price, Instant at) {
        latest.merge(productId, new Trade(price, at),
                (current, incoming) -> incoming.at().isBefore(current.at()) ? current : incoming);
    }

    /**
     * El precio en vivo, solo si el stream esta conectado. Con el stream caido el
     * ultimo precio puede tener minutos, y para operar se prefiere preguntar a la API.
     */
    public Optional<BigDecimal> currentPrice(String productId) {
        if (!streaming()) {
            return Optional.empty();
        }
        return Optional.ofNullable(latest.get(productId)).map(Trade::price);
    }

    @Scheduled(fixedRate = 1000)
    public void sample() {
        if (!streaming()) {
            return;
        }
        long now = clock.instant().getEpochSecond();
        latest.forEach((productId, trade) -> {
            LivePoint point = new LivePoint(now, trade.price());
            Deque<LivePoint> points = series.computeIfAbsent(productId, id -> new ArrayDeque<>());
            synchronized (points) {
                points.addLast(point);
                while (points.size() > HISTORY_SECONDS) {
                    points.removeFirst();
                }
            }
            send(productId, "tick", point);
        });
    }

    /** Abre una grafica en vivo: primero el evento "history" y despues un "tick" por segundo. */
    public SseEmitter subscribe(String productId) {
        if (subscriberCount.incrementAndGet() > MAX_SUBSCRIBERS) {
            subscriberCount.decrementAndGet();
            throw new MarketDataUnavailableException("Hay demasiadas graficas en vivo abiertas. Intenta en un momento.");
        }
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        Set<SseEmitter> listeners = subscribers.computeIfAbsent(productId, id -> ConcurrentHashMap.newKeySet());
        listeners.add(emitter);
        Runnable release = () -> {
            if (listeners.remove(emitter)) {
                subscriberCount.decrementAndGet();
            }
        };
        emitter.onCompletion(release);
        emitter.onTimeout(release);
        emitter.onError(error -> release.run());

        try {
            emitter.send(SseEmitter.event().name("history").data(history(productId), MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            release.run();
            emitter.completeWithError(e);
        }
        return emitter;
    }

    List<LivePoint> history(String productId) {
        Deque<LivePoint> points = series.get(productId);
        if (points == null) {
            return List.of();
        }
        synchronized (points) {
            return List.copyOf(points);
        }
    }

    private boolean streaming() {
        return lastMessageAt.isAfter(clock.instant().minus(STREAM_SILENCE));
    }

    private void send(String productId, String event, Object data) {
        Set<SseEmitter> listeners = subscribers.get(productId);
        if (listeners == null) {
            return;
        }
        for (SseEmitter emitter : listeners) {
            try {
                emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                // La pestana se cerro: se suelta ya, no en el proximo segundo.
                if (listeners.remove(emitter)) {
                    subscriberCount.decrementAndGet();
                }
            }
        }
    }

}
