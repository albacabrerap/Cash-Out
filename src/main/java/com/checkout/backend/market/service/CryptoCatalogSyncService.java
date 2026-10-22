package com.checkout.backend.market.service;

import com.checkout.backend.investment_portfolio.asset.model.Asset;
import com.checkout.backend.investment_portfolio.asset.model.AssetSource;
import com.checkout.backend.investment_portfolio.asset.model.AssetType;
import com.checkout.backend.investment_portfolio.asset.repository.AssetRepository;
import com.checkout.backend.market.CryptoDataClient;
import com.checkout.backend.market.CryptoDataClient.CryptoProduct;
import com.checkout.backend.market.coinbase.CoinbaseProperties;
import com.checkout.backend.market.service.AssetCatalogSyncService.SyncResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pone en el catalogo los pares de cripto configurados.
 *
 * Los elige la configuracion, no el proveedor. Un par que sale de la lista, o que
 * Coinbase suspende, se da de baja y no se borra: puede haber posiciones abiertas.
 */
@Service
public class CryptoCatalogSyncService {

    private static final Logger log = LoggerFactory.getLogger(CryptoCatalogSyncService.class);
    private static final int MAX_NAME = 255;

    private final CryptoDataClient client;
    private final CoinbaseProperties properties;
    private final AssetRepository assetRepository;
    private final TransactionTemplate transaction;

    public CryptoCatalogSyncService(CryptoDataClient client,
                                    CoinbaseProperties properties,
                                    AssetRepository assetRepository,
                                    TransactionTemplate transaction) {
        this.client = client;
        this.properties = properties;
        this.assetRepository = assetRepository;
        this.transaction = transaction;
    }

    public SyncResult sync() {
        List<CryptoProduct> provided = client.products(properties.products());
        SyncResult result = transaction.execute(status -> apply(provided));
        log.info("Pares de cripto sincronizados: {}", result);
        return result;
    }

    private SyncResult apply(List<CryptoProduct> provided) {
        Set<String> listed = new HashSet<>();
        List<Asset> created = new ArrayList<>();
        int updated = 0;

        for (CryptoProduct product : provided) {
            if (!product.tradable()) {
                continue;
            }
            String symbol = product.id().strip().toUpperCase(Locale.ROOT);
            String name = product.name().length() <= MAX_NAME ? product.name() : product.name().substring(0, MAX_NAME);
            listed.add(symbol);

            Optional<Asset> existing = assetRepository.findBySymbolIgnoreCase(symbol);
            if (existing.isEmpty()) {
                created.add(Asset.builder()
                        .symbol(symbol)
                        .name(name)
                        .type(AssetType.CRYPTO)
                        .currency("USD")
                        .active(true)
                        .source(AssetSource.COINBASE)
                        .exchange("COINBASE")
                        .build());
                continue;
            }
            Asset asset = existing.get();
            if (asset.getSource() != AssetSource.COINBASE || !Boolean.TRUE.equals(asset.getActive())
                    || !asset.getName().equals(name) || asset.getType() != AssetType.CRYPTO) {
                asset.setName(name);
                asset.setType(AssetType.CRYPTO);
                asset.setActive(true);
                asset.setSource(AssetSource.COINBASE);
                asset.setExchange("COINBASE");
                updated++;
            }
        }

        int deactivated = 0;
        for (Asset asset : assetRepository.findBySource(AssetSource.COINBASE)) {
            if (Boolean.TRUE.equals(asset.getActive()) && !listed.contains(asset.getSymbol())) {
                asset.setActive(false);
                deactivated++;
            }
        }

        assetRepository.saveAll(created);
        return new SyncResult(provided.size(), created.size(), updated, deactivated);
    }

}
