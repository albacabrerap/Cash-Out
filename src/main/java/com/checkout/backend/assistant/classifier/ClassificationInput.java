package com.checkout.backend.assistant.classifier;

import com.checkout.backend.assistant.parser.ParsedMovement.Hint;

/**
 * Lo que se sabe de un movimiento antes de clasificarlo.
 *
 * @param text         la descripcion que dio el parser, o lo leido de la boleta
 * @param hint         la pista del verbo ("me pagaron", "gaste"); null si no hay
 * @param merchantName la razon social de la boleta; null si vino de un mensaje
 * @param ruc          el RUC de la boleta; null si vino de un mensaje
 */
public record ClassificationInput(long userId, String text, Hint hint, String merchantName, String ruc) {

    /** Todo lo que se puede leer, junto y normalizado, para buscar palabras. */
    String searchable() {
        return Texts.normalize((text == null ? "" : text) + " " + (merchantName == null ? "" : merchantName));
    }

}
