package com.checkout.backend.assistant.parser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Un movimiento sacado de un mensaje escrito, todavia sin categoria: la decide
 * el clasificador y la confirma el usuario.
 *
 * @param description lo que se compro o de donde vino la plata; null si el mensaje solo traia el monto
 * @param amount      null si el mensaje no lo decia: la tarjeta lo pide, no se inventa
 * @param date        la del mensaje ("ayer", "el lunes", "15/10") o la de hoy
 * @param hint        lo que sugiere el verbo ("me pagaron", "gaste"); null si no hay pista
 */
public record ParsedMovement(String description, BigDecimal amount, Currency currency, LocalDate date,
                             PaymentMethod paymentMethod, Hint hint, List<Warning> warnings) {

    public enum Currency { PEN, USD }

    public enum PaymentMethod { YAPE, PLIN, CARD, CASH, TRANSFER }

    public enum Hint { INCOME, EXPENSE }

    /** Lo que la tarjeta tiene que mostrar para que el usuario lo revise antes de guardar. */
    public enum Warning {
        MISSING_AMOUNT,
        /** La app guarda soles: hay que convertirlo. */
        FOREIGN_CURRENCY,
        /** "cena 120 entre 4": se guarda el total, no la parte de cada uno. */
        NOT_SPLIT,
        LONG_DESCRIPTION,
        HIGH_AMOUNT
    }

}
