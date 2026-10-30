package com.checkout.backend.updown.service;

import com.checkout.backend.exceptions.InvalidRequestException;
import com.checkout.backend.market.MarketDataClient;
import com.checkout.backend.market.MarketDataClient.ProviderBar;
import com.checkout.backend.updown.model.UpDownScenario;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Los cierres reales de cada caso, con el dia de la noticia en 100 para que el
 * nivel del precio no delate el activo. Se guardan en memoria: los cierres
 * pasados no cambian.
 */
@Component
public class UpDownPrices {

    /** Los 80 cierres hasta la noticia y los 22 desde ella: el futuro arranca donde termina el pasado. */
    record Series(double[] past, double[] future) {

        double changePercent() {
            return (future[future.length - 1] / past[past.length - 1] - 1) * 100;
        }
    }

    private final MarketDataClient market;
    private final Map<String, Series> cache = new ConcurrentHashMap<>();

    public UpDownPrices(MarketDataClient market) {
        this.market = market;
    }

    Series load(String symbol, LocalDate cutoff) {
        return cache.computeIfAbsent(symbol + "@" + cutoff, key -> fetch(symbol, cutoff));
    }

    private Series fetch(String symbol, LocalDate cutoff) {
        List<ProviderBar> bars = market.dailyBars(symbol, cutoff.minusDays(140), cutoff.plusDays(45));
        int cut = -1;
        for (int i = 0; i < bars.size(); i++) {
            if (bars.get(i).date().equals(cutoff)) {
                cut = i;
            }
        }
        int past = UpDownScenario.PAST_DAYS;
        int future = UpDownScenario.FUTURE_DAYS;
        if (cut < past - 1 || cut + future >= bars.size()) {
            throw new InvalidRequestException("No hay cierres de " + symbol + " suficientes alrededor del " + cutoff
                    + ": hacen falta " + past + " hasta ese dia, que tiene que ser habil, y " + future + " despues.");
        }
        double base = bars.get(cut).close().doubleValue();
        double[] closes = bars.stream().mapToDouble(bar -> bar.close().doubleValue() / base * 100).toArray();
        return new Series(Arrays.copyOfRange(closes, cut - past + 1, cut + 1),
                Arrays.copyOfRange(closes, cut, cut + future + 1));
    }

}
