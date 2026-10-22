package com.checkout.backend.market.coinbase;

import com.checkout.backend.market.CryptoDataClient;
import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(CoinbaseProperties.class)
public class CoinbaseConfig {

    @Bean
    public CryptoDataClient cryptoDataClient(CoinbaseProperties properties) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(properties.timeout());
        return new CoinbaseCryptoDataClient(properties, RestClient.builder().requestFactory(requestFactory));
    }

}
