package com.checkout.backend.race.service;

import com.checkout.backend.exceptions.ForbiddenException;
import com.checkout.backend.exceptions.InvalidRequestException;
import com.checkout.backend.exceptions.ResourceNotFoundException;
import com.checkout.backend.investment_portfolio.trade_order.model.OrderSide;
import com.checkout.backend.race.dto.RaceView;
import com.checkout.backend.race.model.RaceScenario;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Las carreras en curso, en memoria: precios, jugadores, fase y quien mira.
 *
 * Todo cambio de una carrera pasa con el candado de esa carrera, asi un paso,
 * una compra y una inscripcion no se pisan. Aqui no se toca la base: lo que hay
 * que guardar lo hace RaceService.
 */
@Component
public class RaceEngine {

    static final double START_CAPITAL = 10_000;
    static final int TICKS_PER_SECOND = 4;
    static final int COUNTDOWN_TICKS = 3 * TICKS_PER_SECOND;
    public static final int MAX_PLAYERS = 30;
    private static final int FEED_SIZE = 6;
    private static final long EMITTER_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();
    /** Una sala terminada sigue abierta un rato para ver los resultados. */
    private static final Duration KEEP_FINISHED = Duration.ofMinutes(30);

    enum Phase { LOBBY, COUNTDOWN, RUNNING, FINISHED, CANCELLED }

    static final class Racer {
        final long playerId;
        final long userId;
        final String name;
        double cash = START_CAPITAL;
        double units;
        BigDecimal prize = BigDecimal.ZERO;

        Racer(long playerId, long userId, String name) {
            this.playerId = playerId;
            this.userId = userId;
            this.name = name;
        }

        double value(double price) {
            return cash + units * price;
        }
    }

    record Standing(Racer racer, double value) {
    }

    static final class LiveRace {
        final long roomId;
        final String code;
        final long hostId;
        final BigDecimal fee;
        final RaceScenario scenario;
        final double[] realPrices;
        /** Normalizados a 100 al empezar: el nivel del precio no delata la moneda. */
        final double[] prices;
        final Map<Long, Racer> racers = new LinkedHashMap<>();
        final Deque<RaceView.Move> feed = new ArrayDeque<>();
        final Map<SseEmitter, Long> viewers = new ConcurrentHashMap<>();
        Phase phase = Phase.LOBBY;
        int countdown;
        int step;
        Instant openedAt;
        Instant finishedAt;

        LiveRace(long roomId, String code, long hostId, BigDecimal fee, RaceScenario scenario, double[] realPrices) {
            this.roomId = roomId;
            this.code = code;
            this.hostId = hostId;
            this.fee = fee;
            this.scenario = scenario;
            this.realPrices = realPrices;
            this.prices = Arrays.stream(realPrices).map(price -> price / realPrices[0] * 100).toArray();
        }
    }

    private final Map<String, LiveRace> races = new ConcurrentHashMap<>();
    private final Clock clock;

    public RaceEngine(Clock clock) {
        this.clock = clock;
    }

    LiveRace open(long roomId, String code, long hostId, BigDecimal fee, RaceScenario scenario, double[] realPrices) {
        LiveRace race = new LiveRace(roomId, code, hostId, fee, scenario, realPrices);
        race.openedAt = clock.instant();
        races.put(code, race);
        return race;
    }

    /** Salas que siguen esperando jugadores despues de maxAge: nadie las va a empezar. */
    List<LiveRace> staleLobbies(Duration maxAge) {
        Instant limit = clock.instant().minus(maxAge);
        return races.values().stream()
                .filter(race -> {
                    synchronized (race) {
                        return race.phase == Phase.LOBBY && race.openedAt.isBefore(limit);
                    }
                })
                .toList();
    }

    LiveRace require(String code) {
        LiveRace race = code == null ? null : races.get(code.strip().toUpperCase(Locale.ROOT));
        if (race == null) {
            throw new ResourceNotFoundException("No hay una sala activa con el codigo " + code + ".");
        }
        return race;
    }

    Collection<LiveRace> active() {
        return races.values();
    }

    boolean isRacer(LiveRace race, long userId) {
        synchronized (race) {
            return race.racers.containsKey(userId);
        }
    }

