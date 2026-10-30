package com.checkout.backend.assistant.classifier;

import com.checkout.backend.savings.expense.model.ExpenseCategory;
import java.util.List;

/**
 * La propuesta para la tarjeta de confirmacion; quien decide es el usuario.
 *
 * @param category     null en un ingreso: los ingresos no tienen categoria
 * @param confidence   de 0 a 1. Alta: la categoria viene marcada; baja: se ofrecen las alternativas
 * @param alternatives otras categorias posibles, de la mas a la menos probable
 */
public record Classification(Kind kind, ExpenseCategory category, double confidence, List<Option> alternatives,
                             Source source) {

    public enum Kind { INCOME, EXPENSE }

    public enum Source { USER_RULE, JEV, KEYWORDS }

    public record Option(ExpenseCategory category, double probability) {
    }

}
