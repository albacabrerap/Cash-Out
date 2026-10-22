package com.checkout.backend.savings.expense.service;

import com.checkout.backend.exceptions.ResourceNotFoundException;
import com.checkout.backend.savings.expense.dto.ExpenseRequest;
import com.checkout.backend.savings.expense.dto.ExpenseResponse;
import com.checkout.backend.savings.expense.model.Expense;
import com.checkout.backend.savings.expense.model.ExpenseCategory;
import com.checkout.backend.savings.expense.repository.ExpenseRepository;
import com.checkout.backend.savings.expense.repository.ExpenseSpecifications;
import com.checkout.backend.savings.service.SavingsService;
import com.checkout.backend.user.model.User;
import com.checkout.backend.web.DateRange;
import com.checkout.backend.web.PageResponse;
import com.checkout.backend.web.SearchTerm;
import com.checkout.backend.web.SortWhitelist;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gastos del usuario. Es el lado que resta del saldo de ahorro.
 */
@Service
public class ExpenseService {

    /** Columnas por las que el listado se puede ordenar con ?sort=campo,dir. */
    private static final List<String> SORTABLE = List.of("date", "amount", "category");

    /** El orden de siempre: el gasto mas reciente primero. */
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "date");

    private final ExpenseRepository expenseRepository;
    private final SavingsService savingsService;
    private final ModelMapper mapper;

    public ExpenseService(ExpenseRepository expenseRepository,
                          SavingsService savingsService,
                          ModelMapper mapper) {
        this.expenseRepository = expenseRepository;
        this.savingsService = savingsService;
        this.mapper = mapper;
    }

    /**
     * Registra un gasto y lo descuenta del saldo disponible.
     *
     * El descuento va primero: si no hay saldo, debit lanza 400 y el gasto no
     * llega a guardarse. Al reves quedaria registrado un gasto que el saldo no
     * puede sostener.
     */
    @Transactional
    public ExpenseResponse create(User user, ExpenseRequest request) {
        savingsService.debit(user, request.getAmount());

        Expense expense = Expense.builder()
                .user(user)
                .category(request.getCategory())
                .amount(request.getAmount())
                .date(request.getDate())
                .description(request.getDescription())
                .build();

        return mapper.map(expenseRepository.save(expense), ExpenseResponse.class);
    }

    /**
     * Gastos del usuario, con filtros opcionales que se combinan entre si.
     *
     * Rango de fechas, categoria y busqueda pueden ir juntos o por separado, y
     * todos conviven con la paginacion y el orden. Antes categoria y fechas eran
     * excluyentes —un 400 si se mandaban ambos— porque habia un metodo de
     * repositorio por combinacion; con Specification ese limite desaparece.
     *
     * Lo que no cambia: el rango se pide completo y ordenado, por la razon que
     * da IncomeService, y sin orden explicito el listado va de la fecha mas
     * reciente a la mas antigua.
     */
    @Transactional(readOnly = true)
    public PageResponse<ExpenseResponse> list(User user, LocalDate from, LocalDate to,
                                              ExpenseCategory category, String search,
                                              Pageable pageable) {
        DateRange.validate(from, to);

        Specification<Expense> spec = ExpenseSpecifications.ownedBy(user.getId());
        if (from != null) {
            spec = spec.and(ExpenseSpecifications.dateBetween(from, to));
        }
        if (category != null) {
            spec = spec.and(ExpenseSpecifications.inCategory(category));
        }
        String pattern = SearchTerm.toLikePattern(search);
        if (pattern != null) {
            spec = spec.and(ExpenseSpecifications.descriptionMatches(pattern));
        }

        Pageable page = SortWhitelist.restrict(pageable, SORTABLE, DEFAULT_SORT);
        Page<Expense> expenses = expenseRepository.findAll(spec, page);
        return PageResponse.of(expenses.map(e -> mapper.map(e, ExpenseResponse.class)));
    }

    @Transactional(readOnly = true)
    public ExpenseResponse get(User user, Long id) {
        return mapper.map(findOwned(user, id), ExpenseResponse.class);
    }

    /**
     * Corrige un gasto ya registrado.
     *
     * Igual que en los ingresos, el saldo se ajusta por la diferencia. Aqui el
     * signo va al reves: subir el importe de un gasto cobra la diferencia y por
     * tanto valida contra el disponible, y bajarlo la devuelve.
     */
    @Transactional
    public ExpenseResponse update(User user, Long id, ExpenseRequest request) {
        Expense expense = findOwned(user, id);

        BigDecimal difference = request.getAmount().subtract(expense.getAmount());
        if (difference.signum() > 0) {
            savingsService.debit(user, difference);
        } else if (difference.signum() < 0) {
            savingsService.refund(user, difference.abs());
        }

        expense.setCategory(request.getCategory());
        expense.setAmount(request.getAmount());
        expense.setDate(request.getDate());
        expense.setDescription(request.getDescription());

        return mapper.map(expense, ExpenseResponse.class);
    }

    /**
     * Borra el gasto y devuelve el dinero al saldo.
     */
    @Transactional
    public void delete(User user, Long id) {
        Expense expense = findOwned(user, id);
        savingsService.refund(user, expense.getAmount());
        expenseRepository.delete(expense);
    }

    private Expense findOwned(User user, Long id) {
        return expenseRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Gasto", id));
    }

}
