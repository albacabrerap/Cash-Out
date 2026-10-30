package com.checkout.backend.portfoliogame.service;

import com.checkout.backend.exceptions.ForbiddenException;
import com.checkout.backend.exceptions.InvalidRequestException;
import com.checkout.backend.exceptions.ResourceNotFoundException;
import com.checkout.backend.game.RoomViewers;
import com.checkout.backend.portfoliogame.dto.PortfolioGameView;
import com.checkout.backend.portfoliogame.model.PortfolioPeriod;
import com.checkout.backend.portfoliogame.model.PortfolioSector;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Las salas de "Arma tu portafolio", en memoria. Cada jugador reparte el 100 %
 * entre los sectores una sola vez; despues el anfitrion revela etapa por etapa
 * y el portafolio se rebalancea a ese reparto al empezar cada una.
 *
 * Gana el mejor rendimiento ajustado por riesgo: el retorno promedio por etapa
 * dividido por su desviacion. Se juegan puntos, no fichas.
 */
@Component
public class PortfolioGameEngine {

    public static final int START_VALUE = 10_000;
    public static final int MAX_PLAYERS = 40;
    private static final int WEIGHT_STEP = 10;
    /** Con una sola etapa no hay riesgo que medir: se ordena por valor. */
    private static final int STEPS_FOR_RISK = 2;
    /** Una sala sin movimiento en este tiempo se cierra: nadie la esta jugando. */
    private static final Duration IDLE_LIMIT = Duration.ofHours(2);
    private static final List<PortfolioGameView.Sector> SECTORS = Arrays.stream(PortfolioSector.values())
            .map(sector -> new PortfolioGameView.Sector(sector.key(), sector.label(), sector.symbol(), sector.kind(),
                    sector.description()))
            .toList();

    enum Phase { LOBBY, RUNNING, FINISHED, CANCELLED }

    /** Retornos reales de una etapa, en %. */
    record Stage(PortfolioPeriod period, Map<PortfolioSector, Double> returns, double benchmark) {
    }

    record Metrics(double value, double returnPercent, double last, Double risk, Double score) {
    }

    static final class Player {
        final long id;
        final long userId;
        final String name;
        Map<PortfolioSector, Integer> weights = new EnumMap<>(PortfolioSector.class);
        boolean ready;

        Player(long id, long userId, String name) {
            this.id = id;
            this.userId = userId;
            this.name = name;
        }
    }

    static final class Room {
        final String code;
        final long hostId;
        final List<Stage> stages;
        final Map<Long, Player> players = new LinkedHashMap<>();
        final RoomViewers viewers = new RoomViewers();
        Phase phase = Phase.LOBBY;
        int step;
        long nextId = 1;
        Instant touchedAt;

        Room(String code, long hostId, List<Stage> stages) {
            this.code = code;
            this.hostId = hostId;
            this.stages = stages;
        }
    }

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Clock clock;

    public PortfolioGameEngine(Clock clock) {
        this.clock = clock;
    }

    boolean exists(String code) {
        return rooms.containsKey(code);
    }

    Room open(String code, long hostId, List<Stage> stages) {
        Room room = new Room(code, hostId, List.copyOf(stages));
        room.touchedAt = clock.instant();
        rooms.put(code, room);
        return room;
    }

    Room require(String code) {
        Room room = code == null ? null : rooms.get(code.strip().toUpperCase(Locale.ROOT));
        if (room == null) {
            throw new ResourceNotFoundException("No hay una sala activa con el codigo " + code + ".");
        }
        return room;
    }

    /** Solo antes de empezar: el portafolio se arma una vez. Entrar dos veces no hace nada. */
    void join(Room room, long userId, String name) {
        synchronized (room) {
            if (room.players.containsKey(userId)) {
                return;
            }
            if (room.phase != Phase.LOBBY) {
                throw new InvalidRequestException("La simulacion ya empezo; puedes seguirla en la pantalla.");
            }
            if (room.players.size() >= MAX_PLAYERS) {
                throw new InvalidRequestException("La sala esta llena.");
            }
            room.players.put(userId, new Player(room.nextId++, userId, name));
            touch(room);
        }
    }

