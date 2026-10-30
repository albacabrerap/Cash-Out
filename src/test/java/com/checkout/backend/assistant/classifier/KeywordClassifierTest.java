package com.checkout.backend.assistant.classifier;

import com.checkout.backend.assistant.parser.ParsedMovement.Hint;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** La tabla del diccionario: texto, razon social y pista del parser contra tipo, categoria, confianza y alternativas. */
class KeywordClassifierTest {

    private final KeywordClassifier classifier = new KeywordClassifier();

    @ParameterizedTest(name = "{0} / {1} / {2}")
    @CsvSource(delimiter = ';', nullValues = "-", value = {
            "Taxi;-;-;EXPENSE TRANSPORT 0.60",
            "Chifa;-;EXPENSE;EXPENSE FOOD 0.60",
            "Pasaje en combi;-;-;EXPENSE TRANSPORT 0.60",
            // La frase larga tapa a la corta de la misma o de otra categoria.
            "Pollo a la brasa;-;-;EXPENSE FOOD 0.60",
            "agua mineral;-;-;EXPENSE FOOD 0.60",
            "Agua;-;-;EXPENSE SERVICES 0.60",
            "Luz del Sur;-;-;EXPENSE SERVICES 0.60",
            "Pensión de la UTEC;-;-;EXPENSE EDUCATION 0.60",
            "Netflix;-;-;EXPENSE LEISURE 0.60",
            "-;INKAFARMA S.A.;-;EXPENSE HEALTH 0.60",
            "-;SUPERMERCADOS PERUANOS S.A.;-;EXPENSE FOOD 0.60",
            // Empate: la tarjeta ofrece las dos.
            "Almuerzo en el cine;-;-;EXPENSE FOOD 0.30 [LEISURE 0.30]",
            "Taxi al cine y cena;-;-;EXPENSE FOOD 0.20 [TRANSPORT 0.20, LEISURE 0.20]",
            // Palabras completas: "barato" no es "bar".
            "Algo barato;-;-;EXPENSE OTHER 0.20",
            "-;-;-;EXPENSE OTHER 0.20",
            // Ingresos: sin categoria.
            "Bono;-;-;INCOME - 0.50",
            "Mi tía;-;INCOME;INCOME - 0.60",
            // La pista del verbo gana a la palabra: "gaste el sueldo en..." es un gasto.
            "Sueldo;-;EXPENSE;EXPENSE OTHER 0.20"
    })
    void classifies(String text, String merchant, Hint hint, String expected) {
        Classification result = classifier.classify(new ClassificationInput(1, text, hint, merchant, null)).orElseThrow();

        assertThat(line(result)).isEqualTo(expected);
        assertThat(result.source()).isEqualTo(Classification.Source.KEYWORDS);
    }

    private static String line(Classification result) {
        String line = result.kind() + " " + (result.category() == null ? "-" : result.category())
                + String.format(Locale.ROOT, " %.2f", result.confidence());
        if (result.alternatives().isEmpty()) {
            return line;
        }
        return line + result.alternatives().stream()
                .map(option -> option.category() + String.format(Locale.ROOT, " %.2f", option.probability()))
                .collect(Collectors.joining(", ", " [", "]"));
    }

}
