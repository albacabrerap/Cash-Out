package com.checkout.backend.updown;

import com.checkout.backend.market.MarketDataClient;
import com.checkout.backend.support.DatabaseCleaner;
import com.checkout.backend.user.model.Role;
import com.checkout.backend.user.model.User;
import com.checkout.backend.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** El banco de casos: solo ADMIN, y cada caso se valida contra los cierres (aqui, falsos: bajan 1 % al dia). */
@SpringBootTest
@AutoConfigureMockMvc
class UpDownCaseApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "Secreto123!";

    /** "NADA" no tiene cierres; el resto cae 1 % cada dia habil. */
    static class FallingMarket implements MarketDataClient {
        @Override
        public List<ProviderAsset> listUsEquities() {
            return List.of();
        }

        @Override
        public Map<String, ProviderSnapshot> snapshots(Collection<String> symbols) {
            return Map.of();
        }

        @Override
        public List<ProviderBar> dailyBars(String symbol, LocalDate from, LocalDate to) {
            List<ProviderBar> bars = new ArrayList<>();
            double price = 100;
            for (LocalDate day = from; !day.isAfter(to) && !symbol.equals("NADA"); day = day.plusDays(1)) {
                if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                    bars.add(new ProviderBar(day, BigDecimal.valueOf(price)));
                    price *= 0.99;
                }
            }
            return bars;
        }
    }

    @TestConfiguration
    static class FallingMarketConfig {
        @Bean
        @Primary
        FallingMarket fallingMarket() {
            return new FallingMarket();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;

    private String admin;
    private String student;

    @BeforeEach
    void setUp() throws Exception {
        users.save(User.builder()
                .name("Alba")
                .email("alba@utec.edu.pe")
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .roles(EnumSet.of(Role.ADMIN))
                .build());
        admin = login("alba@utec.edu.pe");
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Ana\",\"email\":\"ana@utec.edu.pe\",\"password\":\"" + PASSWORD + "\"}"));
        student = login("ana@utec.edu.pe");
    }

    @AfterEach
    void cleanUp() {
        DatabaseCleaner.clean(jdbc);
    }

    @Test
    @DisplayName("El admin ve que paso, guarda el caso con su resultado real, lo edita, lo desactiva y lo borra")
    void adminManagesCases() throws Exception {
        as(admin, get("/api/v1/updown-cases/preview").param("symbol", "spy").param("cutoff", "2024-08-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("SPY"))
                .andExpect(jsonPath("$.past.length()").value(80))
                .andExpect(jsonPath("$.future.length()").value(22))
                .andExpect(jsonPath("$.outcome").value("DOWN"));

        String body = as(admin, post("/api/v1/updown-cases").contentType(MediaType.APPLICATION_JSON)
                        .content(caseJson("spy", "2024-08-02", true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.symbol").value("SPY"))
                .andExpect(jsonPath("$.outcome").value("DOWN"))
                .andExpect(jsonPath("$.timesPlayed").value(0))
                .andReturn().getResponse().getContentAsString();
        long id = JSON.readTree(body).get("id").asLong();

        as(admin, post("/api/v1/updown-cases").contentType(MediaType.APPLICATION_JSON)
                        .content(caseJson("SPY", "2024-08-02", true)))
                .andExpect(status().isConflict());

        as(admin, put("/api/v1/updown-cases/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(caseJson("SPY", "2024-08-02", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        as(admin, get("/api/v1/updown-cases"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].active").value(false));

        as(admin, delete("/api/v1/updown-cases/" + id)).andExpect(status().isNoContent());
        as(admin, get("/api/v1/updown-cases")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("Un caso sin cierres suficientes, con datos invalidos o de alguien que no es ADMIN no se guarda")
    void invalidCasesAreRejected() throws Exception {
        as(admin, post("/api/v1/updown-cases").contentType(MediaType.APPLICATION_JSON)
                        .content(caseJson("NADA", "2024-08-02", true)))
                .andExpect(status().isBadRequest());
        // Un sabado no tiene cierre.
        as(admin, get("/api/v1/updown-cases/preview").param("symbol", "SPY").param("cutoff", "2024-08-03"))
                .andExpect(status().isBadRequest());
        as(admin, post("/api/v1/updown-cases").contentType(MediaType.APPLICATION_JSON)
                        .content(caseJson("S P Y", "2024-08-02", true)))
                .andExpect(status().isBadRequest());
        as(admin, post("/api/v1/updown-cases").contentType(MediaType.APPLICATION_JSON)
                        .content(caseJson("SPY", "2999-01-01", true)))
                .andExpect(status().isBadRequest());

        as(student, get("/api/v1/updown-cases")).andExpect(status().isForbidden());
        as(student, post("/api/v1/updown-cases").contentType(MediaType.APPLICATION_JSON)
                        .content(caseJson("SPY", "2024-08-02", true)))
                .andExpect(status().isForbidden());
        as(admin, get("/api/v1/updown-cases")).andExpect(jsonPath("$.length()").value(0));
    }

    private static String caseJson(String symbol, String cutoff, boolean active) {
        return "{\"symbol\":\"" + symbol + "\",\"cutoff\":\"" + cutoff + "\",\"sector\":\"Índice amplio\","
                + "\"name\":\"el S&P 500\",\"context\":\"El desempleo sube.\",\"lesson\":\"Vender en pánico sale caro.\","
                + "\"active\":" + active + "}";
    }

    private ResultActions as(String token, MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder.header("Authorization", "Bearer " + token));
    }

    private String login(String email) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("accessToken").asString();
    }

}
