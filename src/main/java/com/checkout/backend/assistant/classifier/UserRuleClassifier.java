package com.checkout.backend.assistant.classifier;

import com.checkout.backend.assistant.classifier.Classification.Kind;
import com.checkout.backend.assistant.classifier.Classification.Source;
import com.checkout.backend.assistant.classifier.rule.ClassificationRule;
import com.checkout.backend.assistant.classifier.rule.ClassificationRule.MatchType;
import com.checkout.backend.assistant.classifier.rule.ClassificationRuleRepository;
import com.checkout.backend.savings.expense.model.ExpenseCategory;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * El primer eslabon: lo que el usuario ya corrigio. Gana siempre y con
 * confianza total, porque es su propia decision.
 */
@Component
@Order(0)
public class UserRuleClassifier implements MovementClassifier {

    /** Una regla por palabras es un nombre corto ("tottus", "luz del sur"), no una frase entera. */
    static final int MAX_KEYWORD_WORDS = 4;
    private static final int MAX_PATTERN = 120;

    private final ClassificationRuleRepository rules;

    public UserRuleClassifier(ClassificationRuleRepository rules) {
        this.rules = rules;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Classification> classify(ClassificationInput input) {
        if (input.ruc() != null) {
            Optional<ClassificationRule> byRuc = rules.findByUserIdAndMatchTypeAndPattern(input.userId(), MatchType.RUC,
                    input.ruc());
            if (byRuc.isPresent()) {
                return byRuc.map(UserRuleClassifier::toClassification);
            }
        }
        // Con varias reglas que encajan gana la mas especifica: "luz del sur" antes que "luz".
        String text = input.searchable();
        return rules.findByUserIdAndMatchType(input.userId(), MatchType.KEYWORD).stream()
                .filter(rule -> Texts.containsWords(text, rule.getPattern()))
                .max(Comparator.comparingInt(rule -> rule.getPattern().length()))
                .map(UserRuleClassifier::toClassification);
    }

    /**
     * Guarda lo que el usuario eligio para que la proxima vez salga solo. Usa
     * el RUC si lo hay; si no, la razon social o la descripcion, siempre que
     * sean cortas. Devuelve false si no habia de donde sacar una regla.
     */
    @Transactional
    public boolean learn(ClassificationInput input, Kind kind, ExpenseCategory category) {
        MatchType type;
        String pattern;
        if (input.ruc() != null) {
            type = MatchType.RUC;
            pattern = input.ruc();
        } else {
            type = MatchType.KEYWORD;
            pattern = Texts.normalize(input.merchantName() != null ? input.merchantName() : input.text());
            int words = pattern.isEmpty() ? 0 : pattern.split(" ").length;
            if (words == 0 || words > MAX_KEYWORD_WORDS || pattern.length() > MAX_PATTERN) {
                return false;
            }
        }
        ClassificationRule rule = rules.findByUserIdAndMatchTypeAndPattern(input.userId(), type, pattern)
                .orElseGet(ClassificationRule::new);
        rule.setUserId(input.userId());
        rule.setMatchType(type);
        rule.setPattern(pattern);
        rule.setKind(kind);
        rule.setCategory(kind == Kind.INCOME ? null : category);
        rules.save(rule);
        return true;
    }

    private static Classification toClassification(ClassificationRule rule) {
        return new Classification(rule.getKind(), rule.getCategory(), 1.0, List.of(), Source.USER_RULE);
    }

}
