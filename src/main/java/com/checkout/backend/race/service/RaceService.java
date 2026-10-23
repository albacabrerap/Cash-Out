package com.checkout.backend.race.service;

import com.checkout.backend.exceptions.InvalidRequestException;
import com.checkout.backend.investment_portfolio.trade_order.model.OrderSide;
import com.checkout.backend.market.CryptoDataClient;
import com.checkout.backend.market.CryptoDataClient.Candle;
import com.checkout.backend.market.MarketDataUnavailableException;
import com.checkout.backend.market.coinbase.CoinbaseProperties;
import com.checkout.backend.race.dto.RaceView;
import com.checkout.backend.race.model.RacePlayer;
import com.checkout.backend.race.model.RaceRoom;
import com.checkout.backend.race.model.RaceScenario;
import com.checkout.backend.race.model.RaceStatus;
import com.checkout.backend.race.repository.RacePlayerRepository;
import com.checkout.backend.race.repository.RaceRoomRepository;
import com.checkout.backend.race.service.RaceEngine.LiveRace;
import com.checkout.backend.race.service.RaceEngine.Racer;
import com.checkout.backend.race.service.RaceEngine.Standing;
import com.checkout.backend.token_wallet.service.TokenWalletService;
import com.checkout.backend.token_wallet.tktransaction.model.TokenReason;
import com.checkout.backend.user.model.User;
import com.checkout.backend.user.repository.UserRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Carrera de trading: salas, inscripciones, cuotas y premios.
 *
 * El juego se resuelve en el servidor. El cliente solo dice "compro" o
 * "vendo", y el precio, el reloj y el ranking los pone RaceEngine; por eso, a
 * diferencia de los minijuegos de puntuacion, aqui nadie puede inventarse un
 * resultado para cobrar el pozo.
 *
 * El pozo sale de las cuotas y se reparte 50/30/20 entre los tres primeros. Con
 * menos de tres jugadores no hay carrera que premiar y se devuelve la cuota:
 * asi dos cuentas de la misma persona no pueden pasarse fichas.
 */
@Service
public class RaceService {

    private static final Logger log = LoggerFactory.getLogger(RaceService.class);

    private static final List<BigDecimal> FEES = List.of(
            BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.valueOf(20), BigDecimal.valueOf(50));
    private static final List<BigDecimal> FRACTIONS = List.of(
            new BigDecimal("0.25"), new BigDecimal("0.5"), BigDecimal.ONE);
    private static final int MIN_PLAYERS_FOR_PRIZE = 3;
    /** Una sala que nadie empieza en este tiempo se cancela y devuelve las cuotas. */
    private static final Duration LOBBY_TIMEOUT = Duration.ofHours(2);
    /** Sin 0, O, 1 ni I: se leen mal en un proyector. */
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final RaceEngine engine;
    private final RaceRoomRepository rooms;
    private final RacePlayerRepository players;
    private final UserRepository users;
    private final TokenWalletService wallet;
    private final CryptoDataClient crypto;
    private final CoinbaseProperties coinbase;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, double[]> pricesByScenario = new ConcurrentHashMap<>();

    public RaceService(RaceEngine engine, RaceRoomRepository rooms, RacePlayerRepository players,
                       UserRepository users, TokenWalletService wallet, CryptoDataClient crypto,
                       CoinbaseProperties coinbase, TransactionTemplate transaction, Clock clock) {
        this.engine = engine;
        this.rooms = rooms;
        this.players = players;
        this.users = users;
        this.wallet = wallet;
        this.crypto = crypto;
        this.coinbase = coinbase;
        this.transaction = transaction;
        this.clock = clock;
    }

    /** Abre una sala con un escenario al azar: ni el anfitrion sabe que moneda ni que dia es. */
    public RaceView create(User host, BigDecimal entryFee) {
        BigDecimal fee = FEES.stream()
                .filter(option -> option.compareTo(entryFee) == 0)
                .findFirst()
                .orElseThrow(() -> new InvalidRequestException("La cuota tiene que ser de 0, 10, 20 o 50 fichas."));
        RaceScenario scenario = RaceScenario.ALL.get(random.nextInt(RaceScenario.ALL.size()));
        double[] realPrices = pricesByScenario.computeIfAbsent(scenario.key(), key -> load(scenario));

        RaceRoom room = transaction.execute(status -> rooms.save(RaceRoom.builder()
                .code(newCode())
                .host(users.getReferenceById(host.getId()))
                .scenarioKey(scenario.key())
                .entryFee(fee)
                .build()));
        LiveRace race = engine.open(room.getId(), room.getCode(), host.getId(), fee, scenario, realPrices);
        return engine.view(race, host.getId());
    }

