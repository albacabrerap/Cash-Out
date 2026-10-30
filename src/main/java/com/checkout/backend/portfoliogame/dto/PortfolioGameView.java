package com.checkout.backend.portfoliogame.dto;

import java.util.List;
import java.util.Map;

/**
 * Lo que ve una persona de la sala en un instante; va completo por SSE.
 *
 * @param phase  LOBBY, RUNNING, FINISHED o CANCELLED
 * @param step   etapas ya reveladas, de 0 a totalSteps
 * @param stages todas las etapas; las que faltan van sin datos
 * @param me     el jugador que mira, o null si no esta inscrito
 */
public record PortfolioGameView(
        String code,
        String phase,
        boolean host,
        boolean joined,
        int maxPlayers,
        int startValue,
        int step,
        int totalSteps,
        List<Sector> sectors,
        List<Stage> stages,
        List<Player> ranking,
        Player me,
        Result benchmark
) {

    public record Sector(String key, String name, String symbol, String kind, String description) {
    }

    /**
     * @param period  "primer semestre de 2022"; null hasta revelarla
     * @param returns retorno de cada sector en la etapa, en %; null hasta revelarla
     */
    public record Stage(int number, boolean revealed, String period, String title, String lesson,
                        Map<String, Double> returns, Double benchmarkReturn) {
    }

    /**
     * @param weights el reparto en %; el de los demas solo se ve cuando empieza la simulacion
     * @param result  null mientras se arman los portafolios
     */
    public record Player(long id, int rank, String name, boolean ready, Map<String, Integer> weights, Result result,
                         boolean me) {
    }

    /**
     * @param lastPercent  el retorno de la ultima etapa revelada
     * @param risk         desviacion de los retornos por etapa; null con menos de dos etapas
     * @param score        rendimiento promedio por unidad de riesgo; null con menos de dos etapas
     */
    public record Result(double value, double returnPercent, double lastPercent, Double risk, Double score) {
    }

}
