package com.checkout.backend.news;

import com.checkout.backend.investment_portfolio.asset.model.Asset;
import com.checkout.backend.investment_portfolio.asset.model.AssetSource;
import com.checkout.backend.investment_portfolio.asset.model.AssetType;
import com.checkout.backend.investment_portfolio.asset.quote.model.AssetQuote;
import com.checkout.backend.investment_portfolio.asset.quote.repository.AssetQuoteRepository;
import com.checkout.backend.investment_portfolio.asset.repository.AssetRepository;
import com.checkout.backend.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El mural con un proveedor falso que anota que simbolos le piden. La cache del
 * servicio vive entre tests: los que piden los mismos simbolos usan su propio
 * token de pagina para no leer la de otro.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NewsApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static class FakeNews implements NewsClient {
        final List<List<String>> calls = new ArrayList<>();
        List<ProviderNews> items = List.of();

        @Override
        public NewsPage latest(Collection<String> symbols, String pageToken, int limit) {
            calls.add(List.copyOf(symbols));
            // La segunda pagina viene vacia, para que el ranking no cuente dos veces.
            return "segunda".equals(pageToken) ? new NewsPage(List.of(), null) : new NewsPage(items, "segunda");
        }
    }

    @TestConfiguration
    static class FakeNewsConfig {
        @Bean
        @Primary
        FakeNews fakeNews() {
            return new FakeNews();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FakeNews news;
    @Autowired private AssetRepository assetRepository;
    @Autowired private AssetQuoteRepository quoteRepository;

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        news.calls.clear();
        news.items = List.of(
                item(1, "BTCUSD", "ZZZZ", "AAPL"),
                item(2, "AAPL"),
                item(3, "AAPL", "BTCUSD"),
                item(4));
        assetRepository.save(Asset.builder().symbol("AAPL").name("Apple").type(AssetType.STOCK)
                .source(AssetSource.ALPACA).build());
        Asset btc = assetRepository.save(Asset.builder().symbol("BTC-USD").name("Bitcoin").type(AssetType.CRYPTO)
                .source(AssetSource.COINBASE).exchange("COINBASE").build());
        quoteRepository.save(AssetQuote.builder().asset(btc).price(new BigDecimal("81000"))
                .changePercent(new BigDecimal("1.5")).updatedAt(LocalDateTime.now()).build());
        token = register();
    }

    @AfterEach
    void cleanUp() {
        DatabaseCleaner.clean(jdbc);
    }

    @Test
    @DisplayName("Cada nota trae los activos del catalogo que nombra, con su variacion; los desconocidos se descartan")
    void itemsLinkCatalogAssets() throws Exception {
        as(get("/api/v1/news").param("pageToken", fresh()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextPageToken").value("segunda"))
                .andExpect(jsonPath("$.items[0].assets.length()").value(2))
                .andExpect(jsonPath("$.items[0].assets[0].symbol").value("BTC-USD"))
                .andExpect(jsonPath("$.items[0].assets[0].changePercent").value(1.5))
                .andExpect(jsonPath("$.items[0].assets[1].symbol").value("AAPL"))
                .andExpect(jsonPath("$.items[3].assets.length()").value(0));
        assertThat(news.calls).containsExactly(List.of());
    }

    @Test
    @DisplayName("Cripto pide los pares del catalogo con el nombre que usa Alpaca (BTCUSD)")
    void cryptoScopeUsesProviderSymbols() throws Exception {
        as(get("/api/v1/news").param("scope", "crypto").param("pageToken", fresh())).andExpect(status().isOk());
        assertThat(news.calls).containsExactly(List.of("BTCUSD"));
    }

    @Test
    @DisplayName("Mis inversiones pide solo lo que hay en el portafolio, y sin posiciones ni pregunta")
    void mineScopeUsesPositions() throws Exception {
        as(get("/api/v1/news").param("scope", "mine"))
                .andExpect(jsonPath("$.items.length()").value(0));
        assertThat(news.calls).isEmpty();

        Long portfolioId = jdbc.queryForObject("SELECT id FROM investment_portfolios", Long.class);
        Long aapl = assetRepository.findBySymbolIgnoreCase("AAPL").orElseThrow().getId();
        jdbc.update("INSERT INTO portfolio_positions (portfolio_id, asset_id, quantity, average_cost, updated_at)"
                + " VALUES (?, ?, 1, 200, CURRENT_TIMESTAMP)", portfolioId, aapl);

        as(get("/api/v1/news").param("scope", "mine")).andExpect(jsonPath("$.items.length()").value(4));
        assertThat(news.calls).containsExactly(List.of("AAPL"));
    }

    @Test
    @DisplayName("Filtrar por simbolo pide ese activo; uno que no existe es 404")
    void symbolFilter() throws Exception {
        as(get("/api/v1/news").param("symbol", "btc-usd").param("pageToken", fresh())).andExpect(status().isOk());
        assertThat(news.calls).containsExactly(List.of("BTCUSD"));

        as(get("/api/v1/news").param("symbol", "NOPE")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("El activo filtrado va primero en cada nota, aunque Alpaca lo nombre despues")
    void filteredAssetComesFirst() throws Exception {
        as(get("/api/v1/news").param("symbol", "AAPL").param("pageToken", fresh()))
                .andExpect(jsonPath("$.items[0].assets[0].symbol").value("AAPL"))
                .andExpect(jsonPath("$.items[0].assets[1].symbol").value("BTC-USD"));
    }

    @Test
    @DisplayName("La misma pagina se sirve de la cache: Alpaca recibe un solo pedido")
    void cachedPagesAreReused() throws Exception {
        String page = fresh();
        as(get("/api/v1/news").param("pageToken", page)).andExpect(status().isOk());
        as(get("/api/v1/news").param("pageToken", page)).andExpect(status().isOk());

        assertThat(news.calls).hasSize(1);
    }

    @Test
    @DisplayName("Lo mas mencionado cuenta en cuantas notas aparece cada activo")
    void trendingCountsMentions() throws Exception {
        as(get("/api/v1/news/trending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("AAPL"))
                .andExpect(jsonPath("$[0].mentions").value(3))
                .andExpect(jsonPath("$[1].symbol").value("BTC-USD"))
                .andExpect(jsonPath("$[1].mentions").value(2));
    }

    @Test
    @DisplayName("Un alcance que no existe es 400")
    void unknownScopeIsRejected() throws Exception {
        as(get("/api/v1/news").param("scope", "todo")).andExpect(status().isBadRequest());
    }

    private static NewsClient.ProviderNews item(long id, String... symbols) {
        Instant published = Instant.parse("2026-10-18T16:00:00Z").minusSeconds(id * 60);
        return new NewsClient.ProviderNews(id, "Headline " + id, "Summary " + id, "benzinga", "Ana",
                "https://www.benzinga.com/" + id, published, published, null, List.of(symbols));
    }

    private static String fresh() {
        return UUID.randomUUID().toString();
    }

    private ResultActions as(MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder.header("Authorization", "Bearer " + token));
    }

    private String register() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ana\",\"email\":\"ana" + UUID.randomUUID()
                                + "@utec.edu.pe\",\"password\":\"Secreto123!\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("accessToken").asString();
    }

}
