package com.checkout.backend.savings.movement.service;

import com.checkout.backend.exceptions.InvalidRequestException;
import com.checkout.backend.savings.dto.MonthlyFlowsResponse;
import com.checkout.backend.savings.dto.MonthlyFlowsResponse.MonthlyFlow;
import com.checkout.backend.savings.dto.MovementResponse;
import com.checkout.backend.savings.movement.repository.MovementQueryRepository;
import com.checkout.backend.savings.movement.repository.MovementQueryRepository.MonthTotals;
import com.checkout.backend.user.model.User;
import com.checkout.backend.web.DateRange;
import com.checkout.backend.web.PageResponse;
import com.checkout.backend.web.SearchTerm;
import com.checkout.backend.web.SortWhitelist;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Listado unificado de ingresos y gastos.
 *
 * Las reglas de los filtros son las mismas que en /incomes y /expenses, y salen
 * de las mismas clases: un rango incompleto, una busqueda demasiado larga o un
 * orden desconocido dan el mismo 400 con el mismo mensaje en los tres listados.
 */
@Service
public class MovementService {

    private static final List<String> SORTABLE = List.of("date", "amount");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "date");

    /** Diez anios: suficiente para cualquier historial, y acota el tamano de la respuesta. */
    static final int MAX_MONTHS = 120;

    private final MovementQueryRepository repository;
    private final Clock clock;

    public MovementService(MovementQueryRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<MovementResponse> list(User user, LocalDate from, LocalDate to,
                                               String search, Pageable pageable) {
        DateRange.validate(from, to);
        String pattern = SearchTerm.toLikePattern(search);
        Pageable page = SortWhitelist.restrict(pageable, SORTABLE, DEFAULT_SORT);
        return PageResponse.of(repository.findPage(user.getId(), from, to, pattern, page));
    }

    /**
     * Ingresos y gastos por mes, hasta el mes en curso (en la zona del negocio).
     *
     * @param months cuantos meses, contando el actual; null para todo el historial
     *               desde el primer movimiento (como mucho MAX_MONTHS)
     */
    @Transactional(readOnly = true)
    public MonthlyFlowsResponse monthly(User user, Integer months) {
        if (months != null && (months < 1 || months > MAX_MONTHS)) {
            throw new InvalidRequestException("'months' tiene que estar entre 1 y " + MAX_MONTHS + ".");
        }
        YearMonth current = YearMonth.now(clock);
        YearMonth oldest = current.minusMonths(MAX_MONTHS - 1);
        YearMonth start = months != null
                ? current.minusMonths(months - 1)
                : repository.firstMovementDate(user.getId()).map(YearMonth::from).orElse(current);
        if (start.isBefore(oldest)) {
            start = oldest;
        }

        Map<YearMonth, MonthTotals> totals =
                repository.sumByMonth(user.getId(), start.atDay(1), current.atEndOfMonth());

        List<MonthlyFlow> series = new ArrayList<>();
        BigDecimal totalIncome = BigDecimal.ZERO;
        BigDecimal totalExpense = BigDecimal.ZERO;
        for (YearMonth month = start; !month.isAfter(current); month = month.plusMonths(1)) {
            MonthTotals t = totals.getOrDefault(month, MonthTotals.ZERO);
            series.add(new MonthlyFlow(month.toString(), t.income(), t.expense(), t.income().subtract(t.expense())));
            totalIncome = totalIncome.add(t.income());
            totalExpense = totalExpense.add(t.expense());
        }
        return new MonthlyFlowsResponse(start.toString(), current.toString(), series,
                totalIncome, totalExpense, totalIncome.subtract(totalExpense));
    }

}
