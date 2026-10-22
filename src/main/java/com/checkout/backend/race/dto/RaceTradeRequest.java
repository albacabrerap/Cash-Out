package com.checkout.backend.race.dto;

import com.checkout.backend.investment_portfolio.trade_order.model.OrderSide;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Comprar o vender una fraccion (0.25, 0.5 o 1) del efectivo o de lo invertido. */
public record RaceTradeRequest(@NotNull OrderSide side, @NotNull BigDecimal fraction) {
}
