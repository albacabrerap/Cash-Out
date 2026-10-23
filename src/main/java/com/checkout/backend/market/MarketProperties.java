package com.checkout.backend.market;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cuanto se reutiliza lo que respondio el proveedor antes de volver a pedirlo.
 *
 * @param quoteTtl              cuanto vale un precio cacheado para mostrar. Corto:
 *                              el grafico en vivo pide seguido, y Alpaca limita el
 *                              plan gratuito a 200 pedidos por minuto
 * @param tradingQuoteMaxAge    el precio mas viejo con el que se ejecuta una orden
 *                              sobre un activo del proveedor. La orden refresca el
 *                              precio antes de ejecutarse; esto solo corta cuando el
 *                              proveedor no respondio y quedo un precio antiguo
 * @param historyYears          cuanto historico se descarga la primera vez
 * @param historyRefresh        cada cuanto se vuelve a preguntar por cierres nuevos
 * @param syncCatalogOnStartup  descargar el catalogo al arrancar si todavia no hay
 *                              activos del proveedor
 */
@ConfigurationProperties(prefix = "market")
public record MarketProperties(
        Duration quoteTtl,
        Duration tradingQuoteMaxAge,
        Integer historyYears,
        Duration historyRefresh,
        Boolean syncCatalogOnStartup
) {

    public MarketProperties {
        if (quoteTtl == null) quoteTtl = Duration.ofSeconds(15);
        if (tradingQuoteMaxAge == null) tradingQuoteMaxAge = Duration.ofMinutes(15);
        if (historyYears == null) historyYears = 5;
        if (historyRefresh == null) historyRefresh = Duration.ofHours(6);
        if (syncCatalogOnStartup == null) syncCatalogOnStartup = Boolean.TRUE;
    }

}
