package com.checkout.backend.market.alpaca;

import com.checkout.backend.market.MarketDataUnavailableException;
import com.checkout.backend.news.NewsClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.JsonNode;

/**
 * NewsClient sobre la API de noticias de Alpaca (fuente: Benzinga), en ingles.
 * Usa las mismas claves que los precios.
 */
public class AlpacaNewsClient implements NewsClient {

    private static final Logger log = LoggerFactory.getLogger(AlpacaNewsClient.class);
    private static final Pattern TAGS = Pattern.compile("<[^>]+>");
    /** La imagen "small" basta para una tarjeta; "large" pesa varias veces mas. */
    private static final List<String> IMAGE_SIZES = List.of("small", "large", "thumb");
    private static final String UNAVAILABLE =
            "No pudimos cargar las noticias en este momento. Intenta de nuevo en unos minutos.";

    private final AlpacaProperties properties;
    private final RestClient data;

    public AlpacaNewsClient(AlpacaProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        if (properties.configured()) {
            builder.defaultHeader("APCA-API-KEY-ID", properties.apiKey())
                    .defaultHeader("APCA-API-SECRET-KEY", properties.apiSecret());
        }
        this.data = builder.baseUrl(properties.dataUrl().toString()).build();
    }

    @Override
    public NewsPage latest(Collection<String> symbols, String pageToken, int limit) {
        if (!properties.configured()) {
            throw new MarketDataUnavailableException("Las noticias no estan configuradas en este servidor.");
        }
        JsonNode body;
        try {
            body = data.get()
                    .uri(uri -> {
                        uri.path("/v1beta1/news").queryParam("limit", limit).queryParam("sort", "desc");
                        if (!symbols.isEmpty()) {
                            uri.queryParam("symbols", String.join(",", symbols));
                        }
                        if (pageToken != null && !pageToken.isBlank()) {
                            uri.queryParam("page_token", pageToken);
                        }
                        return uri.build();
                    })
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            log.warn("Alpaca respondio {} al pedir noticias", e.getStatusCode());
            throw new MarketDataUnavailableException(UNAVAILABLE, e);
        } catch (RestClientException e) {
            log.warn("No se pudo contactar a Alpaca para pedir noticias: {}", e.getMessage());
            throw new MarketDataUnavailableException(UNAVAILABLE, e);
        }
        if (body == null) {
            throw new MarketDataUnavailableException(UNAVAILABLE);
        }

        List<ProviderNews> items = new ArrayList<>();
        for (JsonNode node : body.path("news")) {
            List<String> mentioned = new ArrayList<>();
            node.path("symbols").forEach(symbol -> mentioned.add(symbol.asString()));
            Instant created = Instant.parse(node.path("created_at").asString());
            items.add(new ProviderNews(
                    node.path("id").asLong(),
                    clean(node.path("headline").asString("")),
                    clean(node.path("summary").asString("")),
                    node.path("source").asString(null),
                    node.path("author").asString(null),
                    safeUrl(node.path("url").asString(null)),
                    created,
                    node.path("updated_at").isString() ? Instant.parse(node.path("updated_at").asString()) : created,
                    image(node.path("images")),
                    mentioned));
        }
        JsonNode next = body.path("next_page_token");
        return new NewsPage(items, next.isString() && !next.asString().isBlank() ? next.asString() : null);
    }

    /** Benzinga manda HTML y entidades en los resumenes; el front los mostraria tal cual. */
    private static String clean(String text) {
        return HtmlUtils.htmlUnescape(TAGS.matcher(text).replaceAll(" ")).replaceAll("\\s+", " ").strip();
    }

    /** Un enlace que no sea http o https (javascript:, data:) no llega al navegador. */
    private static String safeUrl(String url) {
        return url != null && (url.startsWith("https://") || url.startsWith("http://")) ? url : null;
    }

    private static String image(JsonNode images) {
        for (String size : IMAGE_SIZES) {
            for (JsonNode image : images) {
                if (size.equals(image.path("size").asString(null))) {
                    return safeUrl(image.path("url").asString(null));
                }
            }
        }
        return null;
    }

}
