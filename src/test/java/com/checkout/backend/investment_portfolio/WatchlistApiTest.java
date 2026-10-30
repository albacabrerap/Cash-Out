package com.checkout.backend.investment_portfolio;

import com.checkout.backend.investment_portfolio.asset.model.Asset;
import com.checkout.backend.investment_portfolio.asset.model.AssetSource;
import com.checkout.backend.investment_portfolio.asset.model.AssetType;
import com.checkout.backend.investment_portfolio.asset.repository.AssetRepository;
import com.checkout.backend.support.DatabaseCleaner;
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

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class WatchlistApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AssetRepository assets;

    private Long aapl;
    private Long spy;

    @BeforeEach
    void setUp() {
        aapl = asset("AAPL", "Apple Inc.", AssetType.STOCK, true);
        spy = asset("SPY", "SPDR S&P 500 ETF", AssetType.ETF, true);
    }

    @AfterEach
    void cleanUp() {
        DatabaseCleaner.clean(jdbc);
    }

    @Test
    @DisplayName("Agregar y quitar son idempotentes; la lista va del mas reciente al mas antiguo")
    void addAndRemoveAreIdempotent() throws Exception {
        String token = register();

        as(token, put("/api/v1/watchlist/" + aapl)).andExpect(status().isNoContent());
        as(token, put("/api/v1/watchlist/" + spy)).andExpect(status().isNoContent());
        as(token, put("/api/v1/watchlist/" + aapl)).andExpect(status().isNoContent());

        as(token, get("/api/v1/watchlist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].symbol").value("SPY"))
                .andExpect(jsonPath("$[1].symbol").value("AAPL"));

        as(token, delete("/api/v1/watchlist/" + aapl)).andExpect(status().isNoContent());
        as(token, delete("/api/v1/watchlist/" + aapl)).andExpect(status().isNoContent());
        as(token, get("/api/v1/watchlist")).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    @DisplayName("Cada usuario ve solo sus favoritos")
    void favoritesBelongToTheirOwner() throws Exception {
        String ana = register();
        String beto = register();

        as(ana, put("/api/v1/watchlist/" + aapl)).andExpect(status().isNoContent());
        as(beto, delete("/api/v1/watchlist/" + aapl)).andExpect(status().isNoContent());

        as(beto, get("/api/v1/watchlist")).andExpect(jsonPath("$", hasSize(0)));
        as(ana, get("/api/v1/watchlist")).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    @DisplayName("Un activo inexistente o dado de baja no se puede seguir")
    void unknownOrInactiveAssetIsNotFound() throws Exception {
        String token = register();
        Long delisted = asset("OLD", "Delisted Corp.", AssetType.STOCK, false);

        as(token, put("/api/v1/watchlist/999999")).andExpect(status().isNotFound());
        as(token, put("/api/v1/watchlist/" + delisted)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Sin sesion no hay favoritos")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/watchlist")).andExpect(status().isUnauthorized());
    }

    private Long asset(String symbol, String name, AssetType type, boolean active) {
        return assets.save(Asset.builder()
                .symbol(symbol)
                .name(name)
                .type(type)
                .currency("USD")
                .active(active)
                .source(AssetSource.MANUAL)
                .build()).getId();
    }

    private ResultActions as(String token, MockHttpServletRequestBuilder builder) throws Exception {
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