    /** Guarda el reparto y deja al jugador listo. */
    void allocate(Room room, long userId, Map<String, Integer> weights) {
        Map<PortfolioSector, Integer> parsed = parse(weights);
        synchronized (room) {
            Player player = requireEditable(room, userId);
            player.weights = parsed;
            player.ready = true;
            touch(room);
        }
    }

    /** Vuelve a editar el reparto antes de que empiece. */
    void unready(Room room, long userId) {
        synchronized (room) {
            requireEditable(room, userId).ready = false;
            touch(room);
        }
    }

    /** Quien no termino su portafolio queda fuera y puede mirar desde la pantalla. */
    void start(Room room, long userId) {
        synchronized (room) {
            requireHost(room, userId);
            if (room.phase != Phase.LOBBY) {
                throw new InvalidRequestException("La simulacion ya empezo.");
            }
            if (room.players.values().stream().noneMatch(player -> player.ready)) {
                throw new InvalidRequestException("Hace falta al menos un portafolio listo para empezar.");
            }
            room.players.values().removeIf(player -> !player.ready);
            room.phase = Phase.RUNNING;
            room.step = 1;
            touch(room);
        }
    }

    void next(Room room, long userId) {
        synchronized (room) {
            requireHost(room, userId);
            if (room.phase != Phase.RUNNING) {
                throw new InvalidRequestException("La simulacion no esta en curso.");
            }
            room.step++;
            if (room.step >= room.stages.size()) {
                room.phase = Phase.FINISHED;
            }
            touch(room);
        }
    }

    void cancel(Room room, long userId) {
        synchronized (room) {
            requireHost(room, userId);
            close(room);
        }
    }

    void evictIdle() {
        Instant idleSince = clock.instant().minus(IDLE_LIMIT);
        for (Room room : rooms.values()) {
            synchronized (room) {
                if (room.touchedAt.isBefore(idleSince)) {
                    close(room);
                }
            }
        }
    }

    SseEmitter subscribe(Room room, long viewerId) {
        synchronized (room) {
            return room.viewers.subscribe(viewerId, id -> buildView(room, id));
        }
    }

    PortfolioGameView view(Room room, long viewerId) {
        synchronized (room) {
            return buildView(room, viewerId);
        }
    }

    /** Cada peso entre 0 y 100 en pasos de 10, y entre todos el 100 %. */
    private static Map<PortfolioSector, Integer> parse(Map<String, Integer> weights) {
        Map<PortfolioSector, Integer> parsed = new EnumMap<>(PortfolioSector.class);
        int total = 0;
        for (Map.Entry<String, Integer> entry : weights.entrySet()) {
            PortfolioSector sector = PortfolioSector.byKey(entry.getKey())
                    .orElseThrow(() -> new InvalidRequestException("No existe el sector " + entry.getKey() + "."));
            int weight = entry.getValue() == null ? 0 : entry.getValue();
            if (weight < 0 || weight > 100 || weight % WEIGHT_STEP != 0) {
                throw new InvalidRequestException("Cada sector va de 0 a 100 %, en pasos de 10.");
            }
            if (weight > 0) {
                parsed.put(sector, weight);
            }
            total += weight;
        }
        if (total != 100) {
            throw new InvalidRequestException("El portafolio tiene que sumar 100 %.");
        }
        return parsed;
    }

    private static Player requireEditable(Room room, long userId) {
        if (room.phase != Phase.LOBBY) {
            throw new InvalidRequestException("La simulacion ya empezo; el portafolio no se puede cambiar.");
        }
        Player player = room.players.get(userId);
        if (player == null) {
            throw new InvalidRequestException("No estas inscrito en esta sala.");
        }
        return player;
    }

    private void close(Room room) {
        if (room.phase != Phase.FINISHED) {
            room.phase = Phase.CANCELLED;
            broadcast(room);
        }
        room.viewers.close();
        rooms.remove(room.code);
    }

    private void touch(Room room) {
        room.touchedAt = clock.instant();
        broadcast(room);
    }

    private void broadcast(Room room) {
        room.viewers.broadcast(id -> buildView(room, id));
    }

    /** Valor, retorno y riesgo de un reparto despues de las etapas reveladas. */
    static Metrics metrics(List<Stage> stages, int step, Map<PortfolioSector, Integer> weights) {
        double[] returns = stages.subList(0, step).stream()
                .mapToDouble(stage -> weights.entrySet().stream()
                        .mapToDouble(entry -> entry.getValue() / 100.0 * stage.returns().get(entry.getKey()))
                        .sum())
                .toArray();
        return metrics(returns);
    }

