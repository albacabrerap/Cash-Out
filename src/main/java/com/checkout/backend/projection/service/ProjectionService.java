package com.checkout.backend.projection.service;

import com.checkout.backend.exceptions.ResourceNotFoundException;
import com.checkout.backend.projection.dto.ProjectionPreviewRequest;
import com.checkout.backend.projection.dto.ProjectionPreviewResponse;
import com.checkout.backend.projection.dto.ProjectionRequest;
import com.checkout.backend.projection.dto.ProjectionResponse;
import com.checkout.backend.projection.model.Projection;
import com.checkout.backend.projection.repository.ProjectionRepository;
import com.checkout.backend.savings.goal.model.SavingsGoal;
import com.checkout.backend.savings.goal.service.SavingsGoalService;
import com.checkout.backend.user.model.User;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proyecciones de ahorro: cuanto llega a ser un capital con aportes periodicos.
 *
 * Calcula dos escenarios sobre los mismos datos para que la diferencia entre
 * ambos sea visible, que es justo lo que la pantalla quiere ensenar: el interes
 * compuesto no se entiende con una definicion, se entiende viendo la brecha que
 * abre contra el simple a los mismos anos.
 *
 * Los periodos son meses, por el @Max(600) del modelo, que son cincuenta anos.
 * La tasa que llega es una TEA entre 0 y 1. Los dos escenarios usan la misma
 * tasa mensual, (1+TEA)^(1/12) - 1, para que lo unico que los distinga sea
 * reinvertir o no los intereses. Dividir la TEA entre doce la trataria como
 * nominal: con 10% el compuesto daria 10.47% al anio.
 */
@Service
public class ProjectionService {

    /** Precision de trabajo. Los resultados se redondean a dos decimales al final. */
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

    private static final int MONTHS_PER_YEAR = 12;

    private final ProjectionRepository projectionRepository;
    private final SavingsGoalService goalService;
    private final ModelMapper mapper;

    public ProjectionService(ProjectionRepository projectionRepository,
                             SavingsGoalService goalService,
                             ModelMapper mapper) {
        this.projectionRepository = projectionRepository;
        this.goalService = goalService;
        this.mapper = mapper;
    }

    /**
     * Calcula una proyeccion y la guarda.
     *
     * La meta asociada es opcional. Cuando viene, se resuelve con findOwned, de
     * modo que nadie pueda enlazar su proyeccion a la meta de otro usuario y
     * averiguar asi que ese id existe.
     */
    @Transactional
    public ProjectionResponse create(User user, ProjectionRequest request) {
        SavingsGoal goal = request.getSavingsGoalId() == null
                ? null
                : goalService.findOwned(user, request.getSavingsGoalId());

        Projection projection = Projection.builder()
                .user(user)
                .savingsGoal(goal)
                .name(request.getName())
                .initialCapital(request.getInitialCapital())
                .monthlyContribution(request.getMonthlyContribution())
                .annualRate(request.getAnnualRate())
                .periods(request.getPeriods())
                .build();

        projection.setSimpleFinalAmount(simpleFinalAmount(projection.getInitialCapital(),
                projection.getMonthlyContribution(), projection.getAnnualRate(), projection.getPeriods()));
        projection.setCompoundFinalAmount(compoundFinalAmount(projection.getInitialCapital(),
                projection.getMonthlyContribution(), projection.getAnnualRate(), projection.getPeriods()));

        return toResponse(projectionRepository.save(projection));
    }

    /** El mismo calculo que create, sin guardar nada. */
    public ProjectionPreviewResponse preview(ProjectionPreviewRequest request) {
        BigDecimal simple = simpleFinalAmount(request.initialCapital(), request.monthlyContribution(),
                request.annualRate(), request.periods());
        BigDecimal compound = compoundFinalAmount(request.initialCapital(), request.monthlyContribution(),
                request.annualRate(), request.periods());
        return new ProjectionPreviewResponse(
                totalContributed(request.initialCapital(), request.monthlyContribution(), request.periods()),
                simple, compound, scale(compound.subtract(simple)));
    }

