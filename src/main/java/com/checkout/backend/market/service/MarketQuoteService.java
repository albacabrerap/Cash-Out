package com.checkout.backend.market.service;

import com.checkout.backend.exceptions.ResourceNotFoundException;
import com.checkout.backend.investment_portfolio.asset.model.Asset;
import com.checkout.backend.investment_portfolio.asset.model.AssetSource;
import com.checkout.backend.investment_portfolio.asset.quote.model.AssetQuote;
import com.checkout.backend.investment_portfolio.asset.quote.repository.AssetQuoteRepository;
import com.checkout.backend.investment_portfolio.asset.repository.AssetRepository;
import com.checkout.backend.market.CryptoDataClient;
import com.checkout.backend.market.MarketDataClient;
import com.checkout.backend.market.MarketDataClient.ProviderSnapshot;
import com.checkout.backend.market.MarketDataUnavailableException;
import com.checkout.backend.market.MarketProperties;
import com.checkout.backend.market.live.LivePriceService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Mantiene al dia la cotizacion cacheada de los activos del proveedor.
 *
 * El precio se pide recien cuando alguien lo necesita (mostrarlo, operar) y se
 * reutiliza durante quoteTtl: con 14 mil acciones no tiene sentido refrescarlas
 * todas, y el plan gratuito de Alpaca limita los pedidos por minuto.
 *
 * Se llama fuera de cualquier transaccion: la orden de compra, en particular, no
 * debe retener la base mientras espera la red. Aqui las transacciones son
 * cortas y solo envuelven la lectura y la escritura.
 */
@Service
public class MarketQuoteService {

    private static final Logger log = LoggerFactory.getLogger(MarketQuoteService.class);

    private final AssetRepository assetRepository;
    private final AssetQuoteRepository quoteRepository;
    private final MarketDataClient client;
    private final CryptoDataClient cryptoClient;
    private final LivePriceService livePrices;
    private final MarketProperties properties;
    private final TransactionTemplate transaction;

    public MarketQuoteService(AssetRepository assetRepository,
                              AssetQuoteRepository quoteRepository,
                              MarketDataClient client,
                              CryptoDataClient cryptoClient,
                              LivePriceService livePrices,
                              MarketProperties properties,
                              TransactionTemplate transaction) {
        this.assetRepository = assetRepository;
        this.quoteRepository = quoteRepository;
        this.client = client;
        this.cryptoClient = cryptoClient;
        this.livePrices = livePrices;
        this.properties = properties;
        this.transaction = transaction;
    }

    /**
     * Refresca la cotizacion si es de un activo del proveedor y ya no esta fresca.
     * Con un activo manual no hace nada: su precio lo fija un ADMIN.
     *
     * Si el proveedor falla y hay un precio guardado, se queda con ese: mostrar
     * el ultimo precio conocido, con su hora, es mejor que una pantalla de error.
     * Para operar, AssetService.requireQuote decide si sigue sirviendo.
     */
    public void refreshIfStale(Long assetId) {
        record Cached(Asset asset, Optional<LocalDateTime> updatedAt) {
        }
        Cached cached = transaction.execute(status -> {
            Asset asset = assetRepository.findById(assetId)
                    .orElseThrow(() -> new ResourceNotFoundException("Activo", assetId));
            return new Cached(asset, quoteRepository.findByAssetId(assetId).map(AssetQuote::getUpdatedAt));
        });

        if (cached.asset().getSource() == AssetSource.COINBASE) {
            refreshCrypto(assetId, cached.asset().getSymbol(), cached.updatedAt());
            return;
        }
        if (cached.asset().getSource() != AssetSource.ALPACA) {
            return;
        }
        LocalDateTime freshSince = LocalDateTime.now().minus(properties.quoteTtl());
        if (cached.updatedAt().filter(time -> time.isAfter(freshSince)).isPresent()) {
            return;
        }

        String symbol = cached.asset().getSymbol();
        ProviderSnapshot snapshot;
        try {
            snapshot = client.snapshots(List.of(symbol)).get(symbol);
        } catch (MarketDataUnavailableException e) {
            if (cached.updatedAt().isPresent()) {
                log.warn("Sin precio nuevo para {}; se usa el guardado del {}", symbol, cached.updatedAt().get());
                return;
            }
            throw e;
        }

        if (snapshot == null || snapshot.lastPrice() == null || snapshot.lastPrice().signum() <= 0) {
            if (cached.updatedAt().isPresent()) {
                return;
            }
            throw new MarketDataUnavailableException(
                    "El mercado no reporta un precio para " + symbol + " en este momento.");
        }

        transaction.executeWithoutResult(status -> write(assetId, snapshot));
    }

