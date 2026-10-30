package com.checkout.backend.portfoliogame;

import com.checkout.backend.market.MarketDataClient;
import com.checkout.backend.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** El juego de punta a punta: tecnologia sube todos los dias, energia baja y el resto no se mueve. */
@SpringBootTest
@AutoConfigureMockMvc
class PortfolioGameApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "Secreto123!";

    static class SectorMarket implements MarketDataClient {
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
            double daily = switch (symbol) {
                case "XLK" -> 1.002;
                case "XLE" -> 0.999;
                case "SPY" -> 1.001;
                default -> 1;
            };
            List<ProviderBar> bars = new ArrayList<>();
            double price = 100;
            for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
                if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                    bars.add(new ProviderBar(day, BigDecimal.valueOf(price)));
                    price *= daily;
                }
            }
            return bars;
        }
    }

    @TestConfiguration
    static class SectorMarketConfig {
        @Bean
        @Primary
        SectorMarket sectorMarket() {
            return new SectorMarket();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        DatabaseCleaner.clean(jdbc);
    }

    @Test
    @DisplayName("Partida completa: con una etapa gana el valor y desde la segunda el rendimiento ajustado por riesgo")
    void fullGame() throws Exception {
        String host = register("host");
        String ana = register("ana");
        String beto = register("beto");
        String carla = register("carla");
        String code = create(host);
        for (String player : List.of(ana, beto, carla)) {
            as(player, post(room(code, "join"))).andExpect(status().isOk());
        }

        allocate(ana, code, "{\"tech\":100}").andExpect(jsonPath("$.me.ready").value(true));
        allocate(beto, code, "{\"energy\":100}").andExpect(status().isOk());
        allocate(carla, code, "{\"staples\":50,\"bonds\":50}").andExpect(status().isOk());
        JsonNode lobby = view(beto, code);
        assertThat(lobby.get("ranking").get(0).get("weights").isNull()).isTrue();
        assertThat(lobby.get("me").get("weights").get("energy").asInt()).isEqualTo(100);

        as(host, post(room(code, "start")))
                .andExpect(jsonPath("$.phase").value("RUNNING"))
                .andExpect(jsonPath("$.step").value(1))
                .andExpect(jsonPath("$.stages[0].revealed").value(true))
                .andExpect(jsonPath("$.stages[1].period").doesNotExist())
                .andExpect(jsonPath("$.ranking[0].result.risk").doesNotExist());
        JsonNode first = view(host, code);
        assertThat(first.get("ranking").findValuesAsString("name")).containsExactly("Ana", "Carla", "Beto");
        assertThat(first.get("ranking").get(2).get("weights").get("energy").asInt()).isEqualTo(100);
        assertThat(first.get("ranking").get(1).get("result").get("returnPercent").asDouble()).isZero();

        as(host, post(room(code, "next"))).andExpect(jsonPath("$.step").value(2));
        JsonNode second = view(ana, code);
        assertThat(second.get("ranking").findValuesAsString("name")).containsExactly("Ana", "Carla", "Beto");
        assertThat(second.get("me").get("result").get("score").asDouble()).isPositive();
        assertThat(second.get("benchmark").get("returnPercent").asDouble()).isPositive();

        as(host, post(room(code, "next")));
        as(host, post(room(code, "next")));
        JsonNode end = JSON.readTree(as(host, post(room(code, "next")))
                .andExpect(jsonPath("$.phase").value("FINISHED"))
                .andExpect(jsonPath("$.step").value(5))
                .andReturn().getResponse().getContentAsString());
        List<String> periods = end.get("stages").findValuesAsString("period");
        assertThat(periods).hasSize(5).doesNotHaveDuplicates();
        assertThat(periods.stream().map(period -> period.substring(period.length() - 4)).toList()).isSorted();
        as(host, post(room(code, "next"))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Reglas: el reparto suma 100 en pasos de 10, se arma antes de empezar y quien no termino queda fuera")
    void rulesAreEnforcedOnTheServer() throws Exception {
        String host = register("host");
        String ana = register("ana");
        String late = register("late");
        String idle = register("idle");
        String code = create(host);

        allocate(ana, code, "{\"tech\":100}").andExpect(status().isBadRequest());
        as(ana, post(room(code, "join")));
        as(idle, post(room(code, "join")));
        allocate(ana, code, "{\"tech\":90}").andExpect(status().isBadRequest());
        allocate(ana, code, "{\"tech\":85,\"gold\":15}").andExpect(status().isBadRequest());
        allocate(ana, code, "{\"crypto\":100}").andExpect(status().isBadRequest());
        as(host, post(room(code, "start"))).andExpect(status().isBadRequest());

        allocate(ana, code, "{\"tech\":60,\"gold\":40}").andExpect(status().isOk());
        as(ana, delete(room(code, "allocation"))).andExpect(jsonPath("$.me.ready").value(false));
        allocate(ana, code, "{\"tech\":60,\"gold\":40}").andExpect(status().isOk());
        as(ana, post(room(code, "start"))).andExpect(status().isForbidden());
        as(host, post(room(code, "start"))).andExpect(status().isOk());

        assertThat(view(idle, code).get("joined").asBoolean()).isFalse();
        as(late, post(room(code, "join"))).andExpect(status().isBadRequest());
        allocate(ana, code, "{\"gold\":100}").andExpect(status().isBadRequest());

        as(ana, post(room(code, "cancel"))).andExpect(status().isForbidden());
        as(host, post(room(code, "cancel"))).andExpect(status().isNoContent());
        as(ana, get("/api/v1/portfolio-rooms/" + code)).andExpect(status().isNotFound());
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

    private static String room(String code, String action) {
        return "/api/v1/portfolio-rooms/" + code + "/" + action;
    }

    private String create(String token) throws Exception {
        String body = as(token, post("/api/v1/portfolio-rooms"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.phase").value("LOBBY"))
                .andExpect(jsonPath("$.totalSteps").value(5))
                .andExpect(jsonPath("$.sectors.length()").value(8))
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("code").asString();
    }

    private ResultActions allocate(String token, String code, String weights) throws Exception {
        return as(token, put(room(code, "allocation"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"weights\":" + weights + "}"));
    }

    private JsonNode view(String token, String code) throws Exception {
        return JSON.readTree(as(token, get("/api/v1/portfolio-rooms/" + code))
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
