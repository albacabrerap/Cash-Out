package com.checkout.backend.savings;

import com.checkout.backend.support.DatabaseCleaner;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato de los listados de gastos e ingresos tal como funcionan hoy.
 *
 * Existe para poder cambiar como se implementan esos listados sin cambiar lo
 * que devuelven. Antes de esta clase ningun test cubria los filtros, asi que la
 * suite pasaba en verde aunque se rompieran. Todo lo que se afirma aqui es
 * comportamiento que ya existia y que un cliente puede estar usando: paginacion,
 * orden por fecha, filtro por rango, filtro por categoria, aislamiento entre
 * usuarios y los 400 de parametros mal formados.
 *
 * Lo que cambia a proposito —combinar categoria con fechas, buscar por texto y
 * ordenar por otros campos— se prueba aparte, en MovementSearchTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MovementListingContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    private String token;

    /** Hoy menos n dias. Las fechas futuras las rechaza @PastOrPresent. */
    private static String daysAgo(int n) {
        return LocalDate.now().minusDays(n).toString();
    }

    @BeforeEach
    void setUp() throws Exception {
        token = registerAndGetToken();

        // Saldo de sobra para que ningun gasto se rechace por disponible.
        income("5000.00", "Practicas", daysAgo(40), "Pago de agosto");
        income("1200.00", "Freelance", daysAgo(20), "Landing page");
        income("300.00", "Practicas", daysAgo(5), "Bono");

        expense("FOOD", "28.50", daysAgo(30), "Almuerzo en la U");
        expense("TRANSPORT", "12.00", daysAgo(25), "Pasaje");
        expense("FOOD", "45.00", daysAgo(10), "Cena");
        expense("LEISURE", "60.00", daysAgo(3), "Cine");
    }

    @AfterEach
    void cleanUp() {
        DatabaseCleaner.clean(jdbc);
    }

    // ------------------------------------------------------------------
    // Gastos
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Gastos sin filtros: todos, del mas reciente al mas antiguo, con metadatos de pagina")
    void expensesListAllNewestFirst() throws Exception {
        auth(get("/api/v1/expenses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(4))
                .andExpect(jsonPath("$.content[0].description").value("Cine"))
                .andExpect(jsonPath("$.content[3].description").value("Almuerzo en la U"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    @DisplayName("Gastos paginados: size y page recortan el contenido y ajustan totalPages")
    void expensesArePaginated() throws Exception {
        auth(get("/api/v1/expenses").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].description").value("Pasaje"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    @DisplayName("Gastos por rango de fechas: los extremos son inclusivos")
    void expensesFilteredByDateRange() throws Exception {
        auth(get("/api/v1/expenses").param("from", daysAgo(25)).param("to", daysAgo(10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].description").value("Cena"))
                .andExpect(jsonPath("$.content[1].description").value("Pasaje"));
    }

    @Test
    @DisplayName("Gastos por categoria")
    void expensesFilteredByCategory() throws Exception {
        auth(get("/api/v1/expenses").param("category", "FOOD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].description").value("Cena"))
                .andExpect(jsonPath("$.content[1].description").value("Almuerzo en la U"));
    }

    @Test
    @DisplayName("Gastos: un rango con un solo extremo es 400")
    void expensesRangeNeedsBothEnds() throws Exception {
        auth(get("/api/v1/expenses").param("from", daysAgo(10)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Gastos: from posterior a to es 400")
    void expensesRangeMustBeOrdered() throws Exception {
        auth(get("/api/v1/expenses").param("from", daysAgo(1)).param("to", daysAgo(10)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Gastos: una categoria que no existe es 400, no 500")
    void expensesRejectUnknownCategory() throws Exception {
        auth(get("/api/v1/expenses").param("category", "CASINO"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Gastos: un usuario no ve los de otro")
    void expensesAreIsolatedPerUser() throws Exception {
        String other = registerAndGetToken();
        mockMvc.perform(get("/api/v1/expenses").header("Authorization", "Bearer " + other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // ------------------------------------------------------------------
    // Ingresos
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Ingresos sin filtros: del mas reciente al mas antiguo")
    void incomesListAllNewestFirst() throws Exception {
        auth(get("/api/v1/incomes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].description").value("Bono"))
                .andExpect(jsonPath("$.content[2].description").value("Pago de agosto"))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    @DisplayName("Ingresos por rango de fechas")
    void incomesFilteredByDateRange() throws Exception {
        auth(get("/api/v1/incomes").param("from", daysAgo(25)).param("to", daysAgo(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].description").value("Bono"))
                .andExpect(jsonPath("$.content[1].description").value("Landing page"));
    }

    @Test
    @DisplayName("Ingresos: un rango con un solo extremo es 400")
    void incomesRangeNeedsBothEnds() throws Exception {
        auth(get("/api/v1/incomes").param("to", daysAgo(1)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Ingresos: from posterior a to es 400")
    void incomesRangeMustBeOrdered() throws Exception {
        auth(get("/api/v1/incomes").param("from", daysAgo(1)).param("to", daysAgo(10)))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------

    private ResultActions auth(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder)
            throws Exception {
        return mockMvc.perform(builder.header("Authorization", "Bearer " + token));
    }

    private void income(String amount, String source, String date, String description) throws Exception {
        mockMvc.perform(post("/api/v1/incomes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":%s,"source":"%s","date":"%s","description":"%s"}
                                """.formatted(amount, source, date, description)))
                .andExpect(status().isCreated());
    }

    private void expense(String category, String amount, String date, String description) throws Exception {
        mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category":"%s","amount":%s,"date":"%s","description":"%s"}
                                """.formatted(category, amount, date, description)))
                .andExpect(status().isCreated());
    }

    private String registerAndGetToken() throws Exception {
        String email = "ana" + UUID.randomUUID() + "@utec.edu.pe";
        String body = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ana\",\"email\":\"" + email
                                + "\",\"password\":\"Secreto123!\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("accessToken").asString();
    }
}
