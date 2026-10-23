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
 * El dinero de una meta vencida deja de estar comprometido.
 *
 * Una meta vence por el paso del tiempo y ese estado no se escribe en la fila
 * (ver SavingsGoalService). Si el comprometido se calculara solo con el estado
 * guardado, una meta vencida seguiria reteniendo su dinero: la pantalla la
 * mostraria como vencida y el disponible no subiria nunca.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GoalExpiryReleaseTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    private String token;
    private long goalId;

    @BeforeEach
    void setUp() throws Exception {
        token = registerAndGetToken();

        auth(post("/api/v1/incomes").contentType(MediaType.APPLICATION_JSON).content("""
                {"amount":1000.00,"source":"Practicas","date":"%s"}
                """.formatted(LocalDate.now().minusDays(10))))
                .andExpect(status().isCreated());

        String goal = auth(post("/api/v1/savings-goals").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"Viaje","targetAmount":500.00,"deadline":"%s"}
                """.formatted(LocalDate.now().plusDays(30))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        goalId = JSON.readTree(goal).get("id").asLong();

        auth(post("/api/v1/savings-goals/" + goalId + "/contributions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":300.00,\"source\":\"MANUAL\"}"))
                .andExpect(status().isCreated());
    }

    @AfterEach
    void cleanUp() {
        DatabaseCleaner.clean(jdbc);
    }

    @Test
    @DisplayName("Mientras la meta esta en curso, lo aportado esta comprometido")
    void activeGoalHoldsItsMoney() throws Exception {
        auth(get("/api/v1/savings"))
                .andExpect(jsonPath("$.currentBalance").value(1000.00))
                .andExpect(jsonPath("$.committedAmount").value(300.00))
                .andExpect(jsonPath("$.availableBalance").value(700.00));
    }

    @Test
    @DisplayName("Al vencer, lo aportado vuelve al disponible aunque la fila siga IN_PROGRESS")
    void expiredGoalReleasesItsMoney() throws Exception {
        expireGoal();

        auth(get("/api/v1/savings-goals/" + goalId))
                .andExpect(jsonPath("$.status").value("EXPIRED"));
        auth(get("/api/v1/savings"))
                .andExpect(jsonPath("$.committedAmount").value(0))
                .andExpect(jsonPath("$.availableBalance").value(1000.00));
    }

    @Test
    @DisplayName("El dinero liberado se puede gastar")
    void releasedMoneyCanBeSpent() throws Exception {
        expireGoal();

        auth(post("/api/v1/expenses").contentType(MediaType.APPLICATION_JSON).content("""
                {"category":"OTHER","amount":900.00,"date":"%s"}
                """.formatted(LocalDate.now())))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Un intento fallido de aportar a la meta vencida no la vuelve a retener")
    void failedWriteOnExpiredGoalKeepsItReleased() throws Exception {
        expireGoal();

        auth(post("/api/v1/savings-goals/" + goalId + "/contributions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":10.00,\"source\":\"MANUAL\"}"))
                .andExpect(status().isBadRequest());
        auth(get("/api/v1/savings"))
                .andExpect(jsonPath("$.committedAmount").value(0));
    }

    /** Simula que paso el tiempo: la fecha limite queda en el pasado sin tocar el estado guardado. */
    private void expireGoal() {
        jdbc.update("UPDATE savings_goals SET deadline = ? WHERE id = ?", LocalDate.now().minusDays(1), goalId);
    }

    private ResultActions auth(MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder.header("Authorization", "Bearer " + token));
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
