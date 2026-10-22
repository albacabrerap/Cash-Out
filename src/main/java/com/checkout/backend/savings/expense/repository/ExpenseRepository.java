package com.checkout.backend.savings.expense.repository;

import com.checkout.backend.savings.expense.model.Expense;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Acceso a la tabla expenses.
 *
 * El listado se arma con ExpenseSpecifications, que permite combinar filtros
 * sin un metodo por combinacion. Con el orden por defecto —fecha descendente—
 * la consulta sigue apoyandose en idx_expenses_user_date, igual que en incomes.
 */
public interface ExpenseRepository extends JpaRepository<Expense, Long>,
        JpaSpecificationExecutor<Expense> {

    Optional<Expense> findByIdAndUserId(Long id, Long userId);

}
