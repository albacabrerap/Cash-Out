package com.checkout.backend.updown.service;

import com.checkout.backend.exceptions.InvalidRequestException;
import com.checkout.backend.game.GameRooms;
import com.checkout.backend.market.MarketDataUnavailableException;
import com.checkout.backend.market.alpaca.AlpacaProperties;
import com.checkout.backend.updown.dto.UpDownView;
import com.checkout.backend.updown.model.UpDownCase;
import com.checkout.backend.updown.model.UpDownChoice;
import com.checkout.backend.updown.model.UpDownScenario;
import com.checkout.backend.updown.repository.UpDownCaseRepository;
import com.checkout.backend.updown.service.UpDownEngine.Round;
import com.checkout.backend.updown.service.UpDownPrices.Series;
import com.checkout.backend.user.model.User;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * "¿Sube o baja?": cada ronda muestra un activo real hasta el dia de una
 * noticia y los jugadores apuestan puntos a lo que hizo el mes siguiente.
 * El resultado sale de los cierres reales, asi que nadie lo puede inventar.
 *
 * Las rondas salen del banco de casos activos, empezando por los que hace mas
 * tiempo no salen: asi quien juega seguido no ve siempre los mismos.
 */
@Service
public class UpDownService {

    private static final Logger log = LoggerFactory.getLogger(UpDownService.class);

    static final int ROUNDS = 8;

    private final UpDownEngine engine;
    private final UpDownPrices prices;
    private final UpDownCaseRepository cases;
    private final AlpacaProperties alpaca;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public UpDownService(UpDownEngine engine, UpDownPrices prices, UpDownCaseRepository cases, AlpacaProperties alpaca,
                         TransactionTemplate transaction, Clock clock) {
        this.engine = engine;
        this.prices = prices;
        this.cases = cases;
        this.alpaca = alpaca;
        this.transaction = transaction;
        this.clock = clock;
    }

    /** Abre una sala con 8 casos, sin repetir dia: dos noticias del mismo dia se contradirian. */
    public UpDownView create(User host) {
        List<UpDownScenario> pool = transaction.execute(status -> {
            List<UpDownCase> active = new ArrayList<>(cases.findByActiveTrue());
            // Al azar entre los empatados, y primero los que nunca salieron o salieron hace mas.
            Collections.shuffle(active, random);
            active.sort(Comparator.comparing(UpDownCase::getLastPlayedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
            return active.stream().map(UpDownScenario::of).toList();
        });
        if (pool.isEmpty()) {
            throw new InvalidRequestException("Todavia no hay casos activos para jugar. Un administrador tiene que cargarlos.");
        }

        List<Round> rounds = new ArrayList<>(ROUNDS);
        Set<LocalDate> days = new HashSet<>();
        for (UpDownScenario scenario : pool) {
            if (rounds.size() == ROUNDS || days.contains(scenario.cutoff())) {
                continue;
            }
            try {
                Series series = prices.load(scenario.symbol(), scenario.cutoff());
                rounds.add(new Round(scenario, series.past(), series.future()));
                days.add(scenario.cutoff());
            } catch (RuntimeException e) {
                log.warn("No se pudo cargar el caso {} ({} {}): {}", scenario.id(), scenario.symbol(), scenario.cutoff(),
                        e.getMessage());
            }
        }
        if (rounds.isEmpty()) {
            throw new MarketDataUnavailableException("No pudimos cargar los precios del juego. Intenta de nuevo.");
        }

        List<Long> played = rounds.stream().map(round -> round.scenario().id()).toList();
        transaction.executeWithoutResult(status -> cases.markPlayed(played, LocalDateTime.now(clock)));
        return engine.view(engine.open(GameRooms.newCode(random, engine::exists), host.getId(), rounds), host.getId());
    }

    public UpDownView view(User user, String code) {
        return engine.view(engine.require(code), user.getId());
    }

    public SseEmitter subscribe(User user, String code) {
        return engine.subscribe(engine.require(code), user.getId());
    }

    public UpDownView join(User user, String code) {
        UpDownEngine.Room room = engine.require(code);
        engine.join(room, user.getId(), GameRooms.firstName(user));
        return engine.view(room, user.getId());
    }

    public UpDownView start(User user, String code) {
        UpDownEngine.Room room = engine.require(code);
        engine.start(room, user.getId());
        return engine.view(room, user.getId());
    }

    public UpDownView vote(User user, String code, UpDownChoice choice, int bet) {
        UpDownEngine.Room room = engine.require(code);
        engine.vote(room, user.getId(), choice, bet);
        return engine.view(room, user.getId());
    }

    public UpDownView reveal(User user, String code) {
        UpDownEngine.Room room = engine.require(code);
        engine.reveal(room, user.getId());
        return engine.view(room, user.getId());
    }

    public UpDownView next(User user, String code) {
        UpDownEngine.Room room = engine.require(code);
        engine.next(room, user.getId());
        return engine.view(room, user.getId());
    }

    public void cancel(User user, String code) {
        engine.cancel(engine.require(code), user.getId());
    }

    @Scheduled(fixedRateString = "${updown.tick-ms:1000}")
    public void tick() {
        engine.tick();
    }

    /** Baja de una vez los cierres de los casos activos, para que crear una sala no espere al proveedor. */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (!alpaca.configured()) {
            return;
        }
        List<UpDownScenario> active = transaction.execute(status ->
                cases.findByActiveTrue().stream().map(UpDownScenario::of).toList());
        CompletableFuture.runAsync(() -> active.forEach(scenario -> {
            try {
                prices.load(scenario.symbol(), scenario.cutoff());
            } catch (RuntimeException e) {
                log.warn("No se pudieron precargar los cierres del caso {}: {}", scenario.id(), e.getMessage());
            }
        }));
    }

}
