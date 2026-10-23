package com.checkout.backend.savings.service;

import com.checkout.backend.exceptions.InvalidRequestException;
import com.checkout.backend.savings.dto.SavingsResponse;
import com.checkout.backend.savings.goal.repository.SavingsGoalRepository;
import com.checkout.backend.savings.model.Savings;
import com.checkout.backend.savings.repository.SavingsRepository;
import com.checkout.backend.user.model.User;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El saldo de ahorro del usuario y las dos cifras que se derivan de el.
 *
 * El modelo que sostiene esta clase quedo fijado en la auditoria del dominio: la
 * meta de ahorro es un sobre virtual sobre el mismo dinero de savings, no una
 * bolsa aparte. De ahi salen los tres numeros que ve el cliente:
 *
 * <ul>
 *   <li>currentBalance: todo el dinero que el usuario tiene ahorrado</li>
 *   <li>committedAmount: la parte ya comprometida en metas vivas</li>
 *   <li>availableBalance: currentBalance - committedAmount, lo que puede gastar
 *       o comprometer en una meta nueva</li>
 * </ul>
 *
 * Tratarlas como bolsas separadas seria contar el mismo dinero dos veces, que es
 * exactamente el defecto que esa auditoria corrigio.
 */
@Service
public class SavingsService {

    private final SavingsRepository savingsRepository;
    private final SavingsGoalRepository goalRepository;
    private final ModelMapper mapper;
    private final Clock clock;

    public SavingsService(SavingsRepository savingsRepository,
                          SavingsGoalRepository goalRepository,
                          ModelMapper mapper,
                          Clock clock) {
        this.savingsRepository = savingsRepository;
        this.goalRepository = goalRepository;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * El registro de ahorro del usuario, creandolo en cero si es la primera vez.
     *
     * Se crea aqui y no al registrarse por dos razones: el registro de usuario
     * es de otro modulo, y un usuario que nunca abrio la seccion de ahorro no
     * necesita la fila. El UNIQUE sobre user_id impide que dos peticiones
     * simultaneas dejen dos filas.
     */
    @Transactional
    public Savings getOrCreate(User user) {
        return savingsRepository.findByUserId(user.getId())
                .orElseGet(() -> savingsRepository.save(
                        Savings.builder()
                                .user(user)
                                .currentBalance(BigDecimal.ZERO)
                                .build()));
    }

    /**
     * Resumen del ahorro con las tres cifras ya calculadas.
     */
    @Transactional
    public SavingsResponse getSummary(User user) {
        Savings savings = getOrCreate(user);
        BigDecimal committed = committedAmount(user);

        SavingsResponse response = mapper.map(savings, SavingsResponse.class);
        // El mapper deja estos dos en null a proposito: no existen en la
        // entidad. Esta documentado en el javadoc de MapperConfig.
        response.setCommittedAmount(committed);
        response.setAvailableBalance(savings.getCurrentBalance().subtract(committed));
        return response;
    }

    /**
     * Lo comprometido en metas que siguen vivas: en curso y sin vencer.
     *
     * El de una meta cumplida deja de estar comprometido y vuelve al disponible:
     * la meta se cumplio, y gastar ese dinero en su objetivo es un gasto aparte.
     * El de una vencida tambien vuelve al disponible.
     */
    @Transactional(readOnly = true)
    public BigDecimal committedAmount(User user) {
        return goalRepository.sumCommittedByUserId(user.getId(), LocalDate.now(clock));
    }

    /**
     * Lo que el usuario puede gastar o comprometer ahora mismo.
     *
     * Es una lectura informativa, para mostrar. Quien vaya a <em>decidir</em>
     * con este numero —gastar o comprometer— debe usar
     * {@link #availableBalanceForUpdate}, que ademas serializa.
     */
    @Transactional
    public BigDecimal availableBalance(User user) {
        return getOrCreate(user).getCurrentBalance().subtract(committedAmount(user));
    }

    /**
     * El registro de ahorro tomado en exclusiva hasta el fin de la transaccion.
     *
     * Si la fila todavia no existe se crea: el INSERT ya es exclusivo por el
     * UNIQUE sobre user_id, asi que no hace falta bloquear algo que nadie mas
     * puede duplicar.
     */
    @Transactional
    public Savings lockForUpdate(User user) {
        return savingsRepository.findByUserIdForUpdate(user.getId())
                .orElseGet(() -> savingsRepository.save(
                        Savings.builder()
                                .user(user)
                                .currentBalance(BigDecimal.ZERO)
                                .build()));
    }

    /**
     * El disponible calculado sobre la fila ya bloqueada.
     *
     * Este es el que hay que usar antes de gastar o de comprometer dinero en una
     * meta. Entre el calculo y la escritura nadie mas puede meterse, porque el
     * bloqueo de la fila de ahorro se mantiene hasta que la transaccion
     * termina. Es la unica forma de que el disponible no se pueda repartir dos
     * veces entre dos operaciones que escriben en filas distintas.
     */
    @Transactional
    public BigDecimal availableBalanceForUpdate(User user) {
        return lockForUpdate(user).getCurrentBalance().subtract(committedAmount(user));
    }

    /**
     * Suma al saldo. Lo usa el alta de un ingreso.
     */
    @Transactional
    public void credit(User user, BigDecimal amount) {
        Savings savings = getOrCreate(user);
        savings.setCurrentBalance(savings.getCurrentBalance().add(amount));
    }

    /**
     * Resta del saldo, sin permitir tocar lo comprometido en metas.
     *
     * Rechaza contra el disponible y no contra el saldo total. Permitir gastar
     * dinero ya comprometido dejaria metas respaldadas por un saldo que no
     * existe, y el sobre virtual perderia todo sentido.
     */
    @Transactional
    public void debit(User user, BigDecimal amount) {
        // Bloqueo la fila antes de mirar el disponible: un gasto y un aporte a
        // una meta compiten por el mismo dinero aunque escriban en tablas
        // distintas, y esta fila es el punto donde se encuentran.
        Savings savings = lockForUpdate(user);
        BigDecimal available = savings.getCurrentBalance().subtract(committedAmount(user));

        if (amount.compareTo(available) > 0) {
            throw new InvalidRequestException(
                    "El monto supera tu saldo disponible de " + available
                            + ". El dinero comprometido en metas no se puede gastar.");
        }
        savings.setCurrentBalance(savings.getCurrentBalance().subtract(amount));
    }

    /**
     * Devuelve al saldo un monto ya descontado. Lo usa la baja de un gasto.
     */
    @Transactional
    public void refund(User user, BigDecimal amount) {
        credit(user, amount);
    }

}
