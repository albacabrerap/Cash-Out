package com.checkout.backend.market.alpaca;

import com.checkout.backend.market.MarketDataClient.ProviderAsset;
import com.checkout.backend.market.MarketDataClient.ProviderBar;
import com.checkout.backend.market.MarketDataClient.ProviderSnapshot;
import com.checkout.backend.market.MarketDataUnavailableException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * El cliente de Alpaca contra respuestas grabadas con la forma real de la API,
 * sin red. Lo que cubre es la traduccion: URLs, cabeceras, lotes, paginas y
 * fechas.
 */
class AlpacaMarketDataClientTest {

    private static final String DATA = "https://data.test";
    private static final String TRADING = "https://trading.test";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    private AlpacaMarketDataClient client(String key, String secret) {
        return new AlpacaMarketDataClient(
                new AlpacaProperties(key, secret, URI.create(DATA), URI.create(TRADING), "iex", Duration.ofSeconds(5)),
                builder);
    }

    @Test
    @DisplayName("El catalogo sale de la API de trading, autenticado con las cabeceras de Alpaca")
    void listsUsEquities() {
        server.expect(requestTo(TRADING + "/v2/assets?status=active&asset_class=us_equity"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("APCA-API-KEY-ID", "clave"))
                .andExpect(header("APCA-API-SECRET-KEY", "secreto"))
                .andRespond(withSuccess("""
                        [{"symbol":"AAPL","name":"Apple Inc. Common Stock","exchange":"NASDAQ","tradable":true,
                          "class":"us_equity","status":"active"},
                         {"symbol":"ZZZ","name":"Halted Corp","exchange":"OTC","tradable":false}]
                        """, MediaType.APPLICATION_JSON));

        List<ProviderAsset> assets = client("clave", "secreto").listUsEquities();

        assertThat(assets).containsExactly(
                new ProviderAsset("AAPL", "Apple Inc. Common Stock", "NASDAQ", true),
                new ProviderAsset("ZZZ", "Halted Corp", "OTC", false));
        server.verify();
    }

    @Test
    @DisplayName("Los precios se piden en lotes y se omiten los simbolos sin operaciones")
    void snapshotsInBatches() {
        List<String> symbols = IntStream.range(0, AlpacaMarketDataClient.SNAPSHOT_BATCH + 1)
                .mapToObj(i -> "S" + i).toList();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(DATA + "/v2/stocks/snapshots?symbols=S0,S1,")))
                .andExpect(requestTo(org.hamcrest.Matchers.containsString(",S199&feed=iex")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(DATA + "/v2/stocks/snapshots?symbols=S200&feed=iex"))
                .andRespond(withSuccess("""
                        {"AAPL":{"latestTrade":{"p":333.75,"t":"2026-10-02T19:59:59.099Z"},
                                 "prevDailyBar":{"c":330.44}},
                         "NOTRADE":{"dailyBar":{"c":1.0}}}
                        """, MediaType.APPLICATION_JSON));

        Map<String, ProviderSnapshot> snapshots = client("k", "s").snapshots(symbols);

        assertThat(snapshots).containsOnlyKeys("AAPL");
        ProviderSnapshot aapl = snapshots.get("AAPL");
        assertThat(aapl.lastPrice()).isEqualByComparingTo("333.75");
        assertThat(aapl.previousClose()).isEqualByComparingTo("330.44");
        assertThat(aapl.lastTradeAt()).isNotNull();
        server.verify();
    }

    @Test
    @DisplayName("El historico sigue las paginas y fecha cada barra en el dia de Nueva York")
    void dailyBarsFollowPagesAndUseMarketDate() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(DATA + "/v2/stocks/bars?symbols=BRK.B&timeframe=1Day")))
                .andExpect(queryParam("adjustment", "all"))
                .andRespond(withSuccess("""
                        {"bars":{"BRK.B":[{"t":"2026-01-05T05:00:00Z","c":470.10},
                                          {"t":"2026-07-01T04:00:00Z","c":481.25}]},
                         "next_page_token":"abc"}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("page_token=abc")))
                .andRespond(withSuccess("""
                        {"bars":{"BRK.B":[{"t":"2026-10-02T04:00:00Z","c":490.00}]},"next_page_token":null}
                        """, MediaType.APPLICATION_JSON));

        List<ProviderBar> bars = client("k", "s").dailyBars("BRK.B", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 2));

        assertThat(bars).extracting(ProviderBar::date).containsExactly(
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 10, 2));
        assertThat(bars).extracting(ProviderBar::close)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("470.10"), new BigDecimal("481.25"), new BigDecimal("490.00"));
        server.verify();
    }

    @Test
    @DisplayName("Credenciales rechazadas salen como proveedor no disponible, sin filtrar detalles")
    void rejectedCredentialsAreUnavailable() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(TRADING)))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"message\":\"forbidden.\"}"));

        assertThatThrownBy(() -> client("k", "s").listUsEquities())
                .isInstanceOf(MarketDataUnavailableException.class)
                .hasMessageNotContaining("trading.test")
                .hasMessageNotContaining("forbidden");
    }

    @Test
    @DisplayName("Sin claves no se hace ningun pedido")
    void unconfiguredNeverCallsTheProvider() {
        assertThatThrownBy(() -> client("", "").snapshots(List.of("AAPL")))
                .isInstanceOf(MarketDataUnavailableException.class)
                .hasMessageContaining("no esta configurado");
        server.verify();
    }

    @Test
    @DisplayName("toString no imprime las claves")
    void propertiesNeverPrintSecrets() {
        AlpacaProperties properties = new AlpacaProperties("clave-real", "secreto-real", null, null, null, null);
        assertThat(properties.toString()).doesNotContain("clave-real").doesNotContain("secreto-real");
    }
}