    @Transactional(readOnly = true)
    public List<ProjectionResponse> list(User user) {
        return projectionRepository.findByUserIdOrderByCalculatedAtDesc(user.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectionResponse get(User user, Long id) {
        return toResponse(findOwned(user, id));
    }

    /**
     * Borra la proyeccion.
     *
     * No hay actualizacion: una proyeccion es el resultado de un calculo con
     * unos datos concretos, y cambiarle los datos no la corrige, produce otra
     * distinta. Guardar la anterior tiene valor, porque el usuario puede querer
     * comparar dos escenarios.
     */
    @Transactional
    public void delete(User user, Long id) {
        projectionRepository.delete(findOwned(user, id));
    }

    private Projection findOwned(User user, Long id) {
        return projectionRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Proyeccion", id));
    }

    /**
     * Interes simple: los intereses se calculan siempre sobre el capital y no se
     * reinvierten.
     *
     * El capital inicial acumula durante los n meses completos. Cada aporte
     * acumula solo desde el mes en que entra, asi que el primero gana intereses
     * por n-1 meses y el ultimo por ninguno.
     */
    private static BigDecimal simpleFinalAmount(BigDecimal initialCapital, BigDecimal monthlyContribution,
                                                BigDecimal annualRate, int periods) {
        BigDecimal monthlyRate = monthlyRate(annualRate);

        BigDecimal fromCapital = initialCapital
                .multiply(BigDecimal.ONE.add(monthlyRate.multiply(BigDecimal.valueOf(periods), MC)), MC);

        // Suma de los meses que acumula cada aporte: (n-1) + (n-2) + ... + 0,
        // que es n*(n-1)/2. Se usa la formula en vez de un bucle porque con 600
        // periodos la diferencia en precision y en tiempo no es despreciable.
        BigDecimal accruedMonths = BigDecimal.valueOf((long) periods * (periods - 1) / 2);

        BigDecimal fromContributions = monthlyContribution
                .multiply(BigDecimal.valueOf(periods), MC)
                .add(monthlyContribution.multiply(monthlyRate, MC)
                        .multiply(accruedMonths, MC), MC);

        return scale(fromCapital.add(fromContributions, MC));
    }

    /**
     * Interes compuesto: los intereses se reinvierten y generan intereses.
     *
     * Capital inicial por (1+i)^n, mas el valor futuro de una renta constante,
     * que es PMT * ((1+i)^n - 1) / i.
     *
     * Con tasa cero esa formula se indefine por la division, y el resultado
     * correcto es simplemente la suma de los aportes: se trata aparte en vez de
     * dejar que reviente.
     */
    private static BigDecimal compoundFinalAmount(BigDecimal initialCapital, BigDecimal monthlyContribution,
                                                  BigDecimal annualRate, int periods) {
        BigDecimal monthlyRate = monthlyRate(annualRate);

        if (monthlyRate.signum() == 0) {
            return totalContributed(initialCapital, monthlyContribution, periods);
        }

        BigDecimal growth = BigDecimal.ONE.add(monthlyRate).pow(periods, MC);

        BigDecimal fromCapital = initialCapital.multiply(growth, MC);
        BigDecimal fromContributions = monthlyContribution
                .multiply(growth.subtract(BigDecimal.ONE, MC).divide(monthlyRate, MC), MC);

        return scale(fromCapital.add(fromContributions, MC));
    }

    private static BigDecimal monthlyRate(BigDecimal annualRate) {
        return twelfthRoot(BigDecimal.ONE.add(annualRate)).subtract(BigDecimal.ONE, MC);
    }

    private static BigDecimal totalContributed(BigDecimal initialCapital, BigDecimal monthlyContribution, int periods) {
        return scale(initialCapital.add(monthlyContribution.multiply(BigDecimal.valueOf(periods), MC), MC));
    }

    /**
     * BigDecimal no trae raices de indice arbitrario. Se parte de la estimacion
     * en double, que ya tiene unas 15 cifras, y Newton duplica las cifras
     * correctas en cada paso: con dos pasos se pasa de las 20 de MC.
     */
    private static BigDecimal twelfthRoot(BigDecimal value) {
        BigDecimal root = BigDecimal.valueOf(Math.pow(value.doubleValue(), 1.0 / MONTHS_PER_YEAR));
        BigDecimal index = BigDecimal.valueOf(MONTHS_PER_YEAR);
        for (int step = 0; step < 2; step++) {
            BigDecimal power = root.pow(MONTHS_PER_YEAR - 1, MC);
            BigDecimal excess = power.multiply(root, MC).subtract(value, MC);
            root = root.subtract(excess.divide(index.multiply(power, MC), MC), MC);
        }
        return root;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Completa los dos campos que el mapper deja en null porque no existen en la
     * entidad: lo aportado en total y la brecha entre los dos escenarios, que es
     * el numero que da sentido a toda la pantalla.
     */
    private ProjectionResponse toResponse(Projection projection) {
        ProjectionResponse response = mapper.map(projection, ProjectionResponse.class);

        response.setTotalContributed(totalContributed(projection.getInitialCapital(),
                projection.getMonthlyContribution(), projection.getPeriods()));

        response.setDifference(scale(projection.getCompoundFinalAmount()
                .subtract(projection.getSimpleFinalAmount())));

        return response;
    }

}
