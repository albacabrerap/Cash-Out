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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Busqueda, filtros combinables y orden en los listados de movimientos.
 *
 * Es lo que necesita el frontend para una pantalla de movimientos con buscador,
 * panel de filtros y columnas ordenables: los tres se mandan juntos en la misma
 * peticion y tienen que convivir con la paginacion.
 *
 * Lo que ya funcionaba antes de este cambio esta fijado en
 * MovementListingContractTest y tiene que seguir pasando.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MovementSearchTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    private String token;

    private static String daysAgo(int n) {
        return LocalDate.now().minusDays(n).toString();
    }

    @BeforeEach
    void setUp() throws Exception {
        token = registerAndGetToken();

        income("5000.00", "Practicas", daysAgo(40), "Pago de agosto");
        income("1200.00", "Freelance", daysAgo(20), "Landing page");
        income("300.00", "Practicas", daysAgo(5), "Bono");

        expense("FOOD", "28.50", daysAgo(30), "Almuerzo en la U");
        expense("TRANSPORT", "12.00", daysAgo(25), "Pasaje");
        expense("FOOD", "45.00", daysAgo(10), "Cena");
        expense("LEISURE", "60.00", daysAgo(3), "Cine");
        expense("EDUCATION", "35.00", daysAgo(2), "Descuento 50% en libros");
    }

    @AfterEach
    void cleanUp() {
        DatabaseCleaner.clean(jdbc);
    }

    // ------------------------------------------------------------------
    // Filtros combinables
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Categoria y rango de fechas se combinan (antes era 400)")
    void categoryAndDateRangeCombine() throws Exception {
        auth(get("/api/v1/expenses")
                .param("category", "FOOD")
                .param("from", daysAgo(15)).param("to", daysAgo(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].description").value("Cena"));
    }

    // ------------------------------------------------------------------
    // Busqueda
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Busqueda en la descripcion del gasto, sin distinguir mayusculas")
    void searchExpensesByDescription() throws Exception {
        auth(get("/api/v1/expenses").param("search", "ALMUERZO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].description").value("Almuerzo en la U"));
    }

    @Test
    @DisplayName("Busqueda, categoria, rango y paginacion en la misma peticion")
    void searchFiltersAndPaginationTogether() throws Exception {
        auth(get("/api/v1/expenses")
                .param("search", "c")
                .param("category", "FOOD")
                .param("from", daysAgo(40)).param("to", daysAgo(1))
                .param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].description").value("Cena"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.size").value(1));
    }

    @Test
    @DisplayName("Un % en la busqueda es un caracter, no un comodin")
    void searchTreatsWildcardsLiterally() throws Exception {
        // Sin escapar, "%" se convierte en el patron %%% y devuelve todo.
        auth(get("/api/v1/expenses").param("search", "%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].description").value("Descuento 50% en libros"));
    }

    @Test
    @DisplayName("Una busqueda en blanco se ignora")
    void blankSearchIsIgnored() throws Exception {
        auth(get("/api/v1/expenses").param("search", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5));
    }

    @Test
    @DisplayName("Una busqueda de mas de 100 caracteres es 400")
    void overlongSearchIsRejected() throws Exception {
        auth(get("/api/v1/expenses").param("search", "x".repeat(101)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Busqueda de ingresos en la fuente o en la descripcion")
    void searchIncomesBySourceOrDescription() throws Exception {
        auth(get("/api/v1/incomes").param("search", "freelance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].source").value("Freelance"));

        auth(get("/api/v1/incomes").param("search", "bono"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].description").value("Bono"));
    }

    // ------------------------------------------------------------------
    // Orden
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Gastos ordenados por monto, de mayor a menor")
    void sortExpensesByAmountDesc() throws Exception {
        auth(get("/api/v1/expenses").param("sort", "amount,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].description").value("Cine"))
                .andExpect(jsonPath("$.content[4].description").value("Pasaje"));
    }

    @Test
    @DisplayName("Gastos ordenados por monto, de menor a mayor")
    void sortExpensesByAmountAsc() throws Exception {
        auth(get("/api/v1/expenses").param("sort", "amount,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].description").value("Pasaje"))
                .andExpect(jsonPath("$.content[4].description").value("Cine"));
    }

    @Test
    @DisplayName("Ingresos ordenados por monto")
    void sortIncomesByAmount() throws Exception {
        auth(get("/api/v1/incomes").param("sort", "amount,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].description").value("Pago de agosto"))
                .andExpect(jsonPath("$.content[2].description").value("Bono"));
    }

    @Test
    @DisplayName("Ordenar por un campo que no existe es 400, no 500")
    void unknownSortFieldIsBadRequest() throws Exception {
        auth(get("/api/v1/expenses").param("sort", "precio,desc"))
                .andExpect(status().isBadRequest());
        auth(get("/api/v1/incomes").param("sort", "precio,desc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("No se puede ordenar navegando a otra entidad, como user.passwordHash")
    void sortCannotReachIntoOtherEntities() throws Exception {
        auth(get("/api/v1/expenses").param("sort", "user.passwordHash,asc"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------

    private ResultActions auth(MockHttpServletRequestBuilder builder) throws Exception {
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
