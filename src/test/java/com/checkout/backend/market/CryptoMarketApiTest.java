package com.checkout.backend.market;

import com.checkout.backend.investment_portfolio.asset.model.Asset;
import com.checkout.backend.investment_portfolio.asset.model.AssetType;
import com.checkout.backend.investment_portfolio.asset.repository.AssetRepository;
import com.checkout.backend.market.MarketDataClient.ProviderBar;
import com.checkout.backend.market.MarketDataClient.ProviderSnapshot;
import com.checkout.backend.market.live.LivePriceService;
import com.checkout.backend.market.service.CryptoCatalogSyncService;
import com.checkout.backend.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * Cripto de punta a punta con un Coinbase falso: catalogo, precio en vivo,
 * compra fraccionaria, historico por dia UTC y la grafica en vivo por SSE.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CryptoMarketApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "Secreto123!";

    static class FakeCrypto implements CryptoDataClient {
        Map<String, CryptoProduct> products = new HashMap<>();
        Map<String, ProviderSnapshot> snapshots = new HashMap<>();
        List<ProviderBar> bars = new ArrayList<>();
        final AtomicInteger snapshotCalls = new AtomicInteger();

        @Override
        public List<CryptoProduct> products(Collection<String> productIds) {
            return productIds.stream().map(products::get).filter(Objects::nonNull).toList();
        }

        @Override
        public ProviderSnapshot snapshot(String productId) {
            snapshotCalls.incrementAndGet();
            ProviderSnapshot snapshot = snapshots.get(productId);
            if (snapshot == null) {
                throw new MarketDataUnavailableException("Sin precio (test).");
            }
            return snapshot;
        }

        @Override
        public List<ProviderBar> dailyBars(String productId, LocalDate from, LocalDate to) {
            return bars.stream().filter(bar -> !bar.date().isBefore(from) && !bar.date().isAfter(to)).toList();
        }

        @Override
        public List<Candle> minuteCandles(String productId, Instant from, Instant to) {
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
    @Autowired private FakeCrypto crypto;
    @Autowired private CryptoCatalogSyncService cryptoSync;
    @Autowired private LivePriceService livePrices;
    @Autowired private AssetRepository assetRepository;

    private String userToken;

    @BeforeEach
    void setUp() throws Exception {
        crypto.products = new HashMap<>(Map.of(
                "BTC-USD", new CryptoDataClient.CryptoProduct("BTC-USD", "Bitcoin", true),
                "ETH-USD", new CryptoDataClient.CryptoProduct("ETH-USD", "Ethereum", true),
                "DOGE-USD", new CryptoDataClient.CryptoProduct("DOGE-USD", "Dogecoin", false)));
        crypto.snapshots = new HashMap<>(Map.of(
                "BTC-USD", new ProviderSnapshot("BTC-USD", new BigDecimal("60500"), null, new BigDecimal("60000")),
                "ETH-USD", new ProviderSnapshot("ETH-USD", new BigDecimal("2500"), null, new BigDecimal("2400"))));
        crypto.bars = new ArrayList<>();
        crypto.snapshotCalls.set(0);

        userToken = token(register());
        cryptoSync.sync();
    }

    @AfterEach
    void cleanUp() {
        DatabaseCleaner.clean(jdbc);
    }

    @Test
    @DisplayName("Entran los pares operables como CRYPTO de COINBASE; uno que sale de la lista se da de baja")
    void syncAddsConfiguredPairs() throws Exception {
        as(get("/api/v1/assets").param("search", "btc"))
                .andExpect(jsonPath("$.content[0].symbol").value("BTC-USD"))
                .andExpect(jsonPath("$.content[0].name").value("Bitcoin"))
                .andExpect(jsonPath("$.content[0].type").value("CRYPTO"))
                .andExpect(jsonPath("$.content[0].source").value("COINBASE"));
        as(get("/api/v1/assets").param("search", "doge"))
                .andExpect(jsonPath("$.totalElements").value(0));
        assertThat(cryptoSync.sync().created()).isZero();

        crypto.products.remove("ETH-USD");
        assertThat(cryptoSync.sync().deactivated()).isEqualTo(1);
        as(get("/api/v1/assets").param("search", "eth"))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("Con el stream conectado, la cotizacion sigue al precio en vivo sin volver a preguntar a Coinbase")
    void quoteFollowsLivePrice() throws Exception {
        long btc = find("BTC-USD");
        livePrices.markAlive();
        livePrices.record("BTC-USD", new BigDecimal("61000"), Instant.now());

        JsonNode quote = quote(btc);
        assertThat(quote.get("price").decimalValue()).isEqualByComparingTo("61000");
        assertThat(quote.get("previousClose").decimalValue()).isEqualByComparingTo("60000");

        livePrices.record("BTC-USD", new BigDecimal("61500"), Instant.now());
        assertThat(quote(btc).get("price").decimalValue()).isEqualByComparingTo("61500");
        assertThat(crypto.snapshotCalls).hasValue(1);
    }

    @Test
    @DisplayName("Sin precio en vivo, la cotizacion sale de la API de Coinbase con la variacion de 24 h")
    void quoteFallsBackToProvider() throws Exception {
        JsonNode quote = quote(find("ETH-USD"));

        assertThat(quote.get("price").decimalValue()).isEqualByComparingTo("2500");
        assertThat(quote.get("changePercent").decimalValue()).isEqualByComparingTo("4.1667");
    }

    @Test
    @DisplayName("Se compra una fraccion de bitcoin al precio en vivo")
    void buysFractionAtLivePrice() throws Exception {
        long btc = find("BTC-USD");
        livePrices.markAlive();
        livePrices.record("BTC-USD", new BigDecimal("62000"), Instant.now());
        jdbc.update("UPDATE token_wallets SET token_balance = 1000");

        String body = as(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assetId\":" + btc + ",\"side\":\"BUY\",\"quantity\":0.001,\"clientOrderId\":\""
                        + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andReturn().getResponse().getContentAsString();

        JsonNode order = JSON.readTree(body);
        assertThat(order.get("executionPrice").decimalValue()).isEqualByComparingTo("62000");
        assertThat(order.get("tokensMoved").decimalValue()).isEqualByComparingTo("62");
    }

    @Test
    @DisplayName("El historico de cripto llega hasta ayer en UTC")
    void historyUsesUtcDays() throws Exception {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        crypto.bars = List.of(
                new ProviderBar(yesterday.minusDays(1), new BigDecimal("60000")),
                new ProviderBar(yesterday, new BigDecimal("61000")));

        as(get("/api/v1/assets/" + find("BTC-USD") + "/price-history")
                .param("from", yesterday.minusDays(5).toString())
                .param("to", yesterday.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("La grafica en vivo abre un SSE para la cripto y se rechaza para una accion")
    void liveStreamOnlyForCrypto() throws Exception {
        MvcResult result = as(get("/api/v1/assets/" + find("BTC-USD") + "/live"))
                .andExpect(request().asyncStarted())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("event:history");

        Asset stock = assetRepository.save(Asset.builder()
                .symbol("AAPL").name("Apple").type(AssetType.STOCK).build());
        as(get("/api/v1/assets/" + stock.getId() + "/live"))
                .andExpect(status().isBadRequest());
    }

    private long find(String symbol) {
        return assetRepository.findBySymbolIgnoreCase(symbol).orElseThrow().getId();
    }

    private JsonNode quote(long assetId) throws Exception {
        return JSON.readTree(as(get("/api/v1/assets/" + assetId + "/quote"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private ResultActions as(MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder.header("Authorization", "Bearer " + userToken));
    }

    private String register() throws Exception {
        return mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ana\",\"email\":\"ana" + UUID.randomUUID()
                                + "@utec.edu.pe\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private static String token(String authBody) {
        return JSON.readTree(authBody).get("accessToken").asString();
    }

}
