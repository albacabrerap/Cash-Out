package com.checkout.backend.market.alpaca;

import com.checkout.backend.market.MarketDataUnavailableException;
import com.checkout.backend.news.NewsClient.NewsPage;
import com.checkout.backend.news.NewsClient.ProviderNews;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
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

/** Las noticias de Alpaca contra respuestas con su forma real, sin red. */
class AlpacaNewsClientTest {

    private static final String DATA = "https://data.test";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    private AlpacaNewsClient client(String key) {
        return new AlpacaNewsClient(new AlpacaProperties(key, key == null ? null : "secreto", URI.create(DATA),
                URI.create("https://trading.test"), "iex", Duration.ofSeconds(5)), builder);
    }

    @Test
    @DisplayName("Traduce cada nota: limpia el HTML, elige la imagen chica y descarta enlaces que no son web")
    void mapsNews() {
        server.expect(requestTo(startsWith(DATA + "/v1beta1/news")))
                .andExpect(header("APCA-API-KEY-ID", "clave"))
                .andExpect(queryParam("symbols", "AAPL,BTCUSD"))
                .andExpect(queryParam("page_token", "abc"))
                .andExpect(queryParam("limit", "20"))
                .andRespond(withSuccess("""
                        {"news":[
                          {"id":1,"headline":"Apple &amp; friends rally","summary":"<p>Strong <b>quarter</b></p>",
                           "author":"Ana","source":"benzinga","url":"https://www.benzinga.com/x",
                           "created_at":"2026-10-18T16:55:09Z","updated_at":"2026-10-18T18:00:00Z",
                           "images":[{"size":"thumb","url":"https://img.test/t.jpg"},
                                     {"size":"small","url":"https://img.test/s.jpg"},
                                     {"size":"large","url":"https://img.test/l.jpg"}],
                           "symbols":["AAPL","BTCUSD"]},
                          {"id":2,"headline":"No summary","summary":"","source":"benzinga",
                           "url":"javascript:alert(1)","created_at":"2026-10-18T10:00:00Z","images":[],"symbols":[]}],
                         "next_page_token":"def"}
                        """, MediaType.APPLICATION_JSON));

        NewsPage page = client("clave").latest(List.of("AAPL", "BTCUSD"), "abc", 20);

        ProviderNews first = page.items().get(0);
        assertThat(first.headline()).isEqualTo("Apple & friends rally");
        assertThat(first.summary()).isEqualTo("Strong quarter");
        assertThat(first.imageUrl()).isEqualTo("https://img.test/s.jpg");
        assertThat(first.symbols()).containsExactly("AAPL", "BTCUSD");
        assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-10-18T16:55:09Z"));
        assertThat(first.updatedAt()).isEqualTo(Instant.parse("2026-10-18T18:00:00Z"));

        ProviderNews second = page.items().get(1);
        assertThat(second.url()).isNull();
        assertThat(second.imageUrl()).isNull();
        assertThat(second.summary()).isEmpty();
        assertThat(second.updatedAt()).isEqualTo(second.publishedAt());
        assertThat(page.nextPageToken()).isEqualTo("def");
    }

    @Test
    @DisplayName("Sin claves no sale a la red y responde no disponible")
    void unconfiguredIsUnavailable() {
        assertThatThrownBy(() -> client(null).latest(List.of(), null, 20))
                .isInstanceOf(MarketDataUnavailableException.class);
        server.verify();
    }

    @Test
    @DisplayName("Un error de Alpaca sale como no disponible")
    void providerErrorIsUnavailable() {
        server.expect(requestTo(startsWith(DATA + "/v1beta1/news")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client("clave").latest(List.of(), null, 20))
                .isInstanceOf(MarketDataUnavailableException.class);
    }

}
