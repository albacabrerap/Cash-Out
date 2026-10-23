package com.checkout.backend.market.alpaca;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credenciales y direcciones de Alpaca, leidas de variables de entorno.
 *
 * Las claves no tienen default y la aplicacion arranca sin ellas: sin claves el
 * catalogo sigue funcionando con los activos cargados a mano, y lo que dependa
 * del proveedor responde 503. Asi los tests y el CI corren sin secretos.
 *
 * Las claves nunca salen del backend. El frontend pide precios a esta API, no a
 * Alpaca: si viajaran en el JavaScript, cualquiera podria leerlas y usarlas.
 *
 * @param apiKey     ALPACA_API_KEY
 * @param apiSecret  ALPACA_API_SECRET
 * @param dataUrl    API de datos de mercado (precios e historico)
 * @param tradingUrl API de trading, que es la que publica el catalogo de activos
 * @param feed       fuente de precios. "iex" es la del plan gratuito
 * @param timeout    limite por peticion, para que un proveedor lento no bloquee hilos
 */
@ConfigurationProperties(prefix = "alpaca")
public record AlpacaProperties(
        String apiKey,
        String apiSecret,
        URI dataUrl,
        URI tradingUrl,
        String feed,
        Duration timeout
) {

    public AlpacaProperties {
        if (dataUrl == null) dataUrl = URI.create("https://data.alpaca.markets");
        if (tradingUrl == null) tradingUrl = URI.create("https://paper-api.alpaca.markets");
        if (feed == null || feed.isBlank()) feed = "iex";
        if (timeout == null) timeout = Duration.ofSeconds(10);
    }

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank() && apiSecret != null && !apiSecret.isBlank();
    }

    /** Nunca imprime las claves, ni siquiera en un log de depuracion. */
    @Override
    public String toString() {
        return "AlpacaProperties[configured=" + configured() + ", dataUrl=" + dataUrl
                + ", tradingUrl=" + tradingUrl + ", feed=" + feed + "]";
    }

}
