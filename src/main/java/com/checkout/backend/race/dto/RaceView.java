package com.checkout.backend.race.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Lo que ve una persona de la sala en un instante. Va completo por SSE en cada
 * paso; {@code prices} solo viaja en el evento "state" (al conectarse o al
 * cambiar de fase) y en los ticks llega solo {@code price}.
 *
 * @param phase     LOBBY, COUNTDOWN, RUNNING, FINISHED o CANCELLED
 * @param price     precio actual, normalizado a 100 al empezar
 * @param countdown segundos para empezar, durante COUNTDOWN
 * @param me        el jugador que mira, o null si no esta inscrito
 * @param scenario  moneda, fecha y contexto: solo al terminar
 */
public record RaceView(
        String code,
        String phase,
        boolean host,
        boolean joined,
        BigDecimal entryFee,
        BigDecimal pot,
        int maxPlayers,
        int step,
        int totalSteps,
        int countdown,
        double price,
        List<Double> prices,
        List<Racer> ranking,
        Racer me,
        List<Move> feed,
        Reveal scenario
) {

    /** @param id el del jugador en la sala: dos jugadores pueden llamarse igual. */
    public record Racer(long id, int rank, String name, double value, double returnPercent, double investedPercent,
                        double cash, BigDecimal prize, boolean me) {
    }

    public record Move(int step, String text, boolean buy) {
    }

    public record Reveal(String productId, String coinName, String date, String title, String description,
                         double realOpen, double realClose) {
    }

}
