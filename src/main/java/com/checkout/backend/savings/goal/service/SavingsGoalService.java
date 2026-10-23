package com.checkout.backend.savings.goal.service;

import com.checkout.backend.exceptions.DuplicateResourceException;
import com.checkout.backend.exceptions.InvalidRequestException;
import com.checkout.backend.exceptions.ResourceNotFoundException;
import com.checkout.backend.savings.goal.dto.SavingsGoalRequest;
import com.checkout.backend.savings.goal.dto.SavingsGoalResponse;
import com.checkout.backend.savings.goal.model.GoalStatus;
import com.checkout.backend.savings.goal.model.SavingsGoal;
import com.checkout.backend.savings.goal.repository.SavingsGoalRepository;
import com.checkout.backend.user.model.User;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Metas de ahorro.
 *
 * Sobre el estado vencido hay una decision que conviene explicar. Una meta pasa
 * a EXPIRED cuando su fecha limite quedo atras sin haberse cumplido, y eso
 * ocurre por el paso del tiempo, no por una accion del usuario: no hay ninguna
 * peticion que se pueda aprovechar para escribir el cambio.
 *
 * La salida facil seria actualizar la fila durante el GET, pero un GET que
 * escribe deja de ser seguro en el sentido de HTTP: un reintento del cliente, o
 * un prefetch del navegador, pasan a tener efectos. Asi que el estado vencido se
 * calcula al construir la respuesta y se persiste solo cuando el usuario intenta
 * operar sobre la meta, que ya es un camino de escritura. El cliente ve EXPIRED
 * de inmediato y la fila se corrige en cuanto alguien la toca.
 *
 * Lo correcto a futuro es una tarea programada que barra las metas vencidas; el
 * executor asincrono que necesita es el issue #11.
 */
@Service
public class SavingsGoalService {

    private final SavingsGoalRepository goalRepository;
    private final ModelMapper mapper;
    private final Clock clock;

    public SavingsGoalService(SavingsGoalRepository goalRepository, ModelMapper mapper, Clock clock) {
        this.goalRepository = goalRepository;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * Crea una meta.
     *
     * El nombre se exige unico por usuario. No es un constraint de la base sino
     * una regla de producto: dos metas llamadas "Viaje" son indistinguibles en
     * la lista y el usuario no sabria a cual esta aportando.
     */
    @Transactional
    public SavingsGoalResponse create(User user, SavingsGoalRequest request) {
        if (goalRepository.existsByUserIdAndNameIgnoreCase(user.getId(), request.getName())) {
            throw new DuplicateResourceException(
                    "Ya tienes una meta llamada '" + request.getName() + "'.");
        }

        SavingsGoal goal = SavingsGoal.builder()
                .user(user)
                .name(request.getName())
                .targetAmount(request.getTargetAmount())
                .accumulatedAmount(BigDecimal.ZERO)
                .deadline(request.getDeadline())
                .status(GoalStatus.IN_PROGRESS)
                .build();

        return toResponse(goalRepository.save(goal));
    }

    @Transactional(readOnly = true)
    public List<SavingsGoalResponse> list(User user) {
        return goalRepository.findByUserIdOrderByDeadlineAsc(user.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public SavingsGoalResponse get(User user, Long id) {
        return toResponse(findOwned(user, id));
    }

    /**
     * Cambia nombre, monto objetivo o fecha limite.
     *
     * El objetivo no puede quedar por debajo de lo ya aportado: eso dejaria una
     * meta con mas dinero del que pide, un progreso mayor al 100% y ninguna
     * forma clara de decidir si esta cumplida.
     */
    @Transactional
    public SavingsGoalResponse update(User user, Long id, SavingsGoalRequest request) {
        SavingsGoal goal = findOwned(user, id);
        applyExpiration(goal);

        if (goal.getStatus() != GoalStatus.IN_PROGRESS) {
            throw new InvalidRequestException(
                    "Solo se puede editar una meta en progreso. Esta esta "
                            + goal.getStatus() + ".");
        }
        if (request.getTargetAmount().compareTo(goal.getAccumulatedAmount()) < 0) {
            throw new InvalidRequestException(
                    "El objetivo no puede ser menor a lo ya aportado ("
                            + goal.getAccumulatedAmount() + ").");
        }
        if (!goal.getName().equalsIgnoreCase(request.getName())
                && goalRepository.existsByUserIdAndNameIgnoreCase(user.getId(), request.getName())) {
            throw new DuplicateResourceException(
                    "Ya tienes una meta llamada '" + request.getName() + "'.");
        }

        goal.setName(request.getName());
        goal.setTargetAmount(request.getTargetAmount());
        goal.setDeadline(request.getDeadline());
        return toResponse(goal);
    }

    /**
     * Borra la meta.
     *
     * Con ella desaparecen sus aportes, por el orphanRemoval de la entidad, y lo
     * que tenia acumulado deja de estar comprometido: el dinero no se mueve, el
     * saldo disponible sube porque baja el comprometido.
     */
    @Transactional
    public void delete(User user, Long id) {
        goalRepository.delete(findOwned(user, id));
    }

    /**
     * Busca la meta exigiendo que sea del usuario. Publico porque el servicio de
     * aportes necesita la entidad, no su DTO.
     */
    @Transactional(readOnly = true)
    public SavingsGoal findOwned(User user, Long id) {
        return goalRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Meta de ahorro", id));
    }

    /**
     * Marca la meta como vencida si corresponde.
     *
     * Es publico porque el servicio de aportes, que vive en otro paquete,
     * necesita aplicarlo antes de validar. Solo debe llamarse desde un camino de
     * escritura y dentro de una transaccion: ver el javadoc de la clase.
     */
    public void applyExpiration(SavingsGoal goal) {
        if (isExpired(goal)) {
            goal.setStatus(GoalStatus.EXPIRED);
        }
    }

    private boolean isExpired(SavingsGoal goal) {
        return goal.getStatus() == GoalStatus.IN_PROGRESS
                && goal.getDeadline().isBefore(LocalDate.now(clock));
    }

    /**
     * Arma la respuesta con los dos campos que el mapper no puede llenar: el
     * porcentaje de avance, que no existe en la entidad, y el estado vencido,
     * que depende de la fecha de hoy.
     */
    private SavingsGoalResponse toResponse(SavingsGoal goal) {
        SavingsGoalResponse response = mapper.map(goal, SavingsGoalResponse.class);
        response.setProgressPercent(progressPercent(goal));
        if (isExpired(goal)) {
            response.setStatus(GoalStatus.EXPIRED);
        }
        return response;
    }

    /**
     * Avance en porcentaje, con dos decimales.
     *
     * El objetivo nunca es cero porque el DTO exige un minimo de 0.01, pero la
     * division se protege igual: un cambio en esa validacion no deberia poder
     * convertirse en una ArithmeticException en produccion.
     */
    private static BigDecimal progressPercent(SavingsGoal goal) {
        BigDecimal target = goal.getTargetAmount();
        if (target == null || target.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return goal.getAccumulatedAmount()
                .multiply(BigDecimal.valueOf(100))
                .divide(target, 2, RoundingMode.HALF_UP);
    }

}
