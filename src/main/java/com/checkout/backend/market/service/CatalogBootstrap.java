package com.checkout.backend.market.service;

import com.checkout.backend.investment_portfolio.asset.model.AssetSource;
import com.checkout.backend.investment_portfolio.asset.repository.AssetRepository;
import com.checkout.backend.market.MarketProperties;
import com.checkout.backend.market.alpaca.AlpacaProperties;
import com.checkout.backend.market.coinbase.CoinbaseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * La primera vez que la aplicacion arranca con el proveedor configurado, carga
 * el catalogo de acciones. Despues lo actualiza un ADMIN con POST /assets/sync.
 * Los pares de cripto, en cambio, se revisan en cada arranque: son unos pocos y
 * asi sus nombres y bajas quedan al dia.
 *
 * Corre cuando la aplicacion ya esta lista, asi que el servidor atiende mientras
 * tanto, y un fallo aqui solo se registra: sin catalogo la aplicacion sigue
 * funcionando con los activos cargados a mano.
 */
@Component
public class CatalogBootstrap {

    private static final Logger log = LoggerFactory.getLogger(CatalogBootstrap.class);

    private final AlpacaProperties alpaca;
    private final CoinbaseProperties coinbase;
    private final MarketProperties market;
    private final AssetRepository assetRepository;
    private final AssetCatalogSyncService syncService;
    private final CryptoCatalogSyncService cryptoSync;

    public CatalogBootstrap(AlpacaProperties alpaca,
                            CoinbaseProperties coinbase,
                            MarketProperties market,
                            AssetRepository assetRepository,
                            AssetCatalogSyncService syncService,
                            CryptoCatalogSyncService cryptoSync) {
        this.alpaca = alpaca;
        this.coinbase = coinbase;
        this.market = market;
        this.assetRepository = assetRepository;
        this.syncService = syncService;
        this.cryptoSync = cryptoSync;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncIfEmpty() {
        if (!alpaca.configured() || !market.syncCatalogOnStartup()
                || assetRepository.existsBySource(AssetSource.ALPACA)) {
            return;
        }
        try {
            syncService.sync();
        } catch (RuntimeException e) {
            log.warn("No se pudo cargar el catalogo del proveedor al arrancar: {}", e.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncCrypto() {
        if (!coinbase.enabled()) {
            return;
        }
        try {
            cryptoSync.sync();
        } catch (RuntimeException e) {
            log.warn("No se pudieron cargar los pares de cripto al arrancar: {}", e.getMessage());
        }
    }

}
