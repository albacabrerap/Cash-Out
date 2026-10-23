package com.checkout.backend.web;

import com.checkout.backend.exceptions.InvalidRequestException;
import java.time.LocalDate;

/**
 * Las reglas del rango de fechas de un listado, en un solo sitio.
 *
 * Gastos e ingresos las aplicaban por separado con el mismo texto. Ahora que los
 * dos listados se arman igual, viven aqui para que no puedan divergir. Los
 * mensajes se conservan tal cual porque ya son parte del contrato de la API.
 *
 * El rango es opcional pero se pide completo: con un solo extremo no esta claro
 * si el otro es el inicio de los tiempos o el dia de hoy, y cada cliente
 * asumiria una cosa distinta.
 */
public final class DateRange {

    private DateRange() {
    }

    /**
     * No hace nada si no hay rango; lanza 400 si viene a medias o al reves.
     */
    public static void validate(LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return;
        }
        if (from == null || to == null) {
            throw new InvalidRequestException(
                    "Para filtrar por fecha hay que enviar 'from' y 'to', no solo uno.");
        }
        if (from.isAfter(to)) {
            throw new InvalidRequestException("'from' no puede ser posterior a 'to'.");
        }
    }

}
