package com.checkout.backend.investment_portfolio.asset.history.repository;

import com.checkout.backend.investment_portfolio.asset.history.model.AssetPriceHistory;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Acceso al cierre diario de cada activo.
 *
 * Es lo que permite dibujar un grafico: la cotizacion viva se sobrescribe, asi
 * que sin estas filas el valor pasado de una posicion no se puede reconstruir.
 */
public interface AssetPriceHistoryRepository extends JpaRepository<AssetPriceHistory, Long> {

    List<AssetPriceHistory> findByAssetIdAndDateBetweenOrderByDateAsc(
            Long assetId, LocalDate from, LocalDate to);

    List<AssetPriceHistory> findByAssetIdOrderByDateAsc(Long assetId);

    @Query("select max(h.date) from AssetPriceHistory h where h.asset.id = :assetId")
    Optional<LocalDate> findLatestDate(@Param("assetId") Long assetId);

    @Query("select h.date from AssetPriceHistory h where h.asset.id = :assetId and h.date between :from and :to")
    Set<LocalDate> findDatesBetween(@Param("assetId") Long assetId,
                                    @Param("from") LocalDate from,
                                    @Param("to") LocalDate to);
}
