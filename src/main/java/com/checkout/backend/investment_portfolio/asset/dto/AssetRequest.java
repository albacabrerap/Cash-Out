package com.checkout.backend.investment_portfolio.asset.dto;

import com.checkout.backend.investment_portfolio.asset.model.AssetType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Admin-only payload: the asset catalogue is curated, not user generated.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssetRequest {

    @NotBlank
    @Size(max = 15)
    private String symbol;

    @NotBlank
    @Size(max = 255)
    private String name;

    @NotNull
    private AssetType type;

    @NotBlank
    @Pattern(regexp = "^[A-Z]{3}$",
            message = "Currency must be a 3-letter ISO code")
    private String currency;

    private Boolean active;

}
