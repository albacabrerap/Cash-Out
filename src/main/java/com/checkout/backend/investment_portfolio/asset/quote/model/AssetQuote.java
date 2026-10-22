package com.checkout.backend.investment_portfolio.asset.quote.model;

import com.checkout.backend.investment_portfolio.asset.model.Asset;
import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Cache of the current price of an asset, refreshed in batch by a scheduled
 * job. It is not a price history: the provider already exposes historical
 * candles on demand.
 *
 * Order execution reads the price from here and never calls the provider
 * inside a database transaction.
 */
@Entity
@Table(
        name = "asset_quotes",
        uniqueConstraints = @UniqueConstraint(name = "uk_asset_quotes_asset", columnNames = "asset_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssetQuote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false, unique = true,
            foreignKey = @ForeignKey(name = "fk_asset_quotes_asset"))
    private Asset asset;

    @NotNull
    @DecimalMin(value = "0.0", inclusive = false)
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal price;

    @Column(name = "previous_close", precision = 19, scale = 4)
    private BigDecimal previousClose;

    @Column(name = "change_percent", precision = 9, scale = 4)
    private BigDecimal changePercent;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Day change in percent with four decimals, or null when there is no previous close to compare. */
    public static BigDecimal percentChange(BigDecimal previous, BigDecimal current) {
        if (previous == null || previous.signum() == 0 || current == null) {
            return null;
        }
        return current.subtract(previous)
                .multiply(BigDecimal.valueOf(100))
                .divide(previous, 4, RoundingMode.HALF_UP);
    }

    /** An order placed against a stale price must be rejected, not guessed. */
    public boolean isStale(Duration maxAge) {
        return updatedAt == null || updatedAt.isBefore(LocalDateTime.now().minus(maxAge));
    }

    /** Identity is the primary key; two unsaved instances are only equal to themselves. */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AssetQuote other)) return false;
        return id != null && id.equals(other.id);
    }

    /** Constant on purpose: the hash must not change when the id is assigned on persist. */
    @Override
    public int hashCode() {
        return AssetQuote.class.hashCode();
    }
}
