package com.checkout.backend.savings.income.service;

import com.checkout.backend.exceptions.ResourceNotFoundException;
import com.checkout.backend.savings.income.dto.IncomeRequest;
import com.checkout.backend.savings.income.dto.IncomeResponse;
import com.checkout.backend.savings.income.model.Income;
import com.checkout.backend.savings.income.repository.IncomeRepository;
import com.checkout.backend.savings.income.repository.IncomeSpecifications;
import com.checkout.backend.savings.service.SavingsService;
import com.checkout.backend.user.model.User;
import com.checkout.backend.web.DateRange;
import com.checkout.backend.web.PageResponse;
import com.checkout.backend.web.SearchTerm;
import com.checkout.backend.web.SortWhitelist;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.modelmapper.ModelMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingresos del usuario.
 *
 * Registrar un ingreso mueve el saldo de ahorro. Sin eso currentBalance nunca
 * cambiaria y el modulo entero seria un cuaderno de notas: el saldo es la suma
 * de lo que entro menos lo que salio, y este es el lado que suma.
 */
@Service
public class IncomeService {

    /** Columnas por las que el listado se puede ordenar con ?sort=campo,dir. */
    private static final List<String> SORTABLE = List.of("date", "amount", "source");

    /** El orden de siempre: el ingreso mas reciente primero. */
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "date");

    private final IncomeRepository incomeRepository;
    private final SavingsService savingsService;
    private final ModelMapper mapper;

    public IncomeService(IncomeRepository incomeRepository,
                         SavingsService savingsService,
                         ModelMapper mapper) {
        this.incomeRepository = incomeRepository;
        this.savingsService = savingsService;
        this.mapper = mapper;
    }

    /**
     * Registra un ingreso y lo suma al saldo.
     *
     * Las dos operaciones van en la misma transaccion: si el saldo no se puede
     * actualizar, el ingreso tampoco queda guardado. Un ingreso registrado que
     * no movio el saldo dejaria las dos cifras en desacuerdo sin que nadie se
     * entere.
     */
    @Transactional
    public IncomeResponse create(User user, IncomeRequest request) {
        Income income = Income.builder()
                .user(user)
                .amount(request.getAmount())
                .source(request.getSource())
                .date(request.getDate())
                .description(request.getDescription())
                .build();

        Income saved = incomeRepository.save(income);
        savingsService.credit(user, saved.getAmount());
        return mapper.map(saved, IncomeResponse.class);
    }

    /**
     * Ingresos del usuario, con rango de fechas y busqueda opcionales.
     *
     * Sin orden explicito van del mas reciente al mas antiguo, como siempre. El
     * rango se pide completo y ordenado; las reglas viven en DateRange.
     */
    @Transactional(readOnly = true)
    public PageResponse<IncomeResponse> list(User user, LocalDate from, LocalDate to,
                                             String search, Pageable pageable) {
        DateRange.validate(from, to);

        Specification<Income> spec = IncomeSpecifications.ownedBy(user.getId());
        if (from != null) {
            spec = spec.and(IncomeSpecifications.dateBetween(from, to));
        }
        String pattern = SearchTerm.toLikePattern(search);
        if (pattern != null) {
            spec = spec.and(IncomeSpecifications.sourceOrDescriptionMatches(pattern));
        }

        Pageable page = SortWhitelist.restrict(pageable, SORTABLE, DEFAULT_SORT);
        Page<Income> incomes = incomeRepository.findAll(spec, page);
        return PageResponse.of(incomes.map(income -> mapper.map(income, IncomeResponse.class)));
    }

    @Transactional(readOnly = true)
    public IncomeResponse get(User user, Long id) {
        return mapper.map(findOwned(user, id), IncomeResponse.class);
    }

    /**
     * Corrige un ingreso ya registrado.
     *
     * Existe porque sin el la unica forma de arreglar un importe mal escrito era
     * borrar y volver a crear, y el borrado puede estar bloqueado si el dinero ya
     * se comprometio en una meta. El usuario que escribia 5000 en vez de 500 se
     * quedaba sin salida.
     *
     * El saldo se ajusta por la diferencia, no se deshace y se vuelve a aplicar:
     * asi corregir 5000 a 5100 no exige tener 5000 libres en un instante
     * intermedio. Cuando la correccion baja el importe se cobra la diferencia con
     * debit, que valida contra el disponible, de modo que no se puede corregir a
     * la baja un dinero que ya esta apartado en una meta.
     */
    @Transactional
    public IncomeResponse update(User user, Long id, IncomeRequest request) {
        Income income = findOwned(user, id);

        BigDecimal difference = request.getAmount().subtract(income.getAmount());
        if (difference.signum() > 0) {
            savingsService.credit(user, difference);
        } else if (difference.signum() < 0) {
            savingsService.debit(user, difference.abs());
        }

        income.setAmount(request.getAmount());
        income.setSource(request.getSource());
        income.setDate(request.getDate());
        income.setDescription(request.getDescription());

        return mapper.map(income, IncomeResponse.class);
    }

    /**
     * Borra el ingreso y deshace su efecto en el saldo.
     *
     * Puede fallar con 400, y es correcto que falle: si el dinero de ese ingreso
     * ya esta comprometido en una meta, quitarlo dejaria la meta sin respaldo.
     * El usuario tiene que liberar la meta primero, o corregir el ingreso con
     * update en vez de borrarlo.
     */
    @Transactional
    public void delete(User user, Long id) {
        Income income = findOwned(user, id);
        savingsService.debit(user, income.getAmount());
        incomeRepository.delete(income);
    }

    /**
     * Busca el ingreso exigiendo que sea del usuario.
     *
     * Un ingreso de otro usuario da 404 y no 403 a proposito: un 403 confirmaria
     * que ese id existe, y eso ya es informacion sobre datos ajenos.
     */
    private Income findOwned(User user, Long id) {
        return incomeRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Ingreso", id));
    }

}
