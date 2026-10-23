package com.checkout.backend.investment_portfolio.asset.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.*;

/**
 * Catalogue of tradable assets: the US equity universe synced from Alpaca plus
 * any asset an admin adds by hand (see AssetSource).
 */
@Entity
@Table(
        name = "assets",
        uniqueConstraints = @UniqueConstraint(name = "uk_assets_symbol", columnNames = "symbol")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Asset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 15)
    @Column(nullable = false, length = 15)
    private String symbol;

    /** Provider names reach 229 characters ("... Common Stock", fund names). */
    @NotBlank
    @Size(max = 255)
    @Column(nullable = false, length = 255)
    private String name;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AssetType type;

    /**
     * ISO 4217 code. Kept on the asset so the display decision stays in the
     * service layer: the provider quotes US equities in USD.
     */
    @NotBlank
    @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a 3-letter ISO code")
    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "USD";

    @NotNull
    @Column(nullable = false)
    @Builder.Default
    private Boolean active = Boolean.TRUE;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private AssetSource source = AssetSource.MANUAL;

    /** Listing venue as the provider reports it (NASDAQ, NYSE, ARCA...). Null for manual assets. */
    @Size(max = 20)
    @Column(length = 20)
    private String exchange;

    /**
     * Last time the daily history was checked against the provider. Kept so a
     * chart viewed on a weekend does not ask the provider again on every load
     * for bars that cannot exist yet.
     */
    @Column(name = "history_synced_at")
    private LocalDateTime historySyncedAt;

    /** Identity is the primary key; two unsaved instances are only equal to themselves. */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Asset other)) return false;
        return id != null && id.equals(other.id);
    }

    /** Constant on purpose: the hash must not change when the id is assigned on persist. */
    @Override
    public int hashCode() {
        return Asset.class.hashCode();
    }
}
