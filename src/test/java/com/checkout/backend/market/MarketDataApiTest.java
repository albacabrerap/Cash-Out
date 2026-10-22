package com.checkout.backend.market;

import com.checkout.backend.support.DatabaseCleaner;
import com.checkout.backend.user.model.Role;
import com.checkout.backend.user.model.User;
import com.checkout.backend.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Catalogo, precios e historico del proveedor, con un proveedor falso.
 *
 * El falso cuenta los pedidos: asi se comprueba que la cache evita llamar a
 * Alpaca en cada pantalla, que es lo que mantiene la cuenta gratuita bajo su
 * limite de pedidos por minuto.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MarketDataApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "Secreto123!";

    /** Proveedor en memoria. Cada test lo deja como necesita en setUp. */
    static class FakeMarket implements MarketDataClient {
        List<ProviderAsset> assets = new ArrayList<>();
        Map<String, BigDecimal> prices = new HashMap<>();
        List<ProviderBar> bars = new ArrayList<>();
        boolean failing;
        final AtomicInteger snapshotCalls = new AtomicInteger();
        final AtomicInteger barCalls = new AtomicInteger();

        @Override
        public List<ProviderAsset> listUsEquities() {
            failIfDown();
            return assets;
        }

        @Override
        public Map<String, ProviderSnapshot> snapshots(Collection<String> symbols) {
            snapshotCalls.incrementAndGet();
            failIfDown();
            Map<String, ProviderSnapshot> result = new HashMap<>();
            symbols.stream().filter(prices::containsKey).forEach(symbol -> result.put(symbol,
                    new ProviderSnapshot(symbol, prices.get(symbol), Instant.now(), new BigDecimal("100.00"))));
            return result;
        }

        @Override
        public List<ProviderBar> dailyBars(String symbol, LocalDate from, LocalDate to) {
            barCalls.incrementAndGet();
            failIfDown();
            return bars.stream().filter(bar -> !bar.date().isBefore(from) && !bar.date().isAfter(to)).toList();
        }

        private void failIfDown() {
            if (failing) {
                throw new MarketDataUnavailableException("Proveedor caido (test).");
            }
        }
    }

    @TestConfiguration
    static class FakeMarketConfig {
        @Bean
        @Primary
        FakeMarket fakeMarket() {
            return new FakeMarket();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FakeMarket market;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String userToken;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        market.assets = new ArrayList<>(List.of(
                new MarketDataClient.ProviderAsset("AAPL", "Apple Inc. Common Stock", "NASDAQ", true),
                new MarketDataClient.ProviderAsset("SPY", "SPDR S&P 500 ETF Trust", "ARCA", true),
                new MarketDataClient.ProviderAsset("AAPU", "Direxion Daily AAPL Bull 2X Shares", "NASDAQ", true),
                new MarketDataClient.ProviderAsset("HALT", "Halted Corp", "OTC", false)));
        market.prices = new HashMap<>(Map.of("AAPL", new BigDecimal("101.50")));
        market.bars = new ArrayList<>();
        market.failing = false;
        market.snapshotCalls.set(0);
        market.barCalls.set(0);

        userToken = token(register());
        userRepository.save(User.builder()
                .name("Alba")
                .email("alba@utec.edu.pe")
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .roles(java.util.EnumSet.of(Role.ADMIN))
                .build());
        adminToken = token(login("alba@utec.edu.pe"));
    }

    @AfterEach
    void cleanUp() {
        DatabaseCleaner.clean(jdbc);
    }

    // ------------------------------------------------------------------
    // Catalogo
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Sincronizar trae solo lo operable, reconoce los ETF y es idempotente")
    void syncImportsTradableAssets() throws Exception {
        as(adminToken, post("/api/v1/assets/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(4))
                .andExpect(jsonPath("$.created").value(3));

        as(userToken, get("/api/v1/assets").param("search", "spy"))
                .andExpect(jsonPath("$.content[0].symbol").value("SPY"))
                .andExpect(jsonPath("$.content[0].type").value("ETF"))
                .andExpect(jsonPath("$.content[0].source").value("ALPACA"))
                .andExpect(jsonPath("$.content[0].exchange").value("ARCA"));
        as(userToken, get("/api/v1/assets").param("search", "halt"))
                .andExpect(jsonPath("$.totalElements").value(0));

        as(adminToken, post("/api/v1/assets/sync"))
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.updated").value(0));
    }

    @Test
    @DisplayName("Un activo que el proveedor deja de listar se da de baja, no se borra")
    void delistedAssetsAreDeactivated() throws Exception {
        as(adminToken, post("/api/v1/assets/sync"));
        market.assets.removeIf(asset -> asset.symbol().equals("AAPU"));

        as(adminToken, post("/api/v1/assets/sync"))
                .andExpect(jsonPath("$.deactivated").value(1));
        as(userToken, get("/api/v1/assets").param("search", "aapu"))
                .andExpect(jsonPath("$.totalElements").value(0));
        as(adminToken, get("/api/v1/assets").param("search", "aapu").param("includeInactive", "true"))
                .andExpect(jsonPath("$.content[0].active").value(false));
    }

    @Test
    @DisplayName("Un activo cargado a mano con el mismo simbolo pasa al proveedor y conserva su id")
    void manualAssetIsAdoptedBySync() throws Exception {
        long id = createManual("AAPL", "Apple a mano");

        as(adminToken, post("/api/v1/assets/sync"))
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.updated").value(1));
        as(userToken, get("/api/v1/assets/" + id))
                .andExpect(jsonPath("$.source").value("ALPACA"))
                .andExpect(jsonPath("$.name").value("Apple Inc. Common Stock"));
    }

    @Test
    @DisplayName("La busqueda pone primero el simbolo exacto y despues los que empiezan igual")
    void searchRanksExactSymbolFirst() throws Exception {
        as(adminToken, post("/api/v1/assets/sync"));

        as(userToken, get("/api/v1/assets").param("search", "aapl"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].symbol").value("AAPL"))
                .andExpect(jsonPath("$.content[1].symbol").value("AAPU"));
        as(userToken, get("/api/v1/assets").param("size", "2"))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    @DisplayName("La letra deja solo los simbolos que empiezan con ella y se combina con la busqueda")
    void letterFiltersBySymbolStart() throws Exception {
        as(adminToken, post("/api/v1/assets/sync"));

        as(userToken, get("/api/v1/assets").param("letter", "a"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].symbol").value("AAPL"))
                .andExpect(jsonPath("$.content[1].symbol").value("AAPU"));
        as(userToken, get("/api/v1/assets").param("letter", "S"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].symbol").value("SPY"));
        // "aapl" tambien aparece en el nombre de AAPU, pero la letra S lo deja fuera.
        as(userToken, get("/api/v1/assets").param("letter", "s").param("search", "aapl"))
                .andExpect(jsonPath("$.totalElements").value(0));
        as(userToken, get("/api/v1/assets").param("letter", "ab"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Las cotizaciones de una pagina salen de un solo pedido al proveedor")
    void quotesForAPageInOneProviderCall() throws Exception {
        market.prices.put("SPY", new BigDecimal("520.00"));
        long aapl = syncAndFind("AAPL");
        long spy = syncAndFind("SPY");
        long aapu = syncAndFind("AAPU");

        as(userToken, get("/api/v1/assets/quotes").param("ids", aapl + "," + spy + "," + aapu + "," + aapl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.symbol == 'SPY')].price").value(520.00))
                .andExpect(jsonPath("$[?(@.symbol == 'AAPL')].changePercent").value(1.5));
        assertThat(market.snapshotCalls).hasValue(1);

        as(userToken, get("/api/v1/assets/quotes").param("ids", aapl + "," + spy)).andExpect(status().isOk());
        assertThat(market.snapshotCalls).hasValue(1);
    }

    @Test
    @DisplayName("No se piden mas de 100 cotizaciones a la vez")
    void tooManyQuotesIsRejected() throws Exception {
        String ids = java.util.stream.LongStream.rangeClosed(1, 101)
                .mapToObj(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        as(userToken, get("/api/v1/assets/quotes").param("ids", ids))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Ver los activos dados de baja y sincronizar son solo de ADMIN")
    void adminOnlyOperations() throws Exception {
        as(userToken, get("/api/v1/assets").param("includeInactive", "true"))
                .andExpect(status().isForbidden());
        as(userToken, post("/api/v1/assets/sync"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // Cotizacion
    // ------------------------------------------------------------------

    @Test
    @DisplayName("El precio se pide al proveedor y se reutiliza mientras esta fresco")
    void quoteIsCached() throws Exception {
        long aapl = syncAndFind("AAPL");

        as(userToken, get("/api/v1/assets/" + aapl + "/quote"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(101.50))
                .andExpect(jsonPath("$.previousClose").value(100.00))
                .andExpect(jsonPath("$.changePercent").value(1.5));
        as(userToken, get("/api/v1/assets/" + aapl + "/quote")).andExpect(status().isOk());

        assertThat(market.snapshotCalls).hasValue(1);
    }

    @Test
    @DisplayName("Si el proveedor cae se muestra el ultimo precio conocido; sin ninguno es 503")
    void providerOutage() throws Exception {
        long aapl = syncAndFind("AAPL");
        long spy = syncAndFind("SPY");
        as(userToken, get("/api/v1/assets/" + aapl + "/quote"));
        jdbc.update("UPDATE asset_quotes SET updated_at = updated_at - INTERVAL '1' HOUR");
        market.failing = true;

        as(userToken, get("/api/v1/assets/" + aapl + "/quote"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(101.50));
        as(userToken, get("/api/v1/assets/" + spy + "/quote"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("El precio de un activo del proveedor no se fija a mano")
    void providerPriceIsNotEditable() throws Exception {
        long aapl = syncAndFind("AAPL");

        as(adminToken, put("/api/v1/assets/" + aapl + "/quote")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"price\": 1.00}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Una orden se ejecuta al precio recien pedido al proveedor")
    void orderUsesFreshProviderPrice() throws Exception {
        long aapl = syncAndFind("AAPL");
        jdbc.update("UPDATE token_wallets SET token_balance = 1000");

        as(userToken, post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assetId\":" + aapl + ",\"side\":\"BUY\",\"quantity\":2,\"clientOrderId\":\""
                        + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.executionPrice").value(101.50));
        assertThat(market.snapshotCalls).hasValue(1);
    }

    @Test
    @DisplayName("No se opera con un precio viejo del proveedor si no se pudo refrescar")
    void staleProviderPriceBlocksTrading() throws Exception {
        long aapl = syncAndFind("AAPL");
        as(userToken, get("/api/v1/assets/" + aapl + "/quote"));
        jdbc.update("UPDATE asset_quotes SET updated_at = updated_at - INTERVAL '1' HOUR");
        jdbc.update("UPDATE token_wallets SET token_balance = 1000");
        market.failing = true;

        as(userToken, post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assetId\":" + aapl + ",\"side\":\"BUY\",\"quantity\":1,\"clientOrderId\":\""
                        + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Una orden que vale menos de 0.01 fichas se rechaza con su motivo, no con un error")
    void tinyOrderIsRejectedWithReason() throws Exception {
        long aapl = syncAndFind("AAPL");
        jdbc.update("UPDATE token_wallets SET token_balance = 1000");

        as(userToken, post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assetId\":" + aapl + ",\"side\":\"BUY\",\"quantity\":0.00001,\"clientOrderId\":\""
                        + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value(org.hamcrest.Matchers.containsString("0.01")))
                .andExpect(jsonPath("$.tokensMoved").value(0));
    }

    @Test
    @DisplayName("El portafolio se valora con precios refrescados, en un solo pedido al proveedor")
    void portfolioRefreshesPositionPrices() throws Exception {
        long aapl = syncAndFind("AAPL");
        long spy = syncAndFind("SPY");
        market.prices.put("SPY", new BigDecimal("500.00"));
        jdbc.update("UPDATE token_wallets SET token_balance = 10000");
        buy(aapl, "2");
        buy(spy, "1");

        market.prices.put("AAPL", new BigDecimal("110.00"));
        market.prices.put("SPY", new BigDecimal("510.00"));
        jdbc.update("UPDATE asset_quotes SET updated_at = updated_at - INTERVAL '1' HOUR");
        int callsBefore = market.snapshotCalls.get();

        as(userToken, get("/api/v1/portfolio"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulatedValue").value(730.00))
                .andExpect(jsonPath("$.positions[?(@.symbol == 'AAPL')].assetId").value(org.hamcrest.Matchers.contains((int) aapl)))
                .andExpect(jsonPath("$.positions[?(@.symbol == 'AAPL')].currentPrice").value(org.hamcrest.Matchers.contains(110.0)));
        assertThat(market.snapshotCalls.get() - callsBefore).isEqualTo(1);
    }

    @Test
    @DisplayName("Las ordenes traen el id del activo, para enlazar a su pagina")
    void ordersIncludeAssetId() throws Exception {
        long aapl = syncAndFind("AAPL");
        jdbc.update("UPDATE token_wallets SET token_balance = 1000");
        buy(aapl, "1");

        as(userToken, get("/api/v1/orders"))
                .andExpect(jsonPath("$.content[0].assetId").value(aapl));
    }

    // ------------------------------------------------------------------
    // Historico
    // ------------------------------------------------------------------

    @Test
    @DisplayName("El historico se descarga la primera vez y despues se sirve de la base")
    void historyIsBackfilledOnce() throws Exception {
        long aapl = syncAndFind("AAPL");
        LocalDate yesterday = LocalDate.now().minusDays(1);
        market.bars = new ArrayList<>(List.of(
                new MarketDataClient.ProviderBar(yesterday.minusDays(2), new BigDecimal("98.00")),
                new MarketDataClient.ProviderBar(yesterday.minusDays(1), new BigDecimal("99.00")),
                new MarketDataClient.ProviderBar(yesterday, new BigDecimal("100.00"))));

        as(userToken, get("/api/v1/assets/" + aapl + "/price-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[2].closePrice").value(100.00));
        as(userToken, get("/api/v1/assets/" + aapl + "/price-history"))
                .andExpect(jsonPath("$.length()").value(3));

        assertThat(market.barCalls).hasValue(1);
    }

    @Test
    @DisplayName("Si el proveedor cae, el historico guardado se sigue mostrando")
    void historySurvivesOutage() throws Exception {
        long aapl = syncAndFind("AAPL");
        market.bars = new ArrayList<>(List.of(
                new MarketDataClient.ProviderBar(LocalDate.now().minusDays(3), new BigDecimal("98.00"))));
        as(userToken, get("/api/v1/assets/" + aapl + "/price-history"));
        jdbc.update("UPDATE assets SET history_synced_at = NULL");
        market.failing = true;

        as(userToken, get("/api/v1/assets/" + aapl + "/price-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private long syncAndFind(String symbol) throws Exception {
        as(adminToken, post("/api/v1/assets/sync")).andExpect(status().isOk());
        JsonNode page = JSON.readTree(as(userToken, get("/api/v1/assets").param("search", symbol))
                .andReturn().getResponse().getContentAsString());
        return page.get("content").get(0).get("id").asLong();
    }

    private void buy(long assetId, String quantity) throws Exception {
        as(userToken, post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assetId\":" + assetId + ",\"side\":\"BUY\",\"quantity\":" + quantity
                        + ",\"clientOrderId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EXECUTED"));
    }

    private long createManual(String symbol, String name) throws Exception {
        String body = as(adminToken, post("/api/v1/assets")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"symbol\":\"" + symbol + "\",\"name\":\"" + name
                        + "\",\"type\":\"STOCK\",\"currency\":\"USD\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("id").asLong();
    }

    private ResultActions as(String token, MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder.header("Authorization", "Bearer " + token));
    }

    private String register() throws Exception {
        return mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ana\",\"email\":\"ana" + UUID.randomUUID()
                                + "@utec.edu.pe\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private String login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static String token(String authBody) throws Exception {
        return JSON.readTree(authBody).get("accessToken").asString();
    }
}
