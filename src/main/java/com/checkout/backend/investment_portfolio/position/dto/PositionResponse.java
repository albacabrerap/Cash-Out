package com.checkout.backend.investment_portfolio.position.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The last three fields are derived from the quote cache in the service.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PositionResponse {

    private Long id;

    private Long assetId;

    private String symbol;

    private String assetName;

    private BigDecimal quantity;

    private BigDecimal averageCost;

    private BigDecimal currentPrice;

    private BigDecimal marketValue;

    private BigDecimal unrealizedPnl;

}