    public RaceView view(User user, String code) {
        return engine.view(engine.require(code), user.getId());
    }

    public SseEmitter subscribe(User user, String code) {
        return engine.subscribe(engine.require(code), user.getId());
    }

    /** Cobra la cuota y pone al jugador en la sala. Inscribirse dos veces no cobra dos veces. */
    public RaceView join(User user, String code) {
        LiveRace race = engine.require(code);
        if (engine.isRacer(race, user.getId())) {
            return engine.view(race, user.getId());
        }
        engine.checkJoinable(race);

        RacePlayer player = transaction.execute(status -> {
            RacePlayer saved = players.save(RacePlayer.builder()
                    .room(rooms.getReferenceById(race.roomId))
                    .user(users.getReferenceById(user.getId()))
                    .build());
            if (race.fee.signum() > 0) {
                // Sin fichas suficientes lanza 400 y la inscripcion se deshace con la transaccion.
                wallet.record(user, race.fee.negate(), TokenReason.RACE_ENTRY, saved.getId());
            }
            return saved;
        });

        if (!engine.addRacer(race, player.getId(), user.getId(), firstName(user))) {
            transaction.executeWithoutResult(status -> refund(user.getId(), player.getId(), race.fee));
            throw new InvalidRequestException("La carrera empezo mientras te inscribias. Te devolvimos la cuota.");
        }
        return engine.view(race, user.getId());
    }

    public RaceView start(User user, String code) {
        LiveRace race = engine.require(code);
        engine.start(race, user.getId());
        transaction.executeWithoutResult(status -> rooms.findById(race.roomId).ifPresent(room -> {
            room.setStatus(RaceStatus.RUNNING);
            room.setStartedAt(LocalDateTime.now(clock));
        }));
        return engine.view(race, user.getId());
    }

    public void cancel(User user, String code) {
        LiveRace race = engine.require(code);
        refundClosed(race, engine.cancel(race, user.getId()));
    }

    private void refundClosed(LiveRace race, List<Racer> racers) {
        transaction.executeWithoutResult(status -> {
            rooms.findById(race.roomId).ifPresent(room -> {
                room.setStatus(RaceStatus.CANCELLED);
                room.setFinishedAt(LocalDateTime.now(clock));
            });
            racers.forEach(racer -> refund(racer.userId, racer.playerId, race.fee));
        });
    }

    public RaceView trade(User user, String code, OrderSide side, BigDecimal fraction) {
        if (FRACTIONS.stream().noneMatch(option -> option.compareTo(fraction) == 0)) {
            throw new InvalidRequestException("Solo se puede operar el 25 %, el 50 % o todo.");
        }
        return engine.trade(engine.require(code), user.getId(), side, fraction.doubleValue());
    }

    /** El reloj de todas las carreras: un paso cada tick, 4 por segundo. */
    @Scheduled(fixedRateString = "${race.tick-ms:250}")
    public void tick() {
        for (LiveRace race : engine.active()) {
            if (engine.advance(race)) {
                try {
                    finish(race);
                } catch (RuntimeException e) {
                    // El proximo tick lo reintenta: el libro de fichas no paga dos veces la misma referencia.
                    log.error("No se pudo cerrar la carrera {}", race.code, e);
                }
            }
        }
        for (LiveRace race : engine.staleLobbies(LOBBY_TIMEOUT)) {
            try {
                refundClosed(race, engine.close(race));
                log.info("Sala {} cancelada: nadie la empezo en {}", race.code, LOBBY_TIMEOUT);
            } catch (RuntimeException e) {
                log.warn("No se pudo cancelar la sala abandonada {}: {}", race.code, e.getMessage());
            }
        }
        engine.evictFinished();
    }

