package com.checkout.backend.projection.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Lo que hace falta para calcular un escenario, sin nombre ni meta. Mismas reglas que ProjectionRequest. */
public record ProjectionPreviewRequest(
        @NotNull @DecimalMin("0") BigDecimal initialCapital,
        @NotNull @DecimalMin("0") BigDecimal monthlyContribution,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal annualRate,
        @NotNull @Min(1) @Max(600) Integer periods) {
}
