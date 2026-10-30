package com.checkout.backend.assistant.classifier;

import java.text.Normalizer;
import java.util.Locale;

/** Para comparar palabras: sin mayusculas, sin tildes y sin signos. "Pollería D'Carlos" es "polleria d carlos". */
final class Texts {

    private Texts() {
    }

    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9]+", " ")
                .strip();
    }

    /** Si la frase aparece como palabras completas: "bar" esta en "fui al bar", no en "barato". */
    static boolean containsWords(String normalizedText, String normalizedPhrase) {
        return !normalizedPhrase.isEmpty() && (" " + normalizedText + " ").contains(" " + normalizedPhrase + " ");
    }

}
