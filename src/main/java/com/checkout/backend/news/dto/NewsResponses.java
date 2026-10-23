package com.checkout.backend.news.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Respuestas del mural de noticias. */
public final class NewsResponses {

    private NewsResponses() {
    }

    public record NewsPageResponse(List<NewsItemResponse> items, String nextPageToken) {
    }

    /** @param assets los activos del catalogo que menciona la nota, con su variacion del dia */
    public record NewsItemResponse(long id, String headline, String summary, String source, String author,
                                   String url, String imageUrl, Instant publishedAt, Instant updatedAt,
                                   List<NewsAssetResponse> assets) {
    }

    public record NewsAssetResponse(long assetId, String symbol, String name, BigDecimal changePercent) {
    }

    public record TrendingAssetResponse(long assetId, String symbol, String name, int mentions) {
    }

}
