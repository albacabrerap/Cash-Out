package com.checkout.backend.investment_portfolio.trade_order.dto;

import com.checkout.backend.investment_portfolio.trade_order.model.OrderSide;
import com.checkout.backend.investment_portfolio.trade_order.model.OrderStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Rejected orders are returned too, with the reason, so the owner can see why a purchase failed.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TradeOrderResponse {

    private Long id;

    private Long assetId;

    private String symbol;

    private OrderSide side;

    private BigDecimal quantity;

    private OrderStatus status;

    private BigDecimal executionPrice;

    private BigDecimal tokensMoved;

    /** Tokens per unit of the asset currency at execution time. */
    private BigDecimal tokenRate;

    private String rejectionReason;

    private LocalDateTime createdAt;

    private LocalDateTime executedAt;

}
