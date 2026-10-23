package com.checkout.backend.investment_portfolio.asset.dto;

import com.checkout.backend.investment_portfolio.asset.model.AssetSource;
import com.checkout.backend.investment_portfolio.asset.model.AssetType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssetResponse {

    private Long id;

    private String symbol;

    private String name;

    private AssetType type;

    private String currency;

    private Boolean active;

    private AssetSource source;

    private String exchange;

}
