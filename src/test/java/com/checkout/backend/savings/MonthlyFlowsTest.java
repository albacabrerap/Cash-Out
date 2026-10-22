package com.checkout.backend.savings;

import com.checkout.backend.support.DatabaseCleaner;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/movements/monthly: ingresos y gastos sumados por mes, para el
 * grafico del resumen. El reloj queda fijo en el 15 de octubre de 2026 en Lima.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MonthlyFlowsTest {

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-10-15T15:00:00Z"), ZoneId.of("America/Lima"));
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        token = registerAndGetToken();
        income(token, "1000.00", "2026-05-20");
        income(token, "1500.00", "2026-08-01");
        income(token, "500.00", "2026-08-30");
        income(token, "1200.00", "2026-10-02");
        expense(token, "200.00", "2026-08-10");
        expense(token, "50.50", "2026-08-31");
        expense(token, "300.00", "2026-10-05");
    }

    @AfterEach
    void cleanUp() {
        DatabaseCleaner.clean(jdbc);
    }

    @Test
    @DisplayName("Suma por mes y rellena los meses sin movimientos, hasta el mes en curso")
    void sumsPerMonthIncludingEmptyMonths() throws Exception {
        auth(get("/api/v1/movements/monthly").param("months", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-08"))
                .andExpect(jsonPath("$.to").value("2026-10"))
                .andExpect(jsonPath("$.months.length()").value(3))
                .andExpect(jsonPath("$.months[0].month").value("2026-08"))
                .andExpect(jsonPath("$.months[0].income").value(2000.00))
                .andExpect(jsonPath("$.months[0].expense").value(250.50))
                .andExpect(jsonPath("$.months[0].net").value(1749.50))
                .andExpect(jsonPath("$.months[1].month").value("2026-09"))
                .andExpect(jsonPath("$.months[1].income").value(0))
                .andExpect(jsonPath("$.months[1].expense").value(0))
                .andExpect(jsonPath("$.months[2].month").value("2026-10"))
                .andExpect(jsonPath("$.months[2].net").value(900.00));
    }

    @Test
    @DisplayName("Los totales del periodo los calcula el servidor")
    void totalsForThePeriod() throws Exception {
        auth(get("/api/v1/movements/monthly").param("months", "3"))
                .andExpect(jsonPath("$.totalIncome").value(3200.00))
                .andExpect(jsonPath("$.totalExpense").value(550.50))
                .andExpect(jsonPath("$.totalNet").value(2649.50));
    }

    @Test
    @DisplayName("Sin months, empieza en el mes del primer movimiento")
    void wholeHistoryStartsAtFirstMovement() throws Exception {
        auth(get("/api/v1/movements/monthly"))
                .andExpect(jsonPath("$.from").value("2026-05"))
                .andExpect(jsonPath("$.months.length()").value(6))
                .andExpect(jsonPath("$.months[0].income").value(1000.00));
    }

    @Test
    @DisplayName("Sin movimientos devuelve solo el mes en curso, en cero")
    void noMovements() throws Exception {
        String other = registerAndGetToken();
        mockMvc.perform(get("/api/v1/movements/monthly").header("Authorization", "Bearer " + other))
                .andExpect(jsonPath("$.months.length()").value(1))
                .andExpect(jsonPath("$.months[0].month").value("2026-10"))
                .andExpect(jsonPath("$.totalNet").value(0));
    }

    @Test
    @DisplayName("Solo cuenta los movimientos del usuario autenticado")
    void onlyOwnMovements() throws Exception {
        String other = registerAndGetToken();
        income(other, "9999.00", "2026-10-01");

        auth(get("/api/v1/movements/monthly").param("months", "1"))
                .andExpect(jsonPath("$.months[0].income").value(1200.00));
    }

    @Test
    @DisplayName("months fuera de 1 a 120 es 400")
    void rejectsOutOfRangeMonths() throws Exception {
        auth(get("/api/v1/movements/monthly").param("months", "0")).andExpect(status().isBadRequest());
        auth(get("/api/v1/movements/monthly").param("months", "121")).andExpect(status().isBadRequest());
    }

    private ResultActions auth(MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder.header("Authorization", "Bearer " + token));
    }

    private void income(String bearer, String amount, String date) throws Exception {
        mockMvc.perform(post("/api/v1/incomes")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + amount + ",\"source\":\"Trabajo\",\"date\":\"" + date + "\"}"))
                .andExpect(status().isCreated());
    }

    private void expense(String bearer, String amount, String date) throws Exception {
        mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"FOOD\",\"amount\":" + amount + ",\"date\":\"" + date + "\"}"))
                .andExpect(status().isCreated());
    }

    private String registerAndGetToken() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ana\",\"email\":\"ana" + UUID.randomUUID()
                                + "@utec.edu.pe\",\"password\":\"Secreto123!\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("accessToken").asString();
    }
}
