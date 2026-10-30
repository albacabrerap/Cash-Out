package com.checkout.backend.assistant.dto;

import com.checkout.backend.assistant.classifier.Classification.Kind;
import com.checkout.backend.savings.expense.model.ExpenseCategory;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Una tarjeta ya revisada por el usuario: lo que se guarda es lo que dice aqui,
 * no lo que se sugirio.
 *
 * @param category   obligatoria en un gasto; se ignora en un ingreso
 * @param ruc        el del comercio, si vino de una boleta: con el se aprende la regla
 * @param receiptRef la huella de la boleta, para no registrarla dos veces
 * @param learn      true si el usuario cambio el tipo o la categoria sugeridos: la proxima vez saldra sola.
 *                   Opcional: si falta, no se aprende
 */
public record ConfirmDraftRequest(
        @NotNull Kind kind,
        @NotNull @DecimalMin(value = "0.01", message = "El monto tiene que ser mayor que cero.")
        @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @NotNull @PastOrPresent(message = "La fecha no puede ser futura.") LocalDate date,
        @Size(max = 255) String description,
        ExpenseCategory category,
        @Pattern(regexp = "\\d{11}", message = "El RUC tiene 11 dígitos.") String ruc,
        @Pattern(regexp = "[0-9a-f]{64}", message = "La referencia de la boleta no es válida.") String receiptRef,
        Boolean learn
) {
}