    /**
     * Refresca de una vez las cotizaciones vencidas de varios activos: es para
     * valorar un portafolio entero con un solo pedido al proveedor, no uno por
     * posicion.
     *
     * Si el proveedor falla no lanza nada: el portafolio se valora con los
     * ultimos precios conocidos, que es mejor que no poder abrirlo.
     */
    public void refreshStale(Collection<Long> assetIds) {
        if (assetIds.isEmpty()) {
            return;
        }
        List<Long> crypto = transaction.execute(status -> assetRepository.findAllById(assetIds).stream()
                .filter(asset -> asset.getSource() == AssetSource.COINBASE)
                .map(Asset::getId)
                .toList());
        for (Long assetId : crypto) {
            try {
                refreshIfStale(assetId);
            } catch (MarketDataUnavailableException e) {
                log.warn("Sin precio nuevo para la cripto {}; se usa el guardado", assetId);
            }
        }

        LocalDateTime freshSince = LocalDateTime.now().minus(properties.quoteTtl());
        Map<Long, String> stale = transaction.execute(status -> {
            Map<Long, LocalDateTime> updatedAt = quoteRepository.findByAssetIdIn(assetIds).stream()
                    .collect(Collectors.toMap(quote -> quote.getAsset().getId(), AssetQuote::getUpdatedAt));
            return assetRepository.findAllById(assetIds).stream()
                    .filter(asset -> asset.getSource() == AssetSource.ALPACA)
                    .filter(asset -> {
                        LocalDateTime time = updatedAt.get(asset.getId());
                        return time == null || !time.isAfter(freshSince);
                    })
                    .collect(Collectors.toMap(Asset::getId, Asset::getSymbol));
        });
        if (stale.isEmpty()) {
            return;
        }

        Map<String, ProviderSnapshot> snapshots;
        try {
            snapshots = client.snapshots(stale.values());
        } catch (MarketDataUnavailableException e) {
            log.warn("Sin precios nuevos para valorar {} activos; se usan los guardados", stale.size());
            return;
        }
        transaction.executeWithoutResult(status -> stale.forEach((assetId, symbol) -> {
            ProviderSnapshot snapshot = snapshots.get(symbol);
            if (snapshot != null && snapshot.lastPrice() != null && snapshot.lastPrice().signum() > 0) {
                write(assetId, snapshot);
            }
        }));
    }

    /**
     * La cripto toma el precio del stream en vivo, sin red. A Coinbase solo se le
     * pregunta cuando la cotizacion vencio, para traer la apertura de 24 horas, que
     * hace de cierre anterior en un mercado que nunca cierra.
     */
    private void refreshCrypto(Long assetId, String productId, Optional<LocalDateTime> updatedAt) {
        Optional<BigDecimal> live = livePrices.currentPrice(productId);
        LocalDateTime freshSince = LocalDateTime.now().minus(properties.quoteTtl());
        if (updatedAt.filter(time -> time.isAfter(freshSince)).isPresent()) {
            live.ifPresent(price -> transaction.executeWithoutResult(status -> writeLivePrice(assetId, price)));
            return;
        }

        ProviderSnapshot snapshot;
        try {
            snapshot = cryptoClient.snapshot(productId);
        } catch (MarketDataUnavailableException e) {
            if (updatedAt.isPresent()) {
                live.ifPresent(price -> transaction.executeWithoutResult(status -> writeLivePrice(assetId, price)));
                log.warn("Sin apertura de 24 h para {}; se mantiene la guardada", productId);
                return;
            }
            throw e;
        }

        BigDecimal price = live.orElse(snapshot.lastPrice());
        if (price == null || price.signum() <= 0) {
            if (updatedAt.isPresent()) {
                return;
            }
            throw new MarketDataUnavailableException(
                    "Coinbase no reporta un precio para " + productId + " en este momento.");
        }
        transaction.executeWithoutResult(status ->
                write(assetId, new ProviderSnapshot(productId, price, null, snapshot.previousClose())));
    }

    /** updatedAt queda en la ultima consulta a Coinbase: asi la apertura de 24 h se renueva cada quoteTtl. */
    private void writeLivePrice(Long assetId, BigDecimal price) {
        quoteRepository.findByAssetId(assetId).ifPresent(quote -> {
            quote.setPrice(price);
            quote.setChangePercent(AssetQuote.percentChange(quote.getPreviousClose(), price));
            quoteRepository.save(quote);
        });
    }

    private void write(Long assetId, ProviderSnapshot snapshot) {
        Asset asset = assetRepository.findByIdForUpdate(assetId)
                .orElseThrow(() -> new ResourceNotFoundException("Activo", assetId));
        AssetQuote quote = quoteRepository.findByAssetId(assetId)
                .orElseGet(() -> AssetQuote.builder().asset(asset).build());
        quote.setPrice(snapshot.lastPrice());
        quote.setPreviousClose(snapshot.previousClose());
        quote.setChangePercent(AssetQuote.percentChange(snapshot.previousClose(), snapshot.lastPrice()));
        quote.setUpdatedAt(LocalDateTime.now());
        quoteRepository.save(quote);
    }

}
