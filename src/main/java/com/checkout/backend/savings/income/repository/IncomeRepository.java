package com.checkout.backend.savings.income.repository;

import com.checkout.backend.savings.income.model.Income;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Acceso a la tabla incomes.
 *
 * El listado se arma con IncomeSpecifications. El filtro por rango y el orden
 * por defecto —fecha descendente— se siguen apoyando en idx_incomes_user_date,
 * que es un indice compuesto por (user_id, date).
 */
public interface IncomeRepository extends JpaRepository<Income, Long>,
        JpaSpecificationExecutor<Income> {

    Optional<Income> findByIdAndUserId(Long id, Long userId);

}
