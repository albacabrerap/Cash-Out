package com.checkout.backend.assistant.dto;

/**
 * @param kind    INCOME o EXPENSE
 * @param id      el del gasto o ingreso creado
 * @param learned true si se guardo una regla para la proxima vez
 */
public record ConfirmDraftResponse(String kind, long id, boolean learned) {
}
