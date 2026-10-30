package com.checkout.backend.updown;

import com.checkout.backend.market.MarketDataClient;
import com.checkout.backend.support.DatabaseCleaner;
import com.checkout.backend.updown.model.UpDownCase;
import com.checkout.backend.updown.repository.UpDownCaseRepository;
import com.checkout.backend.updown.service.UpDownService;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** El juego de punta a punta con precios que suben 1 % cada dia: todas las rondas terminan en "sube". */
@SpringBootTest
@AutoConfigureMockMvc
class UpDownApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "Secreto123!";

    static class RisingMarket implements MarketDataClient {
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
            for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
                if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                    bars.add(new ProviderBar(day, BigDecimal.valueOf(price)));
                    price *= 1.01;
                }
            }
            return bars;
        }
    }

    @TestConfiguration
    static class RisingMarketConfig {
        @Bean
        @Primary
        RisingMarket risingMarket() {
            return new RisingMarket();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UpDownService service;
    @Autowired private UpDownCaseRepository cases;

    /** Diez casos activos, cada uno un lunes distinto: alcanzan para una partida de 8 sin repetir dia. */
    @BeforeEach
    void seedCases() {
        LocalDate monday = LocalDate.of(2023, 1, 2);
        for (int i = 0; i < 10; i++) {
            cases.save(UpDownCase.builder()
                    .symbol("T" + (char) ('A' + i))
                    .cutoff(monday.plusWeeks(i * 5L))
                    .sector("Sector " + i)
                    .name("Activo " + i)
                    .context("Noticia " + i)
                    .lesson("Leccion " + i)
                    .build());
        }
    }

    @AfterEach
    void cleanUp() {
        DatabaseCleaner.clean(jdbc);
    }

    @Test
    @DisplayName("Partida completa: acertar suma la apuesta, fallar la resta y no votar no cambia nada")
    void fullGame() throws Exception {
        String host = register("host");
        String ana = register("ana");
        String beto = register("beto");
        String code = create(host);
        as(ana, post(room(code, "join"))).andExpect(status().isOk());
        as(beto, post(room(code, "join"))).andExpect(jsonPath("$.joined").value(true));

        as(ana, post(room(code, "start"))).andExpect(status().isForbidden());
        as(host, post(room(code, "start")))
                .andExpect(jsonPath("$.phase").value("VOTING"))
                .andExpect(jsonPath("$.question.past.length()").value(80))
                .andExpect(jsonPath("$.question.past[79]").value(100.0))
                .andExpect(jsonPath("$.question.future").doesNotExist())
                .andExpect(jsonPath("$.question.reveal").doesNotExist());

        vote(ana, code, "UP", 200).andExpect(status().isOk());
        vote(ana, code, "DOWN", 10).andExpect(status().isBadRequest());
        JsonNode seenByBeto = view(beto, code);
        assertThat(seenByBeto.get("tally").get("answered").asInt()).isEqualTo(1);
        JsonNode anaRow = seenByBeto.get("ranking").get(0);
        assertThat(anaRow.get("voted").asBoolean()).isTrue();
        assertThat(anaRow.get("choice").isNull()).isTrue();

        // El ultimo voto cierra la ronda sin esperar al reloj.
        vote(beto, code, "DOWN", 300)
                .andExpect(jsonPath("$.phase").value("REVEAL"))
                .andExpect(jsonPath("$.question.reveal.outcome").value("UP"))
                .andExpect(jsonPath("$.question.future.length()").value(22));
        JsonNode revealed = view(host, code);
        assertThat(revealed.get("ranking").findValuesAsString("name")).containsExactly("Ana", "Beto");
        assertThat(revealed.get("ranking").findValuesAsString("choice")).containsExactly("UP", "DOWN");
        assertThat(revealed.get("ranking").get(0).get("points").asInt()).isEqualTo(1200);
        assertThat(revealed.get("ranking").get(1).get("points").asInt()).isEqualTo(700);
        assertThat(revealed.get("ranking").get(1).get("delta").asInt()).isEqualTo(-300);

        as(host, post(room(code, "next"))).andExpect(jsonPath("$.round").value(2));
        ticks(20);
        JsonNode timedOut = view(host, code);
        assertThat(timedOut.get("phase").asString()).isEqualTo("REVEAL");
        assertThat(timedOut.get("ranking").get(0).get("points").asInt()).isEqualTo(1200);

        int total = timedOut.get("totalRounds").asInt();
        assertThat(total).isEqualTo(8);
        for (int round = 3; round <= total; round++) {
            as(host, post(room(code, "next"))).andExpect(jsonPath("$.round").value(round));
            as(host, post(room(code, "reveal"))).andExpect(jsonPath("$.phase").value("REVEAL"));
        }
        as(host, post(room(code, "next")))
                .andExpect(jsonPath("$.phase").value("FINISHED"))
                .andExpect(jsonPath("$.question").doesNotExist())
                .andExpect(jsonPath("$.history.length()").value(8))
                .andExpect(jsonPath("$.me").doesNotExist());
        assertThat(view(host, code).get("history").findValuesAsString("date")).doesNotHaveDuplicates();
        as(ana, post(room(code, "join"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Reglas: la apuesta va de 10 a tus puntos, solo se vota en la votacion y solo el anfitrion la cierra")
    void rulesAreEnforcedOnTheServer() throws Exception {
        String host = register("host");
        String player = register("player");
        String late = register("late");
        String code = create(host);
        as(player, post(room(code, "join")));

        as(host, post(room(code, "start"))).andExpect(status().isOk());
        vote(late, code, "UP", 50).andExpect(status().isBadRequest());
        as(late, post(room(code, "join"))).andExpect(status().isOk());

        vote(player, code, "UP", 1001).andExpect(status().isBadRequest());
        vote(player, code, "UP", 5).andExpect(status().isBadRequest());
        as(player, post(room(code, "votes")).contentType(MediaType.APPLICATION_JSON).content("{\"choice\":\"MAYBE\",\"bet\":50}"))
                .andExpect(status().isBadRequest());
        as(player, post(room(code, "reveal"))).andExpect(status().isForbidden());
        as(host, post(room(code, "next"))).andExpect(status().isBadRequest());

        as(player, post(room(code, "cancel"))).andExpect(status().isForbidden());
        as(host, post(room(code, "cancel"))).andExpect(status().isNoContent());
        as(player, get("/api/v1/updown-rooms/" + code)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Cada partida empieza por los casos que menos salieron, y los inactivos no salen")
    void casesRotate() throws Exception {
        String host = register("host");
        jdbc.update("UPDATE updown_cases SET active = false WHERE symbol = 'TJ'");

        create(host);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM updown_cases WHERE times_played = 1", Integer.class)).isEqualTo(8);
        create(host);

        assertThat(jdbc.queryForList("SELECT times_played FROM updown_cases WHERE active ORDER BY id", Integer.class))
                .allMatch(times -> times >= 1)
                .hasSize(9);
        assertThat(jdbc.queryForObject("SELECT times_played FROM updown_cases WHERE symbol = 'TJ'", Integer.class)).isZero();
    }

    @Test
    @DisplayName("Sin casos activos no se puede abrir una sala")
    void noActiveCases() throws Exception {
        String host = register("host");
        jdbc.update("UPDATE updown_cases SET active = false");

        as(host, post("/api/v1/updown-rooms")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("La sala en vivo abre un SSE que empieza con el estado completo")
    void eventsStreamStartsWithState() throws Exception {
        String host = register("host");
        String code = create(host);

        MvcResult result = as(host, get(room(code, "events")))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("event:state").contains("\"host\":true");
    }

    private void ticks(int count) {
        for (int i = 0; i < count; i++) {
            service.tick();
        }
    }

    private static String room(String code, String action) {
        return "/api/v1/updown-rooms/" + code + "/" + action;
    }

    private String create(String token) throws Exception {
        String body = as(token, post("/api/v1/updown-rooms"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.phase").value("LOBBY"))
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("code").asString();
    }

    private ResultActions vote(String token, String code, String choice, int bet) throws Exception {
        return as(token, post(room(code, "votes"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"choice\":\"" + choice + "\",\"bet\":" + bet + "}"));
    }

    private JsonNode view(String token, String code) throws Exception {
        return JSON.readTree(as(token, get("/api/v1/updown-rooms/" + code))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private ResultActions as(String token, MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder.header("Authorization", "Bearer " + token));
    }

    /** Registra a alguien con el nombre capitalizado ("ana" -> "Ana") y devuelve su token. */
    private String register(String user) throws Exception {
        String name = Character.toUpperCase(user.charAt(0)) + user.substring(1);
        String body = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + " Prueba\",\"email\":\"" + user
                                + "@utec.edu.pe\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("accessToken").asString();
    }

}
