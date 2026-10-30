package com.checkout.backend.portfoliogame.dto;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

/** El reparto en %, por clave de sector ("tech", "gold"…). Las reglas las revisa PortfolioGameEngine. */
public record AllocationRequest(@NotNull Map<String, Integer> weights) {
}
