package com.checkout.backend.updown.service;

import com.checkout.backend.exceptions.ForbiddenException;
import com.checkout.backend.exceptions.InvalidRequestException;
import com.checkout.backend.exceptions.ResourceNotFoundException;
import com.checkout.backend.game.RoomViewers;
import com.checkout.backend.updown.dto.UpDownView;
import com.checkout.backend.updown.model.UpDownChoice;
import com.checkout.backend.updown.model.UpDownScenario;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Las salas de "¿Sube o baja?", en memoria: rondas, votos, puntos y quien mira.
 *
 * Todo cambio de una sala pasa con el candado de esa sala, asi el reloj, un voto
 * y el anfitrion no se pisan. Los puntos son del juego: no se cobran ni se
 * pagan fichas, y por eso no hay nada que guardar en la base.
 */
@Component
public class UpDownEngine {

    public static final int START_POINTS = 1000;
    public static final int MIN_BET = 10;
    public static final int MAX_PLAYERS = 40;
    static final int VOTING_SECONDS = 20;
    /** Una sala sin movimiento en este tiempo se cierra: nadie la esta jugando. */
    private static final Duration IDLE_LIMIT = Duration.ofHours(2);
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("MMMM 'de' yyyy", Locale.of("es"));

    enum Phase { LOBBY, VOTING, REVEAL, FINISHED, CANCELLED }

    record Round(UpDownScenario scenario, double[] past, double[] future) {

        double changePercent() {
            return (future[future.length - 1] / past[past.length - 1] - 1) * 100;
        }
    }

    static final class Player {
        final long id;
        final long userId;
        final String name;
        int points = START_POINTS;
        int hits;
        UpDownChoice choice;
        int bet;
        int delta;

        Player(long id, long userId, String name) {
            this.id = id;
            this.userId = userId;
            this.name = name;
        }
    }

    static final class Room {
        final String code;
        final long hostId;
        final List<Round> rounds;
        final Map<Long, Player> players = new LinkedHashMap<>();
        final RoomViewers viewers = new RoomViewers();
        Phase phase = Phase.LOBBY;
        int round;
        int secondsLeft;
        Instant touchedAt;

        Room(String code, long hostId, List<Round> rounds) {
            this.code = code;
            this.hostId = hostId;
            this.rounds = rounds;
        }
    }

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Clock clock;

    public UpDownEngine(Clock clock) {
        this.clock = clock;
    }

    boolean exists(String code) {
        return rooms.containsKey(code);
    }

