package com.checkout.backend.market;

import com.checkout.backend.market.MarketDataClient.ProviderBar;
import com.checkout.backend.market.MarketDataClient.ProviderSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * Proveedor de precios de cripto: los pares, su ultimo precio y sus velas.
 *
 * Va aparte de MarketDataClient porque la cripto no tiene un catalogo de miles ni
 * una jornada que cierre: se opera 24/7, y su "cierre anterior" es el precio de
 * hace 24 horas. Igual que alla, cualquier fallo sale como
 * MarketDataUnavailableException.
 */
public interface CryptoDataClient {

    /** Los pares pedidos que el proveedor conoce, con el nombre de su moneda. */
    List<CryptoProduct> products(Collection<String> productIds);

    /** Ultimo precio, con la apertura de las ultimas 24 horas como cierre anterior. */
    ProviderSnapshot snapshot(String productId);

    /** Cierres diarios (dias UTC) entre dos fechas, inclusive, del mas antiguo al mas reciente. */
    List<ProviderBar> dailyBars(String productId, LocalDate from, LocalDate to);

    /** Velas de un minuto entre dos instantes, inclusive. Son la materia prima de la repeticion. */
    List<Candle> minuteCandles(String productId, Instant from, Instant to);

    record CryptoProduct(String id, String name, boolean tradable) {
    }

    record Candle(Instant time, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {
    }

}
