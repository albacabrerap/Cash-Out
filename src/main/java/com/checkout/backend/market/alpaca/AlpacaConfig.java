package com.checkout.backend.market.alpaca;

import com.checkout.backend.market.MarketDataClient;
import com.checkout.backend.market.MarketProperties;
import com.checkout.backend.news.NewsClient;
import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({AlpacaProperties.class, MarketProperties.class})
public class AlpacaConfig {

    @Bean
    public MarketDataClient marketDataClient(AlpacaProperties properties) {
        return new AlpacaMarketDataClient(properties, restClient(properties));
    }

    @Bean
    public NewsClient newsClient(AlpacaProperties properties) {
        return new AlpacaNewsClient(properties, restClient(properties));
    }

    private static RestClient.Builder restClient(AlpacaProperties properties) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(properties.timeout());
        return RestClient.builder().requestFactory(requestFactory);
    }

}
