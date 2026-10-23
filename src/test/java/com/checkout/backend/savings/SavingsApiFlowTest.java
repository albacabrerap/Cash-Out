package com.checkout.backend.savings;

import com.checkout.backend.user.model.User;
import com.checkout.backend.user.repository.UserRepository;
import com.checkout.backend.support.DatabaseCleaner;
import com.checkout.backend.user.service.CurrentUserProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Recorre el modulo de ahorro de punta a punta: HTTP, controller, service,
 * repositorio y H2.
 *
 * Es un test de integracion a proposito. Los tests con servicios simulados
 * comprueban que el controller llama a quien debe, pero no verian el defecto que
 * de verdad importa aqui, que es que las tres cifras del saldo dejen de cuadrar
 * entre si. Eso solo aparece cuando los aportes, los ingresos y los gastos se
 * escriben de verdad y se vuelven a leer.
 *
 * addFilters = false desactiva la cadena de Spring Security. Todavia no hay
 * SecurityFilterChain propio (issue #5), asi que la configuracion por defecto de
 * Boot responderia 401 a todo antes de llegar al controller. La identidad la
 * aporta el doble de CurrentUserProvider de abajo, que es justo la costura que
 * esa interfaz existe para permitir.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class SavingsApiFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestCurrentUserProvider currentUserProvider;

    @Autowired
    private JdbcTemplate jdbc;

    private User ana;

    /**
     * Sin @Transactional a proposito: estos flujos mueven saldo y comprometen
     * dinero en metas, y son exactamente los que hay que probar commiteando de
     * verdad. Envolverlos en la transaccion del test cambia la semantica de
     * rollback que se esta verificando.
     */
    @AfterEach
    void cleanUp() {
        DatabaseCleaner.clean(jdbc);
    }

    @BeforeEach
    void setUp() {
        ana = userRepository.save(User.builder()
                .name("Ana")
                .email("ana@utec.edu.pe")
                .passwordHash("$2a$10$hash")
                .birthDate(LocalDate.of(2002, 5, 14))
                .build());
        currentUserProvider.setCurrentUser(ana);
    }

    // ------------------------------------------------------------------
    // Versionado y forma de las rutas
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Las rutas viven bajo /api/v1; sin el prefijo no existen")
    void routesAreVersioned() throws Exception {
        mockMvc.perform(get("/api/v1/savings")).andExpect(status().isOk());
        mockMvc.perform(get("/savings")).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // Saldo
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Un usuario sin movimientos ve su ahorro en cero, sin haberlo creado antes")
    void freshUserSeesZeroedSavings() throws Exception {
        mockMvc.perform(get("/api/v1/savings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentBalance").value(0))
                .andExpect(jsonPath("$.committedAmount").value(0))
                .andExpect(jsonPath("$.availableBalance").value(0));
    }

    @Test
    @DisplayName("Un ingreso suma al saldo y devuelve 201 con Location")
    void incomeCreditsTheBalance() throws Exception {
        mockMvc.perform(post("/api/v1/incomes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 1500.00, "source": "Sueldo", "date": "2026-09-01"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/incomes/")))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.amount").value(1500.00));

        mockMvc.perform(get("/api/v1/savings"))
                .andExpect(jsonPath("$.currentBalance").value(1500.00))
                .andExpect(jsonPath("$.availableBalance").value(1500.00));
    }

    @Test
    @DisplayName("Un gasto resta del saldo")
    void expenseDebitsTheBalance() throws Exception {
        registerIncome("1000.00");

        mockMvc.perform(post("/api/v1/expenses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category": "FOOD", "amount": 250.00, "date": "2026-09-02"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/savings"))
                .andExpect(jsonPath("$.currentBalance").value(750.00));
    }

    @Test
    @DisplayName("400: un gasto mayor al disponible se rechaza y no mueve el saldo")
    void expenseBeyondAvailableIsRejected() throws Exception {
        registerIncome("100.00");

        mockMvc.perform(post("/api/v1/expenses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category": "LEISURE", "amount": 500.00, "date": "2026-09-02"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("saldo disponible")));

        mockMvc.perform(get("/api/v1/savings"))
                .andExpect(jsonPath("$.currentBalance").value(100.00));
    }

    // ------------------------------------------------------------------
    // Metas: el modelo del sobre virtual
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Un aporte compromete dinero pero no lo mueve: el saldo total no cambia")
    void contributionCommitsWithoutMovingMoney() throws Exception {
        registerIncome("1000.00");
        long goalId = createGoal("Viaje", "600.00", LocalDate.now().plusMonths(6));

        mockMvc.perform(post("/api/v1/savings-goals/" + goalId + "/contributions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 400.00, "source": "MANUAL"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(400.00))
                .andExpect(jsonPath("$.source").value("MANUAL"));

        // La invariante del modelo: el dinero sigue ahi, solo que apartado.
        mockMvc.perform(get("/api/v1/savings"))
                .andExpect(jsonPath("$.currentBalance").value(1000.00))
                .andExpect(jsonPath("$.committedAmount").value(400.00))
                .andExpect(jsonPath("$.availableBalance").value(600.00));
    }

    @Test
    @DisplayName("400: no se puede gastar el dinero ya comprometido en una meta")
    void committedMoneyCannotBeSpent() throws Exception {
        registerIncome("1000.00");
        long goalId = createGoal("Viaje", "900.00", LocalDate.now().plusMonths(6));
        contribute(goalId, "800.00");

        // Quedan 200 disponibles de los 1000 del saldo.
        mockMvc.perform(post("/api/v1/expenses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category": "OTHER", "amount": 300.00, "date": "2026-09-03"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("comprometido en metas")));
    }

    @Test
    @DisplayName("400: un aporte no puede pasarse de lo que falta para la meta")
    void contributionCannotExceedRemaining() throws Exception {
        registerIncome("5000.00");
        long goalId = createGoal("Laptop", "1000.00", LocalDate.now().plusMonths(3));
        contribute(goalId, "700.00");

        mockMvc.perform(post("/api/v1/savings-goals/" + goalId + "/contributions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 500.00, "source": "MANUAL"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("falta para la meta")));
    }

    @Test
    @DisplayName("400: un aporte no puede superar el saldo disponible")
    void contributionCannotExceedAvailable() throws Exception {
        registerIncome("100.00");
        long goalId = createGoal("Viaje", "5000.00", LocalDate.now().plusMonths(6));

        mockMvc.perform(post("/api/v1/savings-goals/" + goalId + "/contributions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 500.00, "source": "MANUAL"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("saldo disponible")));
    }

    @Test
    @DisplayName("Alcanzar el objetivo cierra la meta y libera lo comprometido")
    void reachingTheTargetCompletesTheGoal() throws Exception {
        registerIncome("1000.00");
        long goalId = createGoal("Audifonos", "300.00", LocalDate.now().plusMonths(2));
        contribute(goalId, "300.00");

        mockMvc.perform(get("/api/v1/savings-goals/" + goalId))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.progressPercent").value(100.00))
                .andExpect(jsonPath("$.completedAt").exists());

        // Una meta cumplida deja de contar como comprometida: ese dinero ya
        // cumplio su proposito.
        mockMvc.perform(get("/api/v1/savings"))
                .andExpect(jsonPath("$.committedAmount").value(0));
    }

    @Test
    @DisplayName("400: no se aporta a una meta ya cumplida")
    void cannotContributeToCompletedGoal() throws Exception {
        registerIncome("1000.00");
        long goalId = createGoal("Audifonos", "300.00", LocalDate.now().plusMonths(2));
        contribute(goalId, "300.00");

        mockMvc.perform(post("/api/v1/savings-goals/" + goalId + "/contributions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 10.00, "source": "MANUAL"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("ya esta cumplida")));
    }

    @Test
    @DisplayName("El progreso sale con dos decimales")
    void progressPercentIsRounded() throws Exception {
        registerIncome("1000.00");
        long goalId = createGoal("Viaje", "300.00", LocalDate.now().plusMonths(6));
        contribute(goalId, "100.00");

        mockMvc.perform(get("/api/v1/savings-goals/" + goalId))
                .andExpect(jsonPath("$.progressPercent").value(33.33));
    }

    @Test
    @DisplayName("La meta dice cuando se creo, para medir el avance contra el plazo")
    void goalExposesCreationDate() throws Exception {
        long goalId = createGoal("Viaje", "300.00", LocalDate.now().plusMonths(6));

        mockMvc.perform(get("/api/v1/savings-goals/" + goalId))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    // ------------------------------------------------------------------
    // Aislamiento entre usuarios
    // ------------------------------------------------------------------

    @Test
    @DisplayName("404: la meta de otro usuario no existe para mi, y no se confirma que exista")
    void anotherUsersGoalIsNotFound() throws Exception {
        registerIncome("1000.00");
        long goalId = createGoal("Viaje", "500.00", LocalDate.now().plusMonths(6));

        User beto = userRepository.save(User.builder()
                .name("Beto")
                .email("beto@utec.edu.pe")
                .passwordHash("$2a$10$hash")
                .build());
        currentUserProvider.setCurrentUser(beto);

        // 404 y no 403: un 403 confirmaria que ese id existe.
        mockMvc.perform(get("/api/v1/savings-goals/" + goalId))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/savings-goals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ------------------------------------------------------------------
    // Edicion y borrado
    // ------------------------------------------------------------------

    @Test
    @DisplayName("400: el objetivo no puede quedar por debajo de lo ya aportado")
    void targetCannotDropBelowAccumulated() throws Exception {
        registerIncome("1000.00");
        long goalId = createGoal("Viaje", "800.00", LocalDate.now().plusMonths(6));
        contribute(goalId, "500.00");

        mockMvc.perform(put("/api/v1/savings-goals/" + goalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Viaje", "targetAmount": 300.00, "deadline": "%s"}
                                """.formatted(LocalDate.now().plusMonths(6))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("ya aportado")));
    }

    @Test
    @DisplayName("409: dos metas del mismo usuario no pueden llamarse igual")
    void duplicateGoalNameIsConflict() throws Exception {
        createGoal("Viaje", "500.00", LocalDate.now().plusMonths(6));

        mockMvc.perform(post("/api/v1/savings-goals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "viaje", "targetAmount": 900.00, "deadline": "%s"}
                                """.formatted(LocalDate.now().plusMonths(8))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Borrar la meta libera lo comprometido y devuelve 204")
    void deletingAGoalFreesTheCommittedMoney() throws Exception {
        registerIncome("1000.00");
        long goalId = createGoal("Viaje", "600.00", LocalDate.now().plusMonths(6));
        contribute(goalId, "400.00");

        mockMvc.perform(delete("/api/v1/savings-goals/" + goalId))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/savings"))
                .andExpect(jsonPath("$.currentBalance").value(1000.00))
                .andExpect(jsonPath("$.committedAmount").value(0))
                .andExpect(jsonPath("$.availableBalance").value(1000.00));
    }

    @Test
    @DisplayName("400: borrar un ingreso cuyo dinero ya esta comprometido")
    void cannotDeleteIncomeBackingAGoal() throws Exception {
        long incomeId = registerIncome("1000.00");
        long goalId = createGoal("Viaje", "900.00", LocalDate.now().plusMonths(6));
        // 800 y no 900: aportar el objetivo completo cumpliria la meta, y una
        // meta cumplida deja de comprometer dinero, que es justo lo contrario
        // de lo que este test necesita comprobar.
        contribute(goalId, "800.00");

        mockMvc.perform(delete("/api/v1/incomes/" + incomeId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("comprometido en metas")));
    }

    // ------------------------------------------------------------------
    // Validacion
    // ------------------------------------------------------------------

    @Test
    @DisplayName("400: el cuerpo invalido enumera los campos rechazados")
    void invalidBodyListsFields() throws Exception {
        mockMvc.perform(post("/api/v1/incomes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": -5, "source": "", "date": "2099-01-01"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors", hasSize(3)))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'amount')]", hasSize(1)))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'source')]", hasSize(1)))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'date')]", hasSize(1)));
    }

    @Test
    @DisplayName("400: una categoria que no existe en el enum")
    void unknownCategoryIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/expenses").param("category", "CRIPTOMONEDAS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("CRIPTOMONEDAS")));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private long registerIncome(String amount) throws Exception {
        String body = mockMvc.perform(post("/api/v1/incomes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": %s, "source": "Sueldo", "date": "2026-09-01"}
                                """.formatted(amount)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return idOf(body);
    }

    private long createGoal(String name, String target, LocalDate deadline) throws Exception {
        String body = mockMvc.perform(post("/api/v1/savings-goals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "targetAmount": %s, "deadline": "%s"}
                                """.formatted(name, target, deadline)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return idOf(body);
    }

    private void contribute(long goalId, String amount) throws Exception {
        mockMvc.perform(post("/api/v1/savings-goals/" + goalId + "/contributions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": %s, "source": "MANUAL"}
                                """.formatted(amount)))
                .andExpect(status().isCreated());
    }

    /** Saca el id del JSON sin traer un parser entero para una sola clave. */
    private static long idOf(String json) {
        String marker = "\"id\":";
        int start = json.indexOf(marker) + marker.length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        return Long.parseLong(json.substring(start, end));
    }

    /**
     * Doble de CurrentUserProvider que devuelve el usuario que el test decida.
     *
     * Reemplaza al de produccion, que lee del SecurityContext. Cambiar de usuario
     * a mitad del test es lo que permite comprobar el aislamiento entre cuentas
     * sin montar autenticacion.
     */
    static class TestCurrentUserProvider implements CurrentUserProvider {

        private User currentUser;

        void setCurrentUser(User user) {
            this.currentUser = user;
        }

        @Override
        public User requireCurrentUser() {
            return currentUser;
        }
    }

    @TestConfiguration
    static class Config {

        /**
         * @Primary porque el de produccion sigue en el contexto: este no lo
         * reemplaza, lo gana. Sin la anotacion el arranque falla con dos
         * candidatos para la misma interfaz.
         */
        @Bean
        @Primary
        TestCurrentUserProvider testCurrentUserProvider() {
            return new TestCurrentUserProvider();
        }
    }

}
