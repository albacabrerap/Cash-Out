package com.checkout.backend.projection.dto;

import java.math.BigDecimal;

/** Los resultados de un escenario calculado sin guardarlo. */
public record ProjectionPreviewResponse(
        BigDecimal totalContributed,
        BigDecimal simpleFinalAmount,
        BigDecimal compoundFinalAmount,
        BigDecimal difference) {
}
