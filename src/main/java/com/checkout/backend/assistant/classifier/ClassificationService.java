package com.checkout.backend.assistant.classifier;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Recorre la cadena en orden (reglas del usuario, Jev si esta configurado,
 * palabras clave) y se queda con la primera respuesta. Si un eslabon falla,
 * como Jev sin conexion, se pasa al siguiente: clasificar nunca rompe el registro.
 */
@Service
public class ClassificationService {

    private static final Logger log = LoggerFactory.getLogger(ClassificationService.class);

    private final List<MovementClassifier> chain;
    private final KeywordClassifier fallback;

    /** Spring entrega la lista ordenada por @Order. */
    public ClassificationService(List<MovementClassifier> chain, KeywordClassifier fallback) {
        this.chain = chain;
        this.fallback = fallback;
    }

    public Classification classify(ClassificationInput input) {
        for (MovementClassifier classifier : chain) {
            try {
                Optional<Classification> result = classifier.classify(input);
                if (result.isPresent()) {
                    return result.get();
                }
            } catch (RuntimeException e) {
                log.warn("El clasificador {} fallo; se pasa al siguiente: {}", classifier.getClass().getSimpleName(),
                        e.getMessage());
            }
        }
        return fallback.classify(input).orElseThrow();
    }

}