    /** Baja de una vez los precios de todos los escenarios, para que crear una sala no espere a Coinbase. */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (!coinbase.enabled()) {
            return;
        }
        CompletableFuture.runAsync(() -> RaceScenario.ALL.forEach(scenario -> {
            try {
                pricesByScenario.computeIfAbsent(scenario.key(), key -> load(scenario));
            } catch (RuntimeException e) {
                log.warn("No se pudieron precargar los precios de {}: {}", scenario.key(), e.getMessage());
            }
        }));
    }

    /**
     * La carrera vive en memoria: si el servidor se reinicio, las salas que
     * quedaron abiertas no pueden seguir. Se cancelan y se devuelve la cuota.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void cancelAbandoned() {
        transaction.executeWithoutResult(status -> {
            for (RaceRoom room : rooms.findByStatusIn(List.of(RaceStatus.LOBBY, RaceStatus.RUNNING))) {
                room.setStatus(RaceStatus.CANCELLED);
                room.setFinishedAt(LocalDateTime.now(clock));
                for (RacePlayer player : players.findByRoomId(room.getId())) {
                    refund(player.getUser().getId(), player.getId(), room.getEntryFee());
                }
                log.warn("Sala {} cancelada al arrancar: quedo abierta en un reinicio", room.getCode());
            }
        });
    }

    void finish(LiveRace race) {
        List<Standing> standings = engine.ranking(race);
        BigDecimal pot = race.fee.multiply(BigDecimal.valueOf(standings.size()));
        boolean pays = standings.size() >= MIN_PLAYERS_FOR_PRIZE && pot.signum() > 0;

        Map<Long, BigDecimal> prizes = new HashMap<>();
        if (pays) {
            BigDecimal second = pot.multiply(new BigDecimal("0.3")).setScale(2, RoundingMode.DOWN);
            BigDecimal third = pot.multiply(new BigDecimal("0.2")).setScale(2, RoundingMode.DOWN);
            // El primero se lleva lo que sobra del redondeo: el pozo se reparte completo.
            prizes.put(standings.get(0).racer().playerId, pot.subtract(second).subtract(third));
            prizes.put(standings.get(1).racer().playerId, second);
            prizes.put(standings.get(2).racer().playerId, third);
        }

        transaction.executeWithoutResult(status -> {
            rooms.findById(race.roomId).ifPresent(room -> {
                room.setStatus(RaceStatus.FINISHED);
                room.setFinishedAt(LocalDateTime.now(clock));
            });
            for (int i = 0; i < standings.size(); i++) {
                Standing standing = standings.get(i);
                Racer racer = standing.racer();
                BigDecimal prize = prizes.getOrDefault(racer.playerId, BigDecimal.ZERO);
                int rank = i + 1;
                players.findById(racer.playerId).ifPresent(player -> {
                    player.setFinalRank(rank);
                    player.setFinalValue(BigDecimal.valueOf(standing.value()).setScale(2, RoundingMode.HALF_UP));
                    player.setPrize(prize);
                });
                if (prize.signum() > 0) {
                    wallet.record(users.getReferenceById(racer.userId), prize, TokenReason.RACE_PRIZE, racer.playerId);
                } else if (!pays) {
                    refund(racer.userId, racer.playerId, race.fee);
                }
            }
        });
        engine.finish(race, prizes);
    }

    /** Las velas del tramo; los minutos sin operaciones repiten el ultimo cierre. */
    private double[] load(RaceScenario scenario) {
        Instant end = scenario.start().plus(Duration.ofMinutes(RaceScenario.MINUTES - 1));
        List<Candle> candles = crypto.minuteCandles(scenario.productId(), scenario.start(), end);
        if (candles.isEmpty()) {
            throw new MarketDataUnavailableException("No pudimos cargar los precios de la carrera. Intenta de nuevo.");
        }
        Map<Instant, BigDecimal> closes = candles.stream()
                .collect(Collectors.toMap(Candle::time, Candle::close, (first, second) -> second));
        double[] prices = new double[RaceScenario.MINUTES];
        double last = candles.get(0).open().doubleValue();
        for (int i = 0; i < prices.length; i++) {
            BigDecimal close = closes.get(scenario.start().plus(Duration.ofMinutes(i)));
            if (close != null) {
                last = close.doubleValue();
            }
            prices[i] = last;
        }
        return prices;
    }

    private void refund(long userId, long playerId, BigDecimal fee) {
        if (fee.signum() > 0) {
            wallet.record(users.getReferenceById(userId), fee, TokenReason.RACE_REFUND, playerId);
        }
    }

    private String newCode() {
        String code;
        do {
            StringBuilder builder = new StringBuilder(6);
            for (int i = 0; i < 6; i++) {
                builder.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
            }
            code = builder.toString();
        } while (rooms.existsByCode(code));
        return code;
    }

    private static String firstName(User user) {
        String first = user.getName().strip().split("\\s+")[0];
        return first.length() <= 20 ? first : first.substring(0, 20);
    }

}
