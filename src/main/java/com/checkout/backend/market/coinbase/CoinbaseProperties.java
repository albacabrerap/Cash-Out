package com.checkout.backend.market.coinbase;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Direcciones de Coinbase y los pares que entran al catalogo.
 *
 * La API publica de Coinbase no usa claves. Viene apagada por defecto para que
 * los tests no salgan a la red; application.properties la enciende.
 *
 * @param enabled   cargar los pares al arrancar y abrir el stream de precios en vivo
 * @param restUrl   API REST publica (pares, precios y velas)
 * @param streamUrl WebSocket publico con cada operacion
 * @param products  pares que entran al catalogo. Pocos y conocidos: Coinbase lista
 *                  cientos, y para aprender a invertir alcanzan los principales
 * @param timeout   limite por peticion REST y para conectar el WebSocket
 */
@ConfigurationProperties(prefix = "coinbase")
public record CoinbaseProperties(
        Boolean enabled,
        URI restUrl,
        URI streamUrl,
        List<String> products,
        Duration timeout
) {

    public CoinbaseProperties {
        if (enabled == null) enabled = Boolean.FALSE;
        if (restUrl == null) restUrl = URI.create("https://api.exchange.coinbase.com");
        if (streamUrl == null) streamUrl = URI.create("wss://advanced-trade-ws.coinbase.com");
        if (products == null || products.isEmpty()) {
            products = List.of("BTC-USD", "ETH-USD", "SOL-USD", "XRP-USD", "ADA-USD", "DOGE-USD", "LTC-USD", "AVAX-USD");
        }
        if (timeout == null) timeout = Duration.ofSeconds(10);
    }

}
