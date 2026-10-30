package com.checkout.backend.updown.model;

import java.time.LocalDate;

/**
 * Lo que una partida necesita de un caso, copiado al crear la sala: si un ADMIN
 * lo edita o lo borra a mitad de partida, la sala sigue con lo que tenia.
 *
 * Se muestran los 80 cierres hasta el dia de la noticia y los jugadores
 * predicen los 21 siguientes (un mes de bolsa).
 */
public record UpDownScenario(long id, String symbol, LocalDate cutoff, String sector, String name, String context,
                             String lesson) {

    public static final int PAST_DAYS = 80;
    public static final int FUTURE_DAYS = 21;

    public static UpDownScenario of(UpDownCase source) {
        return new UpDownScenario(source.getId(), source.getSymbol(), source.getCutoff(), source.getSector(),
                source.getName(), source.getContext(), source.getLesson());
    }

}
