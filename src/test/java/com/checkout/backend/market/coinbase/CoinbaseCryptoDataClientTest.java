package com.checkout.backend.market.coinbase;

import com.checkout.backend.market.CryptoDataClient.Candle;
import com.checkout.backend.market.CryptoDataClient.CryptoProduct;
import com.checkout.backend.market.MarketDataClient.ProviderBar;
import com.checkout.backend.market.MarketDataClient.ProviderSnapshot;
import com.checkout.backend.market.MarketDataUnavailableException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * El cliente de Coinbase contra respuestas con la forma real de la API, sin red:
 * pares, precios como texto, velas como numero y tramos de 300 velas.
 */
class CoinbaseCryptoDataClientTest {

    private static final String REST = "https://coinbase.test";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final CoinbaseCryptoDataClient client = new CoinbaseCryptoDataClient(
            new CoinbaseProperties(true, URI.create(REST), URI.create("wss://stream.test"),
                    List.of("BTC-USD"), Duration.ofSeconds(5)),
            builder);

    @Test
    @DisplayName("Los pares traen el nombre de su moneda; uno desconocido se omite y uno suspendido no es operable")
    void listsProducts() {
        server.expect(requestTo(REST + "/products/BTC-USD"))
                .andExpect(header("User-Agent", "checkout-backend"))
                .andRespond(withSuccess("""
                        {"id":"BTC-USD","base_currency":"BTC","quote_currency":"USD","status":"online","trading_disabled":false}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(REST + "/currencies/BTC"))
                .andRespond(withSuccess("{\"id\":\"BTC\",\"name\":\"Bitcoin\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(REST + "/products/LUNA-USD"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(REST + "/products/XYZ-USD"))
                .andRespond(withSuccess("""
                        {"id":"XYZ-USD","base_currency":"XYZ","status":"delisted","trading_disabled":true}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(REST + "/currencies/XYZ"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        List<CryptoProduct> products = client.products(List.of("BTC-USD", "LUNA-USD", "XYZ-USD"));

        assertThat(products).containsExactly(
                new CryptoProduct("BTC-USD", "Bitcoin", true),
                new CryptoProduct("XYZ-USD", "XYZ", false));
        server.verify();
    }

    @Test
    @DisplayName("El precio sale de las estadisticas de 24 h: ultimo precio y apertura como cierre anterior")
    void readsSnapshotFromStats() {
        server.expect(requestTo(REST + "/products/BTC-USD/stats"))
                .andRespond(withSuccess("""
                        {"open":"60000.00","high":"61200","low":"59800","last":"60500.50","volume":"1888.8"}
                        """, MediaType.APPLICATION_JSON));

        ProviderSnapshot snapshot = client.snapshot("BTC-USD");

        assertThat(snapshot.lastPrice()).isEqualByComparingTo("60500.50");
        assertThat(snapshot.previousClose()).isEqualByComparingTo("60000.00");
    }

    @Test
    @DisplayName("Las velas de un minuto se piden en tramos de 300 y salen en orden y sin repetidas")
    void paginatesMinuteCandles() {
        // 00:00, 00:01 y 05:00 del 12 de marzo de 2020, en segundos UTC.
        long midnight = 1583971200L;
        long oneMinute = midnight + 60;
        long five = midnight + 5 * 3600;

        server.expect(requestTo(startsWith(REST + "/products/BTC-USD/candles")))
                .andExpect(queryParam("granularity", "60"))
                .andExpect(queryParam("start", "2020-03-12T00:00:00Z"))
                .andExpect(queryParam("end", "2020-03-12T04:59:00Z"))
                .andRespond(withSuccess("[[" + oneMinute + ",1,3,2,2.5,9],[" + midnight + ",1,3,2,2.0,9]]",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(REST + "/products/BTC-USD/candles")))
                .andExpect(queryParam("start", "2020-03-12T05:00:00Z"))
                .andExpect(queryParam("end", "2020-03-12T05:00:00Z"))
                .andRespond(withSuccess("[[" + five + ",1,3,2,4.0,9],[" + oneMinute + ",1,3,2,2.5,9]]",
                        MediaType.APPLICATION_JSON));

        List<Candle> candles = client.minuteCandles("BTC-USD",
                Instant.parse("2020-03-12T00:00:00Z"), Instant.parse("2020-03-12T05:00:00Z"));

        assertThat(candles).extracting(Candle::time).containsExactly(
                Instant.ofEpochSecond(midnight), Instant.ofEpochSecond(oneMinute), Instant.ofEpochSecond(five));
        assertThat(candles.get(0).close()).isEqualByComparingTo("2.0");
        assertThat(candles.get(0).high()).isEqualByComparingTo("3");
        assertThat(candles.get(0).low()).isEqualByComparingTo("1");
        server.verify();
    }

    @Test
    @DisplayName("Las velas diarias se convierten en cierres por dia UTC")
    void mapsDailyCandlesToUtcDays() {
        server.expect(requestTo(startsWith(REST + "/products/ETH-USD/candles")))
                .andExpect(queryParam("granularity", "86400"))
                .andExpect(queryParam("start", "2024-01-01T00:00:00Z"))
                .andExpect(queryParam("end", "2024-01-02T00:00:00Z"))
                .andRespond(withSuccess("[[1704153600,1,9,2,2400.5,9],[1704067200,1,9,2,2300,9]]",
                        MediaType.APPLICATION_JSON));

        List<ProviderBar> bars = client.dailyBars("ETH-USD", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 2));

        assertThat(bars).containsExactly(
                new ProviderBar(LocalDate.of(2024, 1, 1), new BigDecimal("2300")),
                new ProviderBar(LocalDate.of(2024, 1, 2), new BigDecimal("2400.5")));
    }

    @Test
    @DisplayName("Un error de Coinbase sale como servicio no disponible")
    void providerErrorIsUnavailable() {
        server.expect(requestTo(REST + "/products/BTC-USD/stats"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.snapshot("BTC-USD"))
                .isInstanceOf(MarketDataUnavailableException.class);
    }

}
