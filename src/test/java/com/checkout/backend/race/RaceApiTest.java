package com.checkout.backend.race;

import com.checkout.backend.market.CryptoDataClient;
import com.checkout.backend.market.MarketDataClient.ProviderBar;
import com.checkout.backend.market.MarketDataClient.ProviderSnapshot;
import com.checkout.backend.race.service.RaceService;
import com.checkout.backend.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La carrera de punta a punta con precios controlados: estable en 100, cae a
 * 80 a la tercera parte y sube a 130 al final. El reloj se avanza a mano.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RaceApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "Secreto123!";

    /** Las velas de cualquier tramo: 100, luego 80 desde el minuto 120 y 130 desde el 240. */
    static class FakeCrypto implements CryptoDataClient {
        @Override
        public List<Candle> minuteCandles(String productId, Instant from, Instant to) {
            List<Candle> candles = new ArrayList<>();
            int minute = 0;
            for (Instant time = from; !time.isAfter(to); time = time.plus(Duration.ofMinutes(1)), minute++) {
                BigDecimal price = BigDecimal.valueOf(minute < 120 ? 100 : minute < 240 ? 80 : 130);
                candles.add(new Candle(time, price, price, price, price));
            }
            return candles;
        }

        @Override
        public List<CryptoProduct> products(Collection<String> productIds) {
            return List.of();
        }

        @Override
        public ProviderSnapshot snapshot(String productId) {
            return null;
        }

        @Override
        public List<ProviderBar> dailyBars(String productId, LocalDate from, LocalDate to) {
            return List.of();
        }
    }

    @TestConfiguration
    static class FakeCryptoConfig {
        @Bean
        @Primary
        FakeCrypto fakeCrypto() {
            return new FakeCrypto();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RaceService raceService;

    /**
     * Las carreras viven en memoria y sobreviven al test; la base no, y sus ids
     * vuelven a empezar. Se terminan antes de limpiar para que una carrera vieja
     * no pague, en el test siguiente, a un id que ya es de otra persona.
     */
    @AfterEach
    void cleanUp() {
        ticks(12 + 360 + 1);
        DatabaseCleaner.clean(jdbc);
    }

    @Test
    @DisplayName("Carrera completa: gana quien compro en la caida y el pozo se reparte 50/30/20")
    void fullRacePaysThePot() throws Exception {
        String host = register("host");
        String holder = register("holder");
        String panic = register("panic");
        String idle = register("idle");
        String dip = register("dip");
        jdbc.update("UPDATE token_wallets SET token_balance = 100");

        String code = create(host, 20);
        for (String player : List.of(holder, panic, idle, dip)) {
            as(player, post("/api/v1/race-rooms/" + code + "/join")).andExpect(status().isOk());
        }
        assertThat(balance("holder")).isEqualByComparingTo("80");
        as(host, get("/api/v1/race-rooms/" + code)).andExpect(jsonPath("$.pot").value(80));

        as(host, post("/api/v1/race-rooms/" + code + "/start")).andExpect(jsonPath("$.phase").value("COUNTDOWN"));
        ticks(12);
        trade(holder, code, "BUY", "1");
        trade(panic, code, "BUY", "1");
        ticks(130);
        trade(panic, code, "SELL", "1");
        trade(dip, code, "BUY", "1");
        ticks(240);

        JsonNode end = view(host, code);
        assertThat(end.get("phase").asString()).isEqualTo("FINISHED");
        assertThat(end.get("scenario").get("coinName").asString()).isNotBlank();
        assertThat(end.get("ranking").findValuesAsString("name")).containsExactly("Dip", "Holder", "Idle", "Panic");
        assertThat(end.get("ranking").get(0).get("returnPercent").asDouble()).isEqualTo(62.5);

        assertThat(balance("dip")).isEqualByComparingTo("120");
        assertThat(balance("holder")).isEqualByComparingTo("104");
        assertThat(balance("idle")).isEqualByComparingTo("96");
        assertThat(balance("panic")).isEqualByComparingTo("80");
    }

    @Test
    @DisplayName("Con menos de tres jugadores no hay premio: se devuelve la cuota")
    void fewPlayersGetTheirFeeBack() throws Exception {
        String host = register("host");
        String one = register("one");
        String two = register("two");
        jdbc.update("UPDATE token_wallets SET token_balance = 100");

        String code = create(host, 10);
        as(one, post("/api/v1/race-rooms/" + code + "/join"));
        as(two, post("/api/v1/race-rooms/" + code + "/join"));
        assertThat(balance("one")).isEqualByComparingTo("90");

        as(host, post("/api/v1/race-rooms/" + code + "/start"));
        ticks(12 + 360);

        assertThat(view(host, code).get("phase").asString()).isEqualTo("FINISHED");
        assertThat(balance("one")).isEqualByComparingTo("100");
        assertThat(balance("two")).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("Reglas: solo el anfitrion empieza, sin fichas no se entra y no se opera fuera de carrera")
    void rulesAreEnforcedOnTheServer() throws Exception {
        String host = register("host");
        String player = register("player");
        String late = register("late");
        String poor = register("poor");
        jdbc.update("UPDATE token_wallets SET token_balance = 100");
        jdbc.update("UPDATE token_wallets SET token_balance = 5 WHERE user_id = "
                + "(SELECT id FROM users WHERE email = 'poor@utec.edu.pe')");

        as(host, post("/api/v1/race-rooms").contentType(MediaType.APPLICATION_JSON).content("{\"entryFee\":15}"))
                .andExpect(status().isBadRequest());
        String code = create(host, 10);

        as(poor, post("/api/v1/race-rooms/" + code + "/join")).andExpect(status().isBadRequest());
        assertThat(view(poor, code).get("joined").asBoolean()).isFalse();
        assertThat(balance("poor")).isEqualByComparingTo("5");

        as(player, post("/api/v1/race-rooms/" + code + "/join")).andExpect(status().isOk());
        as(player, post("/api/v1/race-rooms/" + code + "/join")).andExpect(status().isOk());
        assertThat(balance("player")).isEqualByComparingTo("90");

        tradeRequest(player, code, "BUY", "1").andExpect(status().isBadRequest());
        as(player, post("/api/v1/race-rooms/" + code + "/start")).andExpect(status().isForbidden());

        as(host, post("/api/v1/race-rooms/" + code + "/start")).andExpect(status().isOk());
        as(late, post("/api/v1/race-rooms/" + code + "/join")).andExpect(status().isBadRequest());
        ticks(12);
        tradeRequest(player, code, "BUY", "0.3").andExpect(status().isBadRequest());
        tradeRequest(player, code, "SELL", "1").andExpect(status().isBadRequest());
        tradeRequest(player, code, "BUY", "0.5").andExpect(status().isOk())
                .andExpect(jsonPath("$.me.investedPercent").value(50.0));

        as(host, get("/api/v1/race-rooms/ZZZZZZ")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("El anfitrion cancela antes de empezar y cada jugador recupera su cuota")
    void cancelRefunds() throws Exception {
        String host = register("host");
        String player = register("player");
        jdbc.update("UPDATE token_wallets SET token_balance = 100");

        String code = create(host, 50);
        as(player, post("/api/v1/race-rooms/" + code + "/join"));
        assertThat(balance("player")).isEqualByComparingTo("50");

        as(player, post("/api/v1/race-rooms/" + code + "/cancel")).andExpect(status().isForbidden());
        as(host, post("/api/v1/race-rooms/" + code + "/cancel")).andExpect(status().isNoContent());

        assertThat(balance("player")).isEqualByComparingTo("100");
        as(player, get("/api/v1/race-rooms/" + code)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Una sala que quedo abierta en un reinicio se cancela y devuelve la cuota")
    void abandonedRoomsAreRefunded() throws Exception {
        String host = register("host");
        String player = register("player");
        jdbc.update("UPDATE token_wallets SET token_balance = 100");
        String code = create(host, 20);
        as(player, post("/api/v1/race-rooms/" + code + "/join"));

        raceService.cancelAbandoned();

        assertThat(balance("player")).isEqualByComparingTo("100");
        assertThat(jdbc.queryForObject("SELECT status FROM race_rooms WHERE code = ?", String.class, code))
                .isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("La sala en vivo abre un SSE que empieza con el estado completo")
    void eventsStreamStartsWithState() throws Exception {
        String host = register("host");
        String code = create(host, 0);

        MvcResult result = as(host, get("/api/v1/race-rooms/" + code + "/events"))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("event:state").contains("\"host\":true");
    }

    private void ticks(int count) {
        for (int i = 0; i < count; i++) {
            raceService.tick();
        }
    }

    private String create(String token, int fee) throws Exception {
        String body = as(token, post("/api/v1/race-rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"entryFee\":" + fee + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.phase").value("LOBBY"))
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("code").asString();
    }

    private void trade(String token, String code, String side, String fraction) throws Exception {
        tradeRequest(token, code, side, fraction).andExpect(status().isOk());
    }

    private ResultActions tradeRequest(String token, String code, String side, String fraction) throws Exception {
        return as(token, post("/api/v1/race-rooms/" + code + "/trades")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"side\":\"" + side + "\",\"fraction\":" + fraction + "}"));
    }

    private JsonNode view(String token, String code) throws Exception {
        return JSON.readTree(as(token, get("/api/v1/race-rooms/" + code))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private BigDecimal balance(String emailUser) {
        return jdbc.queryForObject("SELECT w.token_balance FROM token_wallets w JOIN users u ON u.id = w.user_id"
                + " WHERE u.email = ?", BigDecimal.class, emailUser + "@utec.edu.pe");
    }

    private ResultActions as(String token, MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder.header("Authorization", "Bearer " + token));
    }

    /** Registra a alguien con el nombre capitalizado ("dip" -> "Dip") y devuelve su token. */
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
