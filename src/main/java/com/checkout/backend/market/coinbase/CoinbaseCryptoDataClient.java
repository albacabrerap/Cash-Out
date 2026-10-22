package com.checkout.backend.market.coinbase;

import com.checkout.backend.market.CryptoDataClient;
import com.checkout.backend.market.MarketDataClient.ProviderBar;
import com.checkout.backend.market.MarketDataClient.ProviderSnapshot;
import com.checkout.backend.market.MarketDataUnavailableException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/**
 * CryptoDataClient sobre la API REST publica de Coinbase (Exchange). No usa claves.
 */
public class CoinbaseCryptoDataClient implements CryptoDataClient {

    private static final Logger log = LoggerFactory.getLogger(CoinbaseCryptoDataClient.class);

    /** Coinbase responde 400 a un pedido de mas de 300 velas. */
    static final int MAX_CANDLES = 300;

    private static final String UNAVAILABLE =
            "No pudimos obtener precios de cripto en este momento. Intenta de nuevo en unos minutos.";

    private final RestClient rest;

    public CoinbaseCryptoDataClient(CoinbaseProperties properties, RestClient.Builder builder) {
        // Coinbase rechaza los pedidos que no traen User-Agent.
        this.rest = builder.baseUrl(properties.restUrl().toString())
                .defaultHeader(HttpHeaders.USER_AGENT, "checkout-backend")
                .build();
    }

    @Override
    public List<CryptoProduct> products(Collection<String> productIds) {
        List<CryptoProduct> products = new ArrayList<>();
        for (String id : productIds) {
            Optional<JsonNode> product = find("el par " + id, () -> rest.get()
                    .uri("/products/{id}", id)
                    .retrieve()
                    .body(JsonNode.class));
            if (product.isEmpty()) {
                continue;
            }
            JsonNode node = product.get();
            String base = node.path("base_currency").asString(id);
            String name = find("la moneda " + base, () -> rest.get()
                    .uri("/currencies/{id}", base)
                    .retrieve()
                    .body(JsonNode.class))
                    .map(currency -> currency.path("name").asString(base))
                    .orElse(base);
            boolean tradable = "online".equals(node.path("status").asString(null))
                    && !node.path("trading_disabled").asBoolean(false);
            products.add(new CryptoProduct(node.path("id").asString(id), name, tradable));
        }
        return products;
    }

    @Override
    public ProviderSnapshot snapshot(String productId) {
        JsonNode stats = call("el precio de " + productId, () -> rest.get()
                .uri("/products/{id}/stats", productId)
                .retrieve()
                .body(JsonNode.class));
        return new ProviderSnapshot(productId, decimal(stats.path("last")), null, decimal(stats.path("open")));
    }

    @Override
    public List<ProviderBar> dailyBars(String productId, LocalDate from, LocalDate to) {
        return candles(productId, Duration.ofDays(1),
                from.atStartOfDay(ZoneOffset.UTC).toInstant(),
                to.atStartOfDay(ZoneOffset.UTC).toInstant())
                .stream()
                .map(candle -> new ProviderBar(LocalDate.ofInstant(candle.time(), ZoneOffset.UTC), candle.close()))
                .toList();
    }

    @Override
    public List<Candle> minuteCandles(String productId, Instant from, Instant to) {
        return candles(productId, Duration.ofMinutes(1), from, to);
    }

    /** Pide las velas en tramos de MAX_CANDLES y las devuelve en orden, sin repetidas. */
    private List<Candle> candles(String productId, Duration step, Instant from, Instant to) {
        TreeMap<Instant, Candle> candles = new TreeMap<>();
        Duration span = step.multipliedBy(MAX_CANDLES - 1);
        Instant start = from;
        while (!start.isAfter(to)) {
            Instant end = start.plus(span).isAfter(to) ? to : start.plus(span);
            String startParam = start.toString();
            String endParam = end.toString();
            JsonNode body = call("las velas de " + productId, () -> rest.get()
                    .uri(uri -> uri.path("/products/{id}/candles")
                            .queryParam("granularity", step.toSeconds())
                            .queryParam("start", startParam)
                            .queryParam("end", endParam)
                            .build(productId))
                    .retrieve()
                    .body(JsonNode.class));

            // Cada vela es [tiempo, minimo, maximo, apertura, cierre, volumen], de la mas nueva a la mas vieja.
            for (JsonNode row : body) {
                Instant time = Instant.ofEpochSecond(row.get(0).asLong());
                candles.put(time, new Candle(time, decimal(row.get(3)), decimal(row.get(2)),
                        decimal(row.get(1)), decimal(row.get(4))));
            }
            start = end.plus(step);
        }
        return List.copyOf(candles.values());
    }

    /** Coinbase manda los precios como texto en unos endpoints y como numero en las velas. */
    private static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.isNumber() ? node.decimalValue() : new BigDecimal(node.asString());
    }

    private JsonNode call(String what, Supplier<JsonNode> request) {
        return find(what, request).orElseThrow(() -> new MarketDataUnavailableException(UNAVAILABLE));
    }

    /** Como call, pero un 404 —un par o una moneda que Coinbase no conoce— devuelve vacio. */
    private Optional<JsonNode> find(String what, Supplier<JsonNode> request) {
        try {
            return Optional.ofNullable(request.get());
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            log.warn("Coinbase respondio {} al pedir {}", e.getStatusCode(), what);
            throw new MarketDataUnavailableException(UNAVAILABLE, e);
        } catch (RestClientException e) {
            log.warn("No se pudo contactar a Coinbase para pedir {}: {}", what, e.getMessage());
            throw new MarketDataUnavailableException(UNAVAILABLE, e);
        }
    }

}
