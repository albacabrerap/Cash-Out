package com.checkout.backend.investment_portfolio.watchlist.model;

import com.checkout.backend.investment_portfolio.asset.model.Asset;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/** Un activo que el usuario sigue desde el mercado. */
@Entity
@Table(name = "watchlist_items",
        uniqueConstraints = @UniqueConstraint(name = "uk_watchlist_user_asset", columnNames = {"user_id", "asset_id"}))
@Getter
@Setter
@NoArgsConstructor
public class WatchlistItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false)
    private Asset asset;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public WatchlistItem(Long userId, Asset asset) {
        this.userId = userId;
        this.asset = asset;
    }

}
