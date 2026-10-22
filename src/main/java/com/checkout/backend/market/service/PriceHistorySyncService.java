package com.checkout.backend.market.service;

import com.checkout.backend.exceptions.ResourceNotFoundException;
import com.checkout.backend.investment_portfolio.asset.history.model.AssetPriceHistory;
import com.checkout.backend.investment_portfolio.asset.history.repository.AssetPriceHistoryRepository;
import com.checkout.backend.investment_portfolio.asset.model.Asset;
import com.checkout.backend.investment_portfolio.asset.model.AssetSource;
import com.checkout.backend.investment_portfolio.asset.repository.AssetRepository;
import com.checkout.backend.market.CryptoDataClient;
import com.checkout.backend.market.MarketDataClient;
import com.checkout.backend.market.MarketDataClient.ProviderBar;
import com.checkout.backend.market.MarketDataUnavailableException;
import com.checkout.backend.market.MarketProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Completa el historico diario de un activo del proveedor la primera vez que
 * alguien lo pide, y despues solo agrega los dias que faltan.
 *
 * Descargar 5 anios de 14 mil acciones seria casi 18 millones de filas que nadie
 * va a mirar. Asi, cada accion cuesta un pedido la primera vez que se abre su
 * grafico, y uno pequeno cada historyRefresh.
 *
 * Solo guarda jornadas cerradas, hasta ayer en Nueva York. El dia en curso no
 * tiene cierre todavia: su precio es la cotizacion viva. La cripto no cierra
 * nunca, asi que sus dias se cuentan en UTC, como los cuenta Coinbase.
 */
@Service
public class PriceHistorySyncService {

    private static final Logger log = LoggerFactory.getLogger(PriceHistorySyncService.class);
    private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");

    private final AssetRepository assetRepository;
    private final AssetPriceHistoryRepository historyRepository;
    private final MarketDataClient client;
    private final CryptoDataClient cryptoClient;
    private final MarketProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public PriceHistorySyncService(AssetRepository assetRepository,
                                   AssetPriceHistoryRepository historyRepository,
                                   MarketDataClient client,
                                   CryptoDataClient cryptoClient,
                                   MarketProperties properties,
                                   TransactionTemplate transaction,
                                   Clock clock) {
        this.assetRepository = assetRepository;
        this.historyRepository = historyRepository;
        this.client = client;
        this.cryptoClient = cryptoClient;
        this.properties = properties;
        this.transaction = transaction;
        this.clock = clock;
    }

    public void ensureHistory(Long assetId) {
        record State(Asset asset, Optional<LocalDate> latest) {
        }
        State state = transaction.execute(status -> {
            Asset asset = assetRepository.findById(assetId)
                    .orElseThrow(() -> new ResourceNotFoundException("Activo", assetId));
            return new State(asset, historyRepository.findLatestDate(assetId));
        });

        Asset asset = state.asset();
        if (!asset.getSource().fromProvider()) {
            return;
        }
        boolean crypto = asset.getSource() == AssetSource.COINBASE;
        LocalDateTime checkedAt = asset.getHistorySyncedAt();
        if (checkedAt != null && checkedAt.isAfter(LocalDateTime.now().minus(properties.historyRefresh()))) {
            return;
        }

        LocalDate end = LocalDate.now(clock.withZone(crypto ? ZoneOffset.UTC : MARKET_ZONE)).minusDays(1);
        LocalDate start = state.latest()
                .map(date -> date.plusDays(1))
                .orElse(end.minusYears(properties.historyYears()));

        List<ProviderBar> bars = List.of();
        if (!start.isAfter(end)) {
            try {
                bars = crypto
                        ? cryptoClient.dailyBars(asset.getSymbol(), start, end)
                        : client.dailyBars(asset.getSymbol(), start, end);
            } catch (MarketDataUnavailableException e) {
                if (state.latest().isPresent()) {
                    log.warn("Sin historico nuevo para {}; se muestra el guardado", asset.getSymbol());
                    return;
                }
                throw e;
            }
        }

        List<ProviderBar> received = bars;
        transaction.executeWithoutResult(status -> store(assetId, start, end, received));
    }

    private void store(Long assetId, LocalDate start, LocalDate end, List<ProviderBar> bars) {
        // El bloqueo serializa a dos pedidos simultaneos del mismo grafico: el
        // segundo ve las fechas que inserto el primero y no las repite.
        Asset asset = assetRepository.findByIdForUpdate(assetId)
                .orElseThrow(() -> new ResourceNotFoundException("Activo", assetId));
        Set<LocalDate> stored = start.isAfter(end)
                ? Set.of()
                : historyRepository.findDatesBetween(assetId, start, end);

        List<AssetPriceHistory> missing = bars.stream()
                .filter(bar -> bar.close() != null && bar.close().signum() > 0)
                .filter(bar -> !stored.contains(bar.date()))
                .map(bar -> AssetPriceHistory.builder()
                        .asset(asset)
                        .date(bar.date())
                        .closePrice(bar.close())
                        .build())
                .toList();
        historyRepository.saveAll(missing);
        asset.setHistorySyncedAt(LocalDateTime.now());
    }

}
