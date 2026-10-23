package com.checkout.backend.savings;

import com.checkout.backend.support.DatabaseCleaner;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/movements: ingresos y gastos en un solo listado paginado.
 *
 * Lo delicado es que la pagina se arma sobre dos tablas. Si el orden o el
 * conteo se calcularan por separado en cada una, la pagina 2 repetiria o
 * saltaria filas; estos tests fijan que no pase.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MovementFeedTest {

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

        income(token, "5000.00", "Practicas", daysAgo(40), "Pago de agosto");
        income(token, "1200.00", "Freelance", daysAgo(20), "Landing page");
        income(token, "300.00", "Practicas", daysAgo(5), "Bono");

        expense(token, "FOOD", "28.50", daysAgo(30), "Almuerzo en la U");
        expense(token, "TRANSPORT", "12.00", daysAgo(25), "Pasaje");
        expense(token, "FOOD", "45.00", daysAgo(10), "Cena");
        expense(token, "LEISURE", "60.00", daysAgo(3), "Cine");
        expense(token, "EDUCATION", "35.00", daysAgo(2), "Descuento 50% en libros");
    }

    @AfterEach
    void cleanUp() {
        DatabaseCleaner.clean(jdbc);
    }

    @Test
    @DisplayName("Mezcla ingresos y gastos, del mas reciente al mas antiguo")
    void mixesBothKindsByDateDesc() throws Exception {
        auth(get("/api/v1/movements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(8))
                .andExpect(jsonPath("$.content[0].kind").value("EXPENSE"))
                .andExpect(jsonPath("$.content[0].label").value("Descuento 50% en libros"))
                .andExpect(jsonPath("$.content[0].category").value("EDUCATION"))
                .andExpect(jsonPath("$.content[2].kind").value("INCOME"))
                .andExpect(jsonPath("$.content[2].label").value("Practicas"))
                .andExpect(jsonPath("$.content[2].category").value(nullValue()))
                .andExpect(jsonPath("$.content[2].amount").value(300.00))
                .andExpect(jsonPath("$.content[2].date").value(daysAgo(5)))
                .andExpect(jsonPath("$.content[7].label").value("Practicas"))
                .andExpect(jsonPath("$.content[7].date").value(daysAgo(40)));
    }

    @Test
    @DisplayName("La paginacion recorre las dos tablas sin repetir ni saltar filas")
    void paginatesAcrossBothTables() throws Exception {
        Set<String> seen = new HashSet<>();
        for (int page = 0; page < 3; page++) {
            JsonNode body = json(auth(get("/api/v1/movements").param("size", "3").param("page", String.valueOf(page)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(8))
                    .andExpect(jsonPath("$.totalPages").value(3)));
            for (JsonNode movement : body.get("content")) {
                seen.add(movement.get("kind").asString() + "#" + movement.get("id").asLong());
            }
        }
        assertThat(seen).hasSize(8);
    }

    @Test
    @DisplayName("Con fechas iguales el orden es estable entre paginas")
    void tiesAreStableAcrossPages() throws Exception {
        String sameDay = daysAgo(1);
        for (int i = 0; i < 3; i++) {
            income(token, "10.00", "Propina " + i, sameDay, "x");
            expense(token, "FOOD", "5.00", sameDay, "Snack " + i);
        }

        Set<String> seen = new HashSet<>();
        for (int page = 0; page < 3; page++) {
            JsonNode body = json(auth(get("/api/v1/movements")
                    .param("from", sameDay).param("to", sameDay)
                    .param("size", "2").param("page", String.valueOf(page))));
            for (JsonNode movement : body.get("content")) {
                seen.add(movement.get("kind").asString() + "#" + movement.get("id").asLong());
            }
        }
        assertThat(seen).hasSize(6);
    }

    @Test
    @DisplayName("El rango de fechas filtra las dos tablas")
    void dateRangeFiltersBoth() throws Exception {
        auth(get("/api/v1/movements").param("from", daysAgo(12)).param("to", daysAgo(4)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].label").value("Practicas"))
                .andExpect(jsonPath("$.content[1].label").value("Cena"));
    }

    @Test
    @DisplayName("La busqueda mira la fuente y descripcion del ingreso y la descripcion del gasto")
    void searchMatchesBothKinds() throws Exception {
        auth(get("/api/v1/movements").param("search", "PRACTICAS"))
                .andExpect(jsonPath("$.totalElements").value(2));
        auth(get("/api/v1/movements").param("search", "bono"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].kind").value("INCOME"));
        auth(get("/api/v1/movements").param("search", "cena"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].kind").value("EXPENSE"));
    }

    @Test
    @DisplayName("Los comodines de LIKE se buscan como texto")
    void searchTreatsWildcardsLiterally() throws Exception {
        auth(get("/api/v1/movements").param("search", "50%"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].label").value("Descuento 50% en libros"));
        auth(get("/api/v1/movements").param("search", "_"))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("Se puede ordenar por monto en los dos sentidos")
    void sortsByAmount() throws Exception {
        auth(get("/api/v1/movements").param("sort", "amount,desc"))
                .andExpect(jsonPath("$.content[0].amount").value(5000.00))
                .andExpect(jsonPath("$.content[7].amount").value(12.00));
        auth(get("/api/v1/movements").param("sort", "amount,asc"))
                .andExpect(jsonPath("$.content[0].label").value("Pasaje"));
    }

    @Test
    @DisplayName("Un orden fuera de la lista blanca es 400, tambien si apunta a otra entidad")
    void rejectsUnknownSort() throws Exception {
        auth(get("/api/v1/movements").param("sort", "source,asc"))
                .andExpect(status().isBadRequest());
        auth(get("/api/v1/movements").param("sort", "user.passwordHash,asc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Un rango incompleto o al reves es 400, igual que en los otros listados")
    void rejectsInvalidRange() throws Exception {
        auth(get("/api/v1/movements").param("from", daysAgo(5)))
                .andExpect(status().isBadRequest());
        auth(get("/api/v1/movements").param("from", daysAgo(1)).param("to", daysAgo(5)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Solo aparecen los movimientos del usuario autenticado")
    void onlyOwnMovements() throws Exception {
        String other = registerAndGetToken();
        income(other, "999.00", "Ajeno", daysAgo(1), "No es mio");
        expense(other, "OTHER", "1.00", daysAgo(1), "Tampoco");

        auth(get("/api/v1/movements"))
                .andExpect(jsonPath("$.totalElements").value(8));
        mockMvc.perform(get("/api/v1/movements").header("Authorization", "Bearer " + other))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("Una pagina fuera de rango viene vacia, con el total correcto")
    void pageBeyondTheEndIsEmpty() throws Exception {
        auth(get("/api/v1/movements").param("page", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(8));
    }

    @Test
    @DisplayName("Sin token es 401")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/movements"))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions auth(MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder.header("Authorization", "Bearer " + token));
    }

    private static JsonNode json(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private void income(String bearer, String amount, String source, String date, String description) throws Exception {
        mockMvc.perform(post("/api/v1/incomes")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":%s,"source":"%s","date":"%s","description":"%s"}
                                """.formatted(amount, source, date, description)))
                .andExpect(status().isCreated());
    }

    private void expense(String bearer, String category, String amount, String date, String description)
            throws Exception {
        mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", "Bearer " + bearer)
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
