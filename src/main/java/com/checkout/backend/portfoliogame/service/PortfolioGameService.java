package com.checkout.backend.portfoliogame.service;

import com.checkout.backend.game.GameRooms;
import com.checkout.backend.market.MarketDataClient;
import com.checkout.backend.market.MarketDataClient.ProviderBar;
import com.checkout.backend.market.MarketDataUnavailableException;
import com.checkout.backend.market.alpaca.AlpacaProperties;
import com.checkout.backend.portfoliogame.dto.PortfolioGameView;
import com.checkout.backend.portfoliogame.model.PortfolioPeriod;
import com.checkout.backend.portfoliogame.model.PortfolioSector;
import com.checkout.backend.portfoliogame.service.PortfolioGameEngine.Room;
import com.checkout.backend.portfoliogame.service.PortfolioGameEngine.Stage;
import com.checkout.backend.user.model.User;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * "Arma tu portafolio": cada etapa es un semestre real y el retorno de cada
 * sector sale de los cierres de su ETF, asi que nadie lo puede inventar.
 */
@Service
public class PortfolioGameService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioGameService.class);

    static final int STAGES = 5;
    private static final LocalDate DATA_FROM = LocalDate.of(2021, 12, 1);
    private static final LocalDate DATA_TO = LocalDate.of(2026, 1, 10);

    private final PortfolioGameEngine engine;
    private final MarketDataClient market;
    private final AlpacaProperties alpaca;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, NavigableMap<LocalDate, Double>> closesBySymbol = new ConcurrentHashMap<>();

    public PortfolioGameService(PortfolioGameEngine engine, MarketDataClient market, AlpacaProperties alpaca) {
        this.engine = engine;
        this.market = market;
        this.alpaca = alpaca;
    }

    /** Cinco semestres al azar, en orden cronologico para que la historia se lea de corrido. */
    public PortfolioGameView create(User host) {
        List<PortfolioPeriod> periods = new ArrayList<>(PortfolioPeriod.ALL);
        Collections.shuffle(periods, random);
        List<Stage> stages = periods.subList(0, STAGES).stream()
                .sorted(Comparator.comparing(PortfolioPeriod::from))
                .map(this::stage)
                .toList();
        Room room = engine.open(GameRooms.newCode(random, engine::exists), host.getId(), stages);
        return engine.view(room, host.getId());
    }

    public PortfolioGameView view(User user, String code) {
        return engine.view(engine.require(code), user.getId());
    }

    public SseEmitter subscribe(User user, String code) {
        return engine.subscribe(engine.require(code), user.getId());
    }

    public PortfolioGameView join(User user, String code) {
        Room room = engine.require(code);
        engine.join(room, user.getId(), GameRooms.firstName(user));
        return engine.view(room, user.getId());
    }

    public PortfolioGameView allocate(User user, String code, Map<String, Integer> weights) {
        Room room = engine.require(code);
        engine.allocate(room, user.getId(), weights);
        return engine.view(room, user.getId());
    }

    public PortfolioGameView unready(User user, String code) {
        Room room = engine.require(code);
        engine.unready(room, user.getId());
        return engine.view(room, user.getId());
    }

    public PortfolioGameView start(User user, String code) {
        Room room = engine.require(code);
        engine.start(room, user.getId());
        return engine.view(room, user.getId());
    }

    public PortfolioGameView next(User user, String code) {
        Room room = engine.require(code);
        engine.next(room, user.getId());
        return engine.view(room, user.getId());
    }

    public void cancel(User user, String code) {
        engine.cancel(engine.require(code), user.getId());
    }

    @Scheduled(fixedRateString = "${portfolio-game.cleanup-ms:60000}")
    public void evictIdle() {
        engine.evictIdle();
    }

    /** Baja de una vez los cierres de todos los ETF, para que crear una sala no espere al proveedor. */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (!alpaca.configured()) {
            return;
        }
        CompletableFuture.runAsync(() -> symbols().forEach(symbol -> {
            try {
                closes(symbol);
            } catch (RuntimeException e) {
                log.warn("No se pudieron precargar los cierres de {}: {}", symbol, e.getMessage());
            }
        }));
    }

    private Stage stage(PortfolioPeriod period) {
        Map<PortfolioSector, Double> returns = new EnumMap<>(PortfolioSector.class);
        for (PortfolioSector sector : PortfolioSector.values()) {
            returns.put(sector, change(sector.symbol(), period));
        }
        return new Stage(period, returns, change(PortfolioSector.BENCHMARK, period));
    }

    /** Retorno en % entre el ultimo cierre en o antes de cada borde del semestre. */
    private double change(String symbol, PortfolioPeriod period) {
        NavigableMap<LocalDate, Double> closes = closes(symbol);
        Map.Entry<LocalDate, Double> start = closes.floorEntry(period.from());
        Map.Entry<LocalDate, Double> end = closes.floorEntry(period.to());
        if (start == null || end == null || !end.getKey().isAfter(start.getKey())) {
            throw new MarketDataUnavailableException("No pudimos cargar los precios del juego. Intenta de nuevo.");
        }
        return (end.getValue() / start.getValue() - 1) * 100;
    }

    private NavigableMap<LocalDate, Double> closes(String symbol) {
        return closesBySymbol.computeIfAbsent(symbol, key -> {
            NavigableMap<LocalDate, Double> closes = new TreeMap<>();
            for (ProviderBar bar : market.dailyBars(key, DATA_FROM, DATA_TO)) {
                closes.put(bar.date(), bar.close().doubleValue());
            }
            if (closes.isEmpty()) {
                throw new MarketDataUnavailableException("No pudimos cargar los precios del juego. Intenta de nuevo.");
            }
            return closes;
        });
    }

    private static List<String> symbols() {
        return Stream.concat(Stream.of(PortfolioSector.values()).map(PortfolioSector::symbol),
                Stream.of(PortfolioSector.BENCHMARK)).toList();
    }

}
