package com.checkout.backend.updown.dto;

import java.util.List;

/**
 * Lo que ve una persona de la sala en un instante; va completo por SSE.
 *
 * @param phase    LOBBY, VOTING, REVEAL, FINISHED o CANCELLED
 * @param question la ronda en juego; null en el lobby y al terminar
 * @param history  las rondas ya reveladas, de la primera a la ultima
 * @param me       el jugador que mira, o null si no esta inscrito
 */
public record UpDownView(
        String code,
        String phase,
        boolean host,
        boolean joined,
        int maxPlayers,
        int startPoints,
        int minBet,
        int round,
        int totalRounds,
        int secondsLeft,
        int votingSeconds,
        Question question,
        Tally tally,
        List<Player> ranking,
        Player me,
        List<Reveal> history
) {

    /**
     * Precios normalizados a 100 en el dia de la noticia, para que el nivel no
     * delate el activo.
     *
     * @param future los 21 cierres siguientes; null hasta revelar
     * @param reveal null hasta revelar
     */
    public record Question(String sector, String period, String context, List<Double> past, List<Double> future,
                           Reveal reveal) {
    }

    /** @param date "2022-06-15" */
    public record Reveal(String name, String date, String outcome, double changePercent, String lesson) {
    }

    public record Tally(int answered, List<Option> options) {
    }

    public record Option(String choice, int votes, int averageBet) {
    }

    /**
     * @param id     el del jugador en la sala: dos jugadores pueden llamarse igual
     * @param choice lo que voto en la ronda; solo se ve el propio hasta revelar
     * @param delta  lo que gano o perdio en la ultima ronda revelada
     */
    public record Player(long id, int rank, String name, int points, int hits, boolean voted, String choice, int bet,
                         int delta, boolean me) {
    }

}
