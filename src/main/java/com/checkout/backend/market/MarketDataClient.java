package com.checkout.backend.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Proveedor de datos de mercado: el catalogo de acciones, su ultimo precio y su
 * historico diario.
 *
 * Es una interfaz para que el resto del backend no sepa de Alpaca —sus URLs, sus
 * cabeceras, la forma de su JSON— y para que los tests usen un proveedor falso
 * sin red. Cualquier fallo del proveedor sale como MarketDataUnavailableException.
 */
public interface MarketDataClient {

    /** Las acciones de EE. UU. que el proveedor da por activas. */
    List<ProviderAsset> listUsEquities();

    /** El ultimo precio de cada simbolo; los que el proveedor no conoce no aparecen en el mapa. */
    Map<String, ProviderSnapshot> snapshots(Collection<String> symbols);

    /** Cierres diarios entre dos fechas, inclusive, del mas antiguo al mas reciente. */
    List<ProviderBar> dailyBars(String symbol, LocalDate from, LocalDate to);

    record ProviderAsset(String symbol, String name, String exchange, boolean tradable) {
    }

    /**
     * @param lastPrice     precio de la ultima operacion
     * @param lastTradeAt   cuando ocurrio; fuera de horario puede ser de dias atras
     * @param previousClose cierre de la jornada anterior, para la variacion del dia
     */
    record ProviderSnapshot(String symbol, BigDecimal lastPrice, Instant lastTradeAt, BigDecimal previousClose) {
    }

    record ProviderBar(LocalDate date, BigDecimal close) {
    }

}
