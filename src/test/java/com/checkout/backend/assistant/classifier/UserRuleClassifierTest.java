package com.checkout.backend.assistant.classifier;

import com.checkout.backend.assistant.classifier.Classification.Kind;
import com.checkout.backend.assistant.classifier.Classification.Source;
import com.checkout.backend.assistant.parser.ParsedMovement.Hint;
import com.checkout.backend.savings.expense.model.ExpenseCategory;
import com.checkout.backend.support.DatabaseCleaner;
import com.checkout.backend.user.model.Role;
import com.checkout.backend.user.model.User;
import com.checkout.backend.user.repository.UserRepository;
import java.util.EnumSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Las reglas que cada usuario aprende al corregir, contra la base de los tests. */
@SpringBootTest
class UserRuleClassifierTest {

    private static final String RUC = "20100070970";

    @Autowired private UserRuleClassifier rules;
    @Autowired private ClassificationService service;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;

    private long ana;
    private long beto;

    @BeforeEach
    void setUp() {
        ana = users.save(User.builder().name("Ana").email("ana@utec.edu.pe").passwordHash("x").roles(EnumSet.of(Role.USER)).build()).getId();
        beto = users.save(User.builder().name("Beto").email("beto@utec.edu.pe").passwordHash("x").roles(EnumSet.of(Role.USER)).build()).getId();
    }

    @AfterEach
    void cleanUp() {
        DatabaseCleaner.clean(jdbc);
    }

    @Test
    @DisplayName("Corregir una boleta crea una regla por RUC que gana a las palabras clave, solo para ese usuario")
    void learnsByRuc() {
        ClassificationInput receipt = new ClassificationInput(ana, null, null, "BOTICAS Y SALUD S.A.C.", RUC);
        assertThat(service.classify(receipt).category()).isEqualTo(ExpenseCategory.HEALTH);

        assertThat(rules.learn(receipt, Kind.EXPENSE, ExpenseCategory.FOOD)).isTrue();

        Classification learned = service.classify(receipt);
        assertThat(learned.category()).isEqualTo(ExpenseCategory.FOOD);
        assertThat(learned.source()).isEqualTo(Source.USER_RULE);
        assertThat(learned.confidence()).isEqualTo(1.0);
        assertThat(service.classify(new ClassificationInput(beto, null, null, "BOTICAS Y SALUD S.A.C.", RUC)).source())
                .isEqualTo(Source.KEYWORDS);
    }

    @Test
    @DisplayName("Sin RUC aprende la descripcion corta y la reconoce dentro de otros mensajes")
    void learnsByKeyword() {
        rules.learn(new ClassificationInput(ana, "Donde Pepe", null, null, null), Kind.EXPENSE, ExpenseCategory.FOOD);

        Classification result = service.classify(new ClassificationInput(ana, "almuerzo donde pepe", null, null, null));
        assertThat(result.category()).isEqualTo(ExpenseCategory.FOOD);
        assertThat(result.source()).isEqualTo(Source.USER_RULE);
    }

    @Test
    @DisplayName("Corregir dos veces actualiza la regla; con varias reglas gana la mas especifica")
    void updatesAndPrefersTheLongest() {
        rules.learn(new ClassificationInput(ana, "Luz", null, null, null), Kind.EXPENSE, ExpenseCategory.OTHER);
        rules.learn(new ClassificationInput(ana, "Luz", null, null, null), Kind.EXPENSE, ExpenseCategory.SERVICES);
        rules.learn(new ClassificationInput(ana, "Luz del Sur", null, null, null), Kind.EXPENSE, ExpenseCategory.HEALTH);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM classification_rules", Integer.class)).isEqualTo(2);
        assertThat(service.classify(new ClassificationInput(ana, "recibo luz", null, null, null)).category())
                .isEqualTo(ExpenseCategory.SERVICES);
        assertThat(service.classify(new ClassificationInput(ana, "luz del sur octubre", null, null, null)).category())
                .isEqualTo(ExpenseCategory.HEALTH);
    }

    @Test
    @DisplayName("Un ingreso se aprende sin categoria, y una frase larga no se vuelve regla")
    void incomeAndLongTexts() {
        rules.learn(new ClassificationInput(ana, "Mi tía", Hint.INCOME, null, null), Kind.INCOME, ExpenseCategory.FOOD);
        Classification income = service.classify(new ClassificationInput(ana, "regalo de mi tia", null, null, null));
        assertThat(income.kind()).isEqualTo(Kind.INCOME);
        assertThat(income.category()).isNull();

        assertThat(rules.learn(new ClassificationInput(ana, "fui al centro con mis amigos", null, null, null),
                Kind.EXPENSE, ExpenseCategory.LEISURE)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM classification_rules", Integer.class)).isEqualTo(1);
    }

}