    void checkJoinable(LiveRace race) {
        synchronized (race) {
            if (race.phase != Phase.LOBBY) {
                throw new InvalidRequestException("La carrera ya empezo; no se puede entrar.");
            }
            if (race.racers.size() >= MAX_PLAYERS) {
                throw new InvalidRequestException("La sala esta llena.");
            }
        }
    }

    /** false si, mientras se cobraba la cuota, la carrera empezo o se lleno. */
    boolean addRacer(LiveRace race, long playerId, long userId, String name) {
        synchronized (race) {
            if (race.phase != Phase.LOBBY || race.racers.size() >= MAX_PLAYERS) {
                return false;
            }
            race.racers.putIfAbsent(userId, new Racer(playerId, userId, name));
            broadcast(race, true);
            return true;
        }
    }

    void start(LiveRace race, long userId) {
        synchronized (race) {
            requireHost(race, userId);
            if (race.phase != Phase.LOBBY) {
                throw new InvalidRequestException("La carrera ya empezo.");
            }
            if (race.racers.isEmpty()) {
                throw new InvalidRequestException("Hace falta al menos un jugador para empezar.");
            }
            race.phase = Phase.COUNTDOWN;
            race.countdown = COUNTDOWN_TICKS;
            broadcast(race, true);
        }
    }

    List<Racer> cancel(LiveRace race, long userId) {
        synchronized (race) {
            requireHost(race, userId);
            return close(race);
        }
    }

    /** Cierra una sala que no empezo y devuelve a quienes hay que reembolsar. */
    List<Racer> close(LiveRace race) {
        synchronized (race) {
            if (race.phase != Phase.LOBBY) {
                throw new InvalidRequestException("Solo se puede cancelar una sala que todavia no empezo.");
            }
            race.phase = Phase.CANCELLED;
            broadcast(race, true);
            closeViewers(race);
            races.remove(race.code);
            return List.copyOf(race.racers.values());
        }
    }

    RaceView trade(LiveRace race, long userId, OrderSide side, double fraction) {
        synchronized (race) {
            if (race.phase != Phase.RUNNING) {
                throw new InvalidRequestException("Solo se puede operar mientras corre la carrera.");
            }
            Racer racer = race.racers.get(userId);
            if (racer == null) {
                throw new InvalidRequestException("No estas inscrito en esta carrera.");
            }
            double price = race.prices[race.step];
            if (side == OrderSide.BUY) {
                if (racer.cash < 0.01) {
                    throw new InvalidRequestException("No te queda efectivo para comprar.");
                }
                double spend = racer.cash * fraction;
                racer.cash -= spend;
                racer.units += spend / price;
            } else {
                if (racer.units <= 0) {
                    throw new InvalidRequestException("No tienes nada invertido para vender.");
                }
                double sold = racer.units * fraction;
                racer.units -= sold;
                racer.cash += sold * price;
            }
            race.feed.addFirst(new RaceView.Move(race.step, racer.name + " " + describe(side, fraction),
                    side == OrderSide.BUY));
            while (race.feed.size() > FEED_SIZE) {
                race.feed.removeLast();
            }
            return buildView(race, userId, false);
        }
    }

    /** Avanza un paso. Devuelve true cuando la carrera llego al final y hay que cerrarla. */
    boolean advance(LiveRace race) {
        synchronized (race) {
            if (race.phase == Phase.COUNTDOWN) {
                race.countdown--;
                if (race.countdown <= 0) {
                    race.phase = Phase.RUNNING;
                    race.step = 0;
                }
                broadcast(race, race.phase == Phase.RUNNING);
                return false;
            }
            if (race.phase == Phase.RUNNING) {
                if (race.step >= race.prices.length - 1) {
                    return true;
                }
                race.step++;
                broadcast(race, false);
            }
            return false;
        }
    }

    /** Del mejor al peor al precio actual; ante un empate gana quien entro primero. */
    List<Standing> ranking(LiveRace race) {
        synchronized (race) {
            return rank(race);
        }
    }

    void finish(LiveRace race, Map<Long, BigDecimal> prizes) {
        synchronized (race) {
            race.racers.values().forEach(racer -> racer.prize = prizes.getOrDefault(racer.playerId, BigDecimal.ZERO));
            race.phase = Phase.FINISHED;
            race.finishedAt = clock.instant();
            broadcast(race, true);
        }
    }

