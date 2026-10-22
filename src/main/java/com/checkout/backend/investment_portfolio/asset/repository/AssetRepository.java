package com.checkout.backend.investment_portfolio.asset.repository;

import com.checkout.backend.investment_portfolio.asset.model.Asset;
import com.checkout.backend.investment_portfolio.asset.model.AssetSource;
import com.checkout.backend.investment_portfolio.asset.model.AssetType;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Acceso al catalogo de activos.
 */
public interface AssetRepository extends JpaRepository<Asset, Long> {

    Optional<Asset> findBySymbolIgnoreCase(String symbol);

    boolean existsBySymbolIgnoreCase(String symbol);

    boolean existsBySource(AssetSource source);

    List<Asset> findBySource(AssetSource source);

    List<Asset> findBySymbolInAndActiveTrue(Collection<String> symbols);

    /**
     * El activo tomado en exclusiva hasta el fin de la transaccion. Serializa a
     * quienes escriben su cotizacion o su historico: sin esto, dos pedidos que
     * llegan a la vez insertan la misma fila y uno choca con el UNIQUE.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Asset a where a.id = :id")
    Optional<Asset> findByIdForUpdate(@Param("id") Long id);

    /**
     * Busqueda paginada por simbolo o nombre.
     *
     * Primero el simbolo exacto, despues los que empiezan con lo buscado y al
     * final el resto: quien escribe "AAPL" busca Apple, no los fondos que tienen
     * "aapl" en el nombre. Los patrones llegan escapados por SearchTerm.
     *
     * @param pattern null para no filtrar por texto
     * @param exact   el texto buscado en minusculas, o "" si no hay busqueda
     * @param prefix  patron "empieza con", o "" si no hay busqueda
     * @param startsWith patron "el simbolo empieza con", o null para no filtrar
     */
    @Query(value = """
            select a from Asset a
            where (:includeInactive = true or a.active = true)
              and (:type is null or a.type = :type)
              and (:startsWith is null or lower(a.symbol) like :startsWith escape '\\')
              and (:pattern is null
                   or lower(a.symbol) like :pattern escape '\\'
                   or lower(a.name) like :pattern escape '\\')
            order by case
                       when lower(a.symbol) = :exact then 0
                       when lower(a.symbol) like :prefix escape '\\' then 1
                       else 2
                     end,
                     a.symbol
            """,
            countQuery = """
            select count(a) from Asset a
            where (:includeInactive = true or a.active = true)
              and (:type is null or a.type = :type)
              and (:startsWith is null or lower(a.symbol) like :startsWith escape '\\')
              and (:pattern is null
                   or lower(a.symbol) like :pattern escape '\\'
                   or lower(a.name) like :pattern escape '\\')
            """)
    Page<Asset> search(@Param("includeInactive") boolean includeInactive,
                       @Param("type") AssetType type,
                       @Param("startsWith") String startsWith,
                       @Param("pattern") String pattern,
                       @Param("exact") String exact,
                       @Param("prefix") String prefix,
                       Pageable pageable);
}
