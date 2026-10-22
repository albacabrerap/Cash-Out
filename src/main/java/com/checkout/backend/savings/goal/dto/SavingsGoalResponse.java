package com.checkout.backend.savings.goal.dto;

import com.checkout.backend.savings.goal.model.GoalStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * progressPercent is computed in the service, not stored.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SavingsGoalResponse {

    private Long id;

    private String name;

    private BigDecimal targetAmount;

    private BigDecimal accumulatedAmount;

    private BigDecimal progressPercent;

    private LocalDate deadline;

    private GoalStatus status;

    private LocalDateTime completedAt;

    /** Junto con deadline dice que parte del plazo ya paso: la web compara eso con el avance. */
    private LocalDateTime createdAt;

}