    private static Metrics metrics(double[] returns) {
        double value = START_VALUE;
        for (double r : returns) {
            value *= 1 + r / 100;
        }
        double last = returns.length == 0 ? 0 : returns[returns.length - 1];
        if (returns.length < STEPS_FOR_RISK) {
            return new Metrics(value, (value / START_VALUE - 1) * 100, last, null, null);
        }
        double mean = Arrays.stream(returns).average().orElse(0);
        double variance = Arrays.stream(returns).map(r -> (r - mean) * (r - mean)).sum() / (returns.length - 1);
        double risk = Math.sqrt(variance);
        double score = risk > 1e-9 ? mean / risk : mean;
        return new Metrics(value, (value / START_VALUE - 1) * 100, last, risk, score);
    }

    private PortfolioGameView buildView(Room room, long viewerId) {
        boolean simulating = room.phase == Phase.RUNNING || room.phase == Phase.FINISHED;
        List<Player> players = new ArrayList<>(room.players.values());
        Map<Player, Metrics> results = new LinkedHashMap<>();
        if (simulating) {
            players.forEach(player -> results.put(player, metrics(room.stages, room.step, player.weights)));
            Comparator<Player> byValue = Comparator.comparingDouble(player -> results.get(player).value());
            Comparator<Player> order = room.step < STEPS_FOR_RISK
                    ? byValue.reversed()
                    : Comparator.comparingDouble((Player player) -> results.get(player).score()).reversed()
                            .thenComparing(byValue.reversed());
            players.sort(order);
        }

        List<PortfolioGameView.Player> ranking = new ArrayList<>(players.size());
        PortfolioGameView.Player me = null;
        for (int i = 0; i < players.size(); i++) {
            Player player = players.get(i);
            boolean mine = player.userId == viewerId;
            PortfolioGameView.Player row = new PortfolioGameView.Player(player.id, i + 1, player.name, player.ready,
                    mine || simulating ? keyed(player.weights) : null,
                    simulating ? result(results.get(player)) : null, mine);
            ranking.add(row);
            if (mine) {
                me = row;
            }
        }

        double[] benchmark = room.stages.subList(0, room.step).stream().mapToDouble(Stage::benchmark).toArray();
        return new PortfolioGameView(room.code, room.phase.name(), room.hostId == viewerId, me != null, MAX_PLAYERS,
                START_VALUE, room.step, room.stages.size(), SECTORS, stages(room), ranking, me,
                simulating ? result(metrics(benchmark)) : null);
    }

    private static List<PortfolioGameView.Stage> stages(Room room) {
        List<PortfolioGameView.Stage> stages = new ArrayList<>(room.stages.size());
        for (int i = 0; i < room.stages.size(); i++) {
            Stage stage = room.stages.get(i);
            if (i >= room.step) {
                stages.add(new PortfolioGameView.Stage(i + 1, false, null, null, null, null, null));
                continue;
            }
            Map<String, Double> returns = new LinkedHashMap<>();
            stage.returns().forEach((sector, value) -> returns.put(sector.key(), round(value, 1)));
            PortfolioPeriod period = stage.period();
            stages.add(new PortfolioGameView.Stage(i + 1, true, period.label(), period.title(), period.lesson(), returns,
                    round(stage.benchmark(), 1)));
        }
        return stages;
    }

    private static Map<String, Integer> keyed(Map<PortfolioSector, Integer> weights) {
        Map<String, Integer> keyed = new LinkedHashMap<>();
        weights.forEach((sector, weight) -> keyed.put(sector.key(), weight));
        return keyed;
    }

    private static PortfolioGameView.Result result(Metrics metrics) {
        return new PortfolioGameView.Result(round(metrics.value(), 2), round(metrics.returnPercent(), 2),
                round(metrics.last(), 2), metrics.risk() == null ? null : round(metrics.risk(), 2),
                metrics.score() == null ? null : round(metrics.score(), 3));
    }

    private static double round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }

    private static void requireHost(Room room, long userId) {
        if (room.hostId != userId) {
            throw new ForbiddenException("Solo el anfitrion de la sala puede hacer esto.");
        }
    }

}