    void evictFinished() {
        Instant limit = clock.instant().minus(KEEP_FINISHED);
        races.values().removeIf(race -> {
            synchronized (race) {
                if (race.phase == Phase.FINISHED && race.finishedAt.isBefore(limit)) {
                    closeViewers(race);
                    return true;
                }
                return false;
            }
        });
    }

    SseEmitter subscribe(LiveRace race, long viewerId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        Runnable release = () -> race.viewers.remove(emitter);
        emitter.onCompletion(release);
        emitter.onTimeout(release);
        emitter.onError(error -> release.run());
        synchronized (race) {
            race.viewers.put(emitter, viewerId);
            send(race, emitter, viewerId, true);
        }
        return emitter;
    }

    RaceView view(LiveRace race, long viewerId) {
        synchronized (race) {
            return buildView(race, viewerId, true);
        }
    }

    private List<Standing> rank(LiveRace race) {
        double price = race.prices[race.step];
        return race.racers.values().stream()
                .map(racer -> new Standing(racer, racer.value(price)))
                .sorted(Comparator.comparingDouble(Standing::value).reversed())
                .toList();
    }

    private RaceView buildView(LiveRace race, long viewerId, boolean withPrices) {
        double price = race.prices[race.step];
        List<Standing> standings = rank(race);
        List<RaceView.Racer> ranking = new ArrayList<>(standings.size());
        RaceView.Racer me = null;
        for (int i = 0; i < standings.size(); i++) {
            Racer racer = standings.get(i).racer();
            double value = standings.get(i).value();
            RaceView.Racer row = new RaceView.Racer(racer.playerId, i + 1, racer.name, round(value, 2),
                    round((value / START_CAPITAL - 1) * 100, 2),
                    round(value > 0 ? racer.units * price / value * 100 : 0, 1),
                    round(racer.cash, 2), racer.prize, racer.userId == viewerId);
            ranking.add(row);
            if (row.me()) {
                me = row;
            }
        }
        List<Double> prices = withPrices
                ? Arrays.stream(race.prices, 0, race.step + 1).map(value -> round(value, 4)).boxed().toList()
                : null;
        RaceView.Reveal reveal = race.phase == Phase.FINISHED
                ? new RaceView.Reveal(race.scenario.productId(), race.scenario.coinName(),
                        race.scenario.start().atOffset(ZoneOffset.UTC).toLocalDate().toString(),
                        race.scenario.title(), race.scenario.description(),
                        race.realPrices[0], race.realPrices[race.realPrices.length - 1])
                : null;

        return new RaceView(race.code, race.phase.name(), race.hostId == viewerId, me != null, race.fee,
                race.fee.multiply(BigDecimal.valueOf(race.racers.size())), MAX_PLAYERS, race.step,
                race.prices.length, (int) Math.ceil(race.countdown / (double) TICKS_PER_SECOND),
                round(price, 4), prices, ranking, me, List.copyOf(race.feed), reveal);
    }

    private void broadcast(LiveRace race, boolean withPrices) {
        race.viewers.forEach((emitter, viewerId) -> send(race, emitter, viewerId, withPrices));
    }

    private void send(LiveRace race, SseEmitter emitter, long viewerId, boolean withPrices) {
        try {
            emitter.send(SseEmitter.event()
                    .name(withPrices ? "state" : "tick")
                    .data(buildView(race, viewerId, withPrices), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            race.viewers.remove(emitter);
        }
    }

    private void closeViewers(LiveRace race) {
        race.viewers.keySet().forEach(SseEmitter::complete);
        race.viewers.clear();
    }

    private static void requireHost(LiveRace race, long userId) {
        if (race.hostId != userId) {
            throw new ForbiddenException("Solo el anfitrion de la sala puede hacer esto.");
        }
    }

    private static String describe(OrderSide side, double fraction) {
        String amount = fraction >= 1 ? null : Math.round(fraction * 100) + " %";
        if (side == OrderSide.BUY) {
            return amount == null ? "compró con todo" : "compró " + amount;
        }
        return amount == null ? "vendió todo" : "vendió " + amount;
    }

    private static double round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }

}
