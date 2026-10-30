package com.checkout.backend.assistant.classifier;

import java.util.Optional;

/**
 * Un eslabon de la cadena que decide si un movimiento es ingreso o gasto, y su
 * categoria. Va vacio cuando no se anima: el siguiente lo intenta. El ultimo,
 * el de palabras clave, siempre responde.
 */
public interface MovementClassifier {

    Optional<Classification> classify(ClassificationInput input);

}
