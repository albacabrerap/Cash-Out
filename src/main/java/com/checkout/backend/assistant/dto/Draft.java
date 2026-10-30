package com.checkout.backend.assistant.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Una tarjeta para confirmar: lo que se entendio y lo que se sugiere. Nada esta
 * guardado todavia; al confirmar se crea el gasto o el ingreso.
 *
 * @param kind                INCOME o EXPENSE
 * @param amount              null si no se dijo: la tarjeta lo pide
 * @param category            null en un ingreso
 * @param confidence          de 0 a 1; baja, la tarjeta ofrece las alternativas
 * @param source              quien sugirio: USER_RULE, JEV o KEYWORDS
 * @param warnings            lo que el usuario tiene que revisar, como MISSING_AMOUNT o DUPLICATE_RECEIPT
 * @param ruc                 el RUC del comercio de la boleta (dato publico del negocio, no del comprador),
 *                            para aprender una regla al confirmar; null si vino de un mensaje
 * @param receiptRef          la huella de la boleta, para guardarla al confirmar; null si vino de un mensaje
 * @param alreadyRegisteredAt cuando se registro esta misma boleta; null si es la primera vez
 */
public record Draft(String kind, String description, BigDecimal amount, String currency, LocalDate date,
                    String paymentMethod, String category, double confidence, List<Option> alternatives,
                    String source, List<String> warnings, String ruc, String receiptRef,
                    LocalDateTime alreadyRegisteredAt) {

    public record Option(String category, double probability) {
    }

}
