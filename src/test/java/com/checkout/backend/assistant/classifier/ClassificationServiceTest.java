package com.checkout.backend.assistant.classifier;

import com.checkout.backend.assistant.classifier.Classification.Kind;
import com.checkout.backend.assistant.classifier.Classification.Source;
import com.checkout.backend.savings.expense.model.ExpenseCategory;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClassificationServiceTest {

    private static final ClassificationInput TAXI = new ClassificationInput(1, "Taxi", null, null, null);
    private final KeywordClassifier keywords = new KeywordClassifier();

    @Test
    @DisplayName("Se queda con el primer eslabon que responde")
    void firstAnswerWins() {
        MovementClassifier rule = input -> Optional.of(
                new Classification(Kind.EXPENSE, ExpenseCategory.LEISURE, 1.0, List.of(), Source.USER_RULE));
        ClassificationService service = new ClassificationService(List.of(rule, keywords), keywords);

        assertThat(service.classify(TAXI).source()).isEqualTo(Source.USER_RULE);
    }

    @Test
    @DisplayName("Un eslabon que falla, como Jev sin conexion, no rompe nada: responde el siguiente")
    void failingClassifierIsSkipped() {
        MovementClassifier broken = input -> {
            throw new IllegalStateException("sin conexion");
        };
        MovementClassifier silent = input -> Optional.empty();
        ClassificationService service = new ClassificationService(List.of(broken, silent, keywords), keywords);

        Classification result = service.classify(TAXI);

        assertThat(result.source()).isEqualTo(Source.KEYWORDS);
        assertThat(result.category()).isEqualTo(ExpenseCategory.TRANSPORT);
    }

    @Test
    @DisplayName("Aunque la cadena no responda, siempre hay una propuesta")
    void alwaysAnswers() {
        ClassificationService service = new ClassificationService(List.of(), keywords);

        assertThat(service.classify(TAXI).category()).isEqualTo(ExpenseCategory.TRANSPORT);
    }

}