    Room open(String code, long hostId, List<Round> rounds) {
        Room room = new Room(code, hostId, List.copyOf(rounds));
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

    /** Se puede entrar hasta la ultima ronda; quien llega tarde empieza con los mismos puntos. Entrar dos veces no hace nada. */
    void join(Room room, long userId, String name) {
        synchronized (room) {
            if (room.players.containsKey(userId)) {
                return;
            }
            if (room.phase == Phase.FINISHED || room.phase == Phase.CANCELLED) {
                throw new InvalidRequestException("El juego ya termino.");
            }
            if (room.players.size() >= MAX_PLAYERS) {
                throw new InvalidRequestException("La sala esta llena.");
            }
            room.players.put(userId, new Player(room.players.size() + 1, userId, name));
            touch(room);
        }
    }

    void start(Room room, long userId) {
        synchronized (room) {
            requireHost(room, userId);
            if (room.phase != Phase.LOBBY) {
                throw new InvalidRequestException("El juego ya empezo.");
            }
            if (room.players.isEmpty()) {
                throw new InvalidRequestException("Hace falta al menos un jugador para empezar.");
            }
            beginRound(room, 0);
        }
    }

    void vote(Room room, long userId, UpDownChoice choice, int bet) {
        synchronized (room) {
            if (room.phase != Phase.VOTING) {
                throw new InvalidRequestException("La votacion de esta ronda ya cerro.");
            }
            Player player = room.players.get(userId);
            if (player == null) {
                throw new InvalidRequestException("No estas inscrito en esta sala.");
            }
            if (player.choice != null) {
                throw new InvalidRequestException("Ya votaste en esta ronda.");
            }
            if (bet > player.points || bet < Math.min(MIN_BET, player.points)) {
                throw new InvalidRequestException("La apuesta va de " + Math.min(MIN_BET, player.points) + " a "
                        + player.points + " puntos.");
            }
            player.choice = choice;
            player.bet = bet;
            if (room.players.values().stream().allMatch(p -> p.choice != null)) {
                settle(room);
            } else {
                touch(room);
            }
        }
    }

    /** El anfitrion cierra la votacion antes de tiempo. */
    void reveal(Room room, long userId) {
        synchronized (room) {
            requireHost(room, userId);
            if (room.phase != Phase.VOTING) {
                throw new InvalidRequestException("No hay una votacion abierta.");
            }
            settle(room);
        }
    }

    void next(Room room, long userId) {
        synchronized (room) {
            requireHost(room, userId);
            if (room.phase != Phase.REVEAL) {
                throw new InvalidRequestException("Primero hay que revelar la ronda.");
            }
            if (room.round + 1 >= room.rounds.size()) {
                room.phase = Phase.FINISHED;
                touch(room);
            } else {
                beginRound(room, room.round + 1);
            }
        }
    }

    void cancel(Room room, long userId) {
        synchronized (room) {
            requireHost(room, userId);
            close(room);
        }
    }

    /** Un segundo del reloj de todas las salas: descuenta la votacion y cierra las salas abandonadas. */
    void tick() {
        Instant idleSince = clock.instant().minus(IDLE_LIMIT);
        for (Room room : rooms.values()) {
            synchronized (room) {
                if (room.phase == Phase.VOTING) {
                    room.secondsLeft--;
                    if (room.secondsLeft <= 0) {
                        settle(room);
                    } else {
                        broadcast(room);
                    }
                } else if (room.touchedAt.isBefore(idleSince)) {
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

    UpDownView view(Room room, long viewerId) {
        synchronized (room) {
            return buildView(room, viewerId);
        }
    }

    private void beginRound(Room room, int index) {
        room.round = index;
        room.secondsLeft = VOTING_SECONDS;
        room.players.values().forEach(player -> {
            player.choice = null;
            player.bet = 0;
            player.delta = 0;
        });
        room.phase = Phase.VOTING;
        touch(room);
    }

    private void settle(Room room) {
        UpDownChoice outcome = UpDownChoice.of(room.rounds.get(room.round).changePercent());
        for (Player player : room.players.values()) {
            if (player.choice == null) {
                continue;
            }
            boolean hit = player.choice == outcome;
            player.delta = hit ? player.bet : -player.bet;
            player.points += player.delta;
            if (hit) {
                player.hits++;
            }
        }
        room.secondsLeft = 0;
        room.phase = Phase.REVEAL;
        touch(room);
    }

    /** Una sala terminada solo se descarta; las demas se avisan como canceladas. */
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

    private UpDownView buildView(Room room, long viewerId) {
        boolean revealed = room.phase == Phase.REVEAL || room.phase == Phase.FINISHED;
        List<Player> standings = room.players.values().stream()
                .sorted(Comparator.comparingInt((Player p) -> p.points).reversed())
                .toList();
        List<UpDownView.Player> ranking = new ArrayList<>(standings.size());
        UpDownView.Player me = null;
        for (int i = 0; i < standings.size(); i++) {
            Player player = standings.get(i);
            boolean mine = player.userId == viewerId;
            boolean showVote = mine || revealed;
            UpDownView.Player row = new UpDownView.Player(player.id, i + 1, player.name, player.points, player.hits,
                    player.choice != null, showVote && player.choice != null ? player.choice.name() : null,
                    showVote ? player.bet : 0, revealed ? player.delta : 0, mine);
            ranking.add(row);
            if (mine) {
                me = row;
            }
        }

        int played = room.phase == Phase.LOBBY ? 0 : revealed ? room.round + 1 : room.round;
        List<UpDownView.Reveal> history = room.rounds.subList(0, played).stream().map(UpDownEngine::revealOf).toList();
        UpDownView.Question question = room.phase == Phase.VOTING || room.phase == Phase.REVEAL
                ? question(room.rounds.get(room.round), room.phase == Phase.REVEAL)
                : null;

        return new UpDownView(room.code, room.phase.name(), room.hostId == viewerId, me != null, MAX_PLAYERS,
                START_POINTS, MIN_BET, room.round + 1, room.rounds.size(), room.secondsLeft, VOTING_SECONDS,
                question, tally(room), ranking, me, history);
    }

    private static UpDownView.Question question(Round round, boolean revealed) {
        UpDownScenario scenario = round.scenario();
        return new UpDownView.Question(scenario.sector(), PERIOD.format(scenario.cutoff()), scenario.context(),
                rounded(round.past()), revealed ? rounded(round.future()) : null, revealed ? revealOf(round) : null);
    }

    private static UpDownView.Reveal revealOf(Round round) {
        double change = round.changePercent();
        return new UpDownView.Reveal(round.scenario().name(), round.scenario().cutoff().toString(),
                UpDownChoice.of(change).name(), Math.round(change * 10) / 10.0, round.scenario().lesson());
    }

    private static UpDownView.Tally tally(Room room) {
        List<Player> voters = room.players.values().stream().filter(p -> p.choice != null).toList();
        List<UpDownView.Option> options = Arrays.stream(UpDownChoice.values()).map(choice -> {
            List<Player> picked = voters.stream().filter(p -> p.choice == choice).toList();
            int average = (int) Math.round(picked.stream().mapToInt(p -> p.bet).average().orElse(0));
            return new UpDownView.Option(choice.name(), picked.size(), average);
        }).toList();
        return new UpDownView.Tally(voters.size(), options);
    }

    private static List<Double> rounded(double[] prices) {
        return Arrays.stream(prices).map(price -> Math.round(price * 100) / 100.0).boxed().toList();
    }

    private void broadcast(Room room) {
        room.viewers.broadcast(id -> buildView(room, id));
    }

    private static void requireHost(Room room, long userId) {
        if (room.hostId != userId) {
            throw new ForbiddenException("Solo el anfitrion de la sala puede hacer esto.");
        }
    }

}
