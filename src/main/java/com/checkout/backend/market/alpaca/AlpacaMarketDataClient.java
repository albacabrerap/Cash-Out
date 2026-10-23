package com.checkout.backend.market.alpaca;

import com.checkout.backend.market.MarketDataClient;
import com.checkout.backend.market.MarketDataUnavailableException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/**
 * MarketDataClient sobre la API REST de Alpaca.
 *
 * Usa dos APIs distintas: la de trading publica el catalogo de activos y la de
 * datos publica precios e historico. Las dos autentican con las mismas cabeceras.
 */
public class AlpacaMarketDataClient implements MarketDataClient {

    private static final Logger log = LoggerFactory.getLogger(AlpacaMarketDataClient.class);

    /** Simbolos por pedido de snapshots: la lista viaja en la URL. */
    static final int SNAPSHOT_BATCH = 200;

    /** Las barras diarias de Alpaca marcan la medianoche de Nueva York; de ahi sale el dia de cotizacion. */
    private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");

    private static final String UNAVAILABLE =
            "No pudimos obtener datos del mercado en este momento. Intenta de nuevo en unos minutos.";

    private final AlpacaProperties properties;
    private final RestClient data;
    private final RestClient trading;

    public AlpacaMarketDataClient(AlpacaProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        if (properties.configured()) {
            builder.defaultHeader("APCA-API-KEY-ID", properties.apiKey())
                    .defaultHeader("APCA-API-SECRET-KEY", properties.apiSecret());
        }
        this.data = builder.clone().baseUrl(properties.dataUrl().toString()).build();
        this.trading = builder.clone().baseUrl(properties.tradingUrl().toString()).build();
    }

    @Override
    public List<ProviderAsset> listUsEquities() {
        JsonNode body = call("catalogo", () -> trading.get()
                .uri(uri -> uri.path("/v2/assets")
                        .queryParam("status", "active")
                        .queryParam("asset_class", "us_equity")
                        .build())
                .retrieve()
                .body(JsonNode.class));

        List<ProviderAsset> assets = new ArrayList<>();
        for (JsonNode node : body) {
            assets.add(new ProviderAsset(
                    node.path("symbol").asString(),
                    node.path("name").asString(),
                    node.path("exchange").asString(null),
                    node.path("tradable").asBoolean(false)));
        }
        return assets;
    }

    @Override
    public Map<String, ProviderSnapshot> snapshots(Collection<String> symbols) {
        List<String> all = List.copyOf(symbols);
        Map<String, ProviderSnapshot> result = new HashMap<>();
        for (int i = 0; i < all.size(); i += SNAPSHOT_BATCH) {
            String batch = String.join(",", all.subList(i, Math.min(i + SNAPSHOT_BATCH, all.size())));
            JsonNode body = call("precios", () -> data.get()
                    .uri(uri -> uri.path("/v2/stocks/snapshots")
                            .queryParam("symbols", batch)
                            .queryParam("feed", properties.feed())
                            .build())
                    .retrieve()
                    .body(JsonNode.class));

            for (var entry : body.properties()) {
                JsonNode trade = entry.getValue().path("latestTrade");
                if (!trade.has("p")) {
                    continue;
                }
                JsonNode previous = entry.getValue().path("prevDailyBar");
                result.put(entry.getKey(), new ProviderSnapshot(
                        entry.getKey(),
                        trade.path("p").decimalValue(),
                        trade.has("t") ? Instant.parse(trade.path("t").asString()) : null,
                        previous.has("c") ? previous.path("c").decimalValue() : null));
            }
        }
        return result;
    }

    @Override
    public List<ProviderBar> dailyBars(String symbol, LocalDate from, LocalDate to) {
        List<ProviderBar> bars = new ArrayList<>();
        String pageToken = null;
        do {
            String token = pageToken;
            JsonNode body = call("historico", () -> data.get()
                    .uri(uri -> {
                        uri.path("/v2/stocks/bars")
                                .queryParam("symbols", symbol)
                                .queryParam("timeframe", "1Day")
                                .queryParam("start", from.toString())
                                .queryParam("end", to.toString())
                                // Ajustado por splits y dividendos: sin esto un split
                                // dibuja una caida del 75% que nadie sufrio.
                                .queryParam("adjustment", "all")
                                .queryParam("feed", properties.feed())
                                .queryParam("limit", 10000);
                        if (token != null) {
                            uri.queryParam("page_token", token);
                        }
                        return uri.build();
                    })
                    .retrieve()
                    .body(JsonNode.class));

            for (JsonNode bar : body.path("bars").path(symbol)) {
                LocalDate date = Instant.parse(bar.path("t").asString()).atZone(MARKET_ZONE).toLocalDate();
                bars.add(new ProviderBar(date, bar.path("c").decimalValue()));
            }
            JsonNode next = body.path("next_page_token");
            pageToken = next.isString() && !next.asString().isBlank() ? next.asString() : null;
        } while (pageToken != null);
        return bars;
    }

    /**
     * Ejecuta un pedido traduciendo cualquier fallo a MarketDataUnavailableException.
     * Al log va el codigo de estado y el motivo, nunca las cabeceras: llevan las claves.
     */
    private JsonNode call(String what, Supplier<JsonNode> request) {
        if (!properties.configured()) {
            throw new MarketDataUnavailableException(
                    "El proveedor de precios no esta configurado en este servidor.");
        }
        try {
            JsonNode body = request.get();
            if (body == null) {
                throw new MarketDataUnavailableException(UNAVAILABLE);
            }
            return body;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                log.error("Alpaca rechazo las credenciales al pedir {} ({})", what, e.getStatusCode());
            } else {
                log.warn("Alpaca respondio {} al pedir {}", e.getStatusCode(), what);
            }
            throw new MarketDataUnavailableException(UNAVAILABLE, e);
        } catch (RestClientException e) {
            log.warn("No se pudo contactar a Alpaca para pedir {}: {}", what, e.getMessage());
            throw new MarketDataUnavailableException(UNAVAILABLE, e);
        }
    }

}
