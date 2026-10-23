package com.checkout.backend.config;

import com.checkout.backend.support.DatabaseCleaner;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
 * "Hoy" es el dia de Lima, no el del servidor.
 *
 * El reloj queda fijo en el 4 de octubre a las 02:00 UTC, que en Lima todavia
 * es el 3 a las 21:00. Es la franja en la que un servidor en UTC ya vive en el
 * dia siguiente: entre las 19:00 y la medianoche de Lima, cada dia.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BusinessTimeZoneTest {

    /** 3 de octubre, 21:00 en Lima. */
    private static final Instant NIGHT_IN_LIMA = Instant.parse("2026-10-04T02:00:00Z");
    private static final String LIMA_TODAY = "2026-10-03";
    private static final String UTC_TODAY = "2026-10-04";

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NIGHT_IN_LIMA, ZoneId.of("America/Lima"));
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        token = register("1999-05-20").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        token = JSON.readTree(token).get("accessToken").asString();
        auth(post("/api/v1/incomes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":500.00,\"source\":\"Practicas\",\"date\":\"2026-10-01\"}"))
                .andExpect(status().isCreated());
    }

    @AfterEach
    void cleanUp() {
        DatabaseCleaner.clean(jdbc);
    }

    @Test
    @DisplayName("Un gasto con la fecha de hoy en Lima se acepta, aunque en UTC ya sea ayer")
    void expenseDatedLimaTodayIsAccepted() throws Exception {
        expense(LIMA_TODAY).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Un gasto con la fecha de manana en Lima es futuro, aunque en UTC ya sea hoy")
    void expenseDatedUtcTodayIsFutureInLima() throws Exception {
        expense(UTC_TODAY).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Una meta que vence manana en Lima es futura, aunque en UTC sea hoy")
    void goalDeadlineTomorrowInLimaIsFuture() throws Exception {
        goal("Manana", UTC_TODAY).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Una meta no puede vencer hoy en Lima")
    void goalDeadlineLimaTodayIsNotFuture() throws Exception {
        goal("Hoy", LIMA_TODAY).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Una meta que vence hoy en Lima sigue en curso hasta la medianoche de Lima")
    void goalDueLimaTodayIsStillInProgress() throws Exception {
        String body = goal("Viaje", UTC_TODAY).andReturn().getResponse().getContentAsString();
        long goalId = JSON.readTree(body).get("id").asLong();
        jdbc.update("UPDATE savings_goals SET deadline = ? WHERE id = ?", LocalDate.parse(LIMA_TODAY), goalId);

        auth(get("/api/v1/savings-goals/" + goalId))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    @DisplayName("Una fecha de nacimiento de hoy en Lima no es pasada")
    void birthDateLimaTodayIsNotPast() throws Exception {
        register(LIMA_TODAY).andExpect(status().isBadRequest());
        register("2026-10-02").andExpect(status().isCreated());
    }

    private ResultActions expense(String date) throws Exception {
        return auth(post("/api/v1/expenses").contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"FOOD\",\"amount\":10.00,\"date\":\"" + date + "\"}"));
    }

    private ResultActions goal(String name, String deadline) throws Exception {
        return auth(post("/api/v1/savings-goals").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"targetAmount\":100.00,\"deadline\":\"" + deadline + "\"}"));
    }

    private ResultActions register(String birthDate) throws Exception {
        String email = "ana" + UUID.randomUUID() + "@utec.edu.pe";
        return mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Ana\",\"email\":\"" + email + "\",\"password\":\"Secreto123!\",\"birthDate\":\""
                        + birthDate + "\"}"));
    }

    private ResultActions auth(MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder.header("Authorization", "Bearer " + token));
    }
}
