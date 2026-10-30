package com.checkout.backend.investment_portfolio.watchlist.repository;

import com.checkout.backend.investment_portfolio.watchlist.model.WatchlistItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WatchlistItemRepository extends JpaRepository<WatchlistItem, Long> {

    /** Del mas reciente al mas antiguo, con el activo ya cargado para no hacer una consulta por fila. */
    @Query("select w from WatchlistItem w join fetch w.asset where w.userId = :userId order by w.createdAt desc, w.id desc")
    List<WatchlistItem> findByUserIdWithAsset(@Param("userId") Long userId);

    boolean existsByUserIdAndAssetId(Long userId, Long assetId);

    long countByUserId(Long userId);

    @Modifying
    @Query("delete from WatchlistItem w where w.userId = :userId and w.asset.id = :assetId")
    int deleteByUserIdAndAssetId(@Param("userId") Long userId, @Param("assetId") Long assetId);

}
