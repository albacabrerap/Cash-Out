package com.checkout.backend.market.service;

import com.checkout.backend.exceptions.DuplicateResourceException;
import com.checkout.backend.investment_portfolio.asset.model.Asset;
import com.checkout.backend.investment_portfolio.asset.model.AssetSource;
import com.checkout.backend.investment_portfolio.asset.model.AssetType;
import com.checkout.backend.investment_portfolio.asset.repository.AssetRepository;
import com.checkout.backend.market.MarketDataClient;
import com.checkout.backend.market.MarketDataClient.ProviderAsset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Trae el universo de acciones del proveedor al catalogo.
 *
 * El pedido al proveedor va fuera de la transaccion (no se retiene una conexion
 * de la base mientras se espera a la red) y la escritura va en una sola: o se
 * aplica la lista completa, o nada.
 *
 * Reglas:
 * <ul>
 *   <li>Solo entran los activos que el proveedor marca como operables.</li>
 *   <li>Un activo cargado a mano con el mismo simbolo pasa a ser del proveedor:
 *       conserva su id, porque posiciones y ordenes lo referencian, y desde ahi
 *       su precio sale del mercado.</li>
 *   <li>Un activo del proveedor que deja de aparecer se da de baja, no se borra.
 *       Las posiciones abiertas sobre el siguen valorandose.</li>
 * </ul>
 */
@Service
public class AssetCatalogSyncService {

    private static final Logger log = LoggerFactory.getLogger(AssetCatalogSyncService.class);

    /** Alpaca no marca los ETF; el nombre si lo dice ("SPDR S&P 500 ETF Trust"). */
    private static final Pattern ETF_NAME = Pattern.compile("\\bETF\\b", Pattern.CASE_INSENSITIVE);

    private static final int MAX_SYMBOL = 15;
    private static final int MAX_NAME = 255;

    private final MarketDataClient client;
    private final AssetRepository assetRepository;
    private final TransactionTemplate transaction;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AssetCatalogSyncService(MarketDataClient client,
                                   AssetRepository assetRepository,
                                   TransactionTemplate transaction) {
        this.client = client;
        this.assetRepository = assetRepository;
        this.transaction = transaction;
    }

    public record SyncResult(int received, int created, int updated, int deactivated) {
    }

    public SyncResult sync() {
        if (!running.compareAndSet(false, true)) {
            throw new DuplicateResourceException("Ya hay una sincronizacion del catalogo en curso.");
        }
        try {
            List<ProviderAsset> provided = client.listUsEquities();
            SyncResult result = transaction.execute(status -> apply(provided));
            log.info("Catalogo sincronizado: {}", result);
            return result;
        } finally {
            running.set(false);
        }
    }

    private SyncResult apply(List<ProviderAsset> provided) {
        Map<String, Asset> bySymbol = assetRepository.findAll().stream()
                .collect(Collectors.toMap(asset -> normalize(asset.getSymbol()), Function.identity()));

        Set<String> seen = new HashSet<>();
        List<Asset> created = new ArrayList<>();
        int updated = 0;

        for (ProviderAsset item : provided) {
            String symbol = normalize(item.symbol());
            if (!item.tradable() || symbol.isEmpty() || symbol.length() > MAX_SYMBOL || !seen.add(symbol)) {
                continue;
            }
            String name = name(item);
            AssetType type = ETF_NAME.matcher(name).find() ? AssetType.ETF : AssetType.STOCK;
            String exchange = item.exchange() == null ? null : truncate(item.exchange(), 20);

            Asset asset = bySymbol.get(symbol);
            if (asset == null) {
                created.add(Asset.builder()
                        .symbol(symbol)
                        .name(name)
                        .type(type)
                        .currency("USD")
                        .active(true)
                        .source(AssetSource.ALPACA)
                        .exchange(exchange)
                        .build());
            } else if (changes(asset, name, type, exchange)) {
                asset.setName(name);
                asset.setType(type);
                asset.setExchange(exchange);
                asset.setActive(true);
                asset.setSource(AssetSource.ALPACA);
                updated++;
            }
        }

        int deactivated = 0;
        for (Asset asset : bySymbol.values()) {
            if (asset.getSource() == AssetSource.ALPACA
                    && Boolean.TRUE.equals(asset.getActive())
                    && !seen.contains(normalize(asset.getSymbol()))) {
                asset.setActive(false);
                deactivated++;
            }
        }

        assetRepository.saveAll(created);
        return new SyncResult(provided.size(), created.size(), updated, deactivated);
    }

    private static boolean changes(Asset asset, String name, AssetType type, String exchange) {
        return asset.getSource() != AssetSource.ALPACA
                || !Boolean.TRUE.equals(asset.getActive())
                || !asset.getName().equals(name)
                || asset.getType() != type
                || !Objects.equals(asset.getExchange(), exchange);
    }

    private static String name(ProviderAsset item) {
        String name = item.name() == null || item.name().isBlank() ? item.symbol() : item.name().strip();
        return truncate(name, MAX_NAME);
    }

    private static String normalize(String symbol) {
        return symbol == null ? "" : symbol.strip().toUpperCase(Locale.ROOT);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

}
