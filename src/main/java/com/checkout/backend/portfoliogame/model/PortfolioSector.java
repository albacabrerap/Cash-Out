package com.checkout.backend.portfoliogame.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Los sectores que se pueden elegir. Cada uno se sigue con un ETF real, y su
 * retorno en cada etapa sale de los cierres ajustados de ese ETF.
 */
public enum PortfolioSector {
    TECH("XLK", "Tecnología", "Crecimiento", "Software y chips. Crece rápido; sufre cuando suben las tasas."),
    ENERGY("XLE", "Energía", "Cíclico", "Petróleo y gas. Gana cuando sube el precio de la energía."),
    STAPLES("XLP", "Consumo básico", "Defensivo", "Comida e higiene. La gente los compra igual en crisis."),
    BANKS("XLF", "Bancos y finanzas", "Cíclico", "Ganan con tasas altas y economía fuerte; pierden con impagos."),
    HEALTH("XLV", "Salud", "Defensivo", "Medicinas y hospitales. Demanda estable."),
    SMALL("IWM", "Small caps", "Alto riesgo", "Empresas pequeñas. Suben fuerte con optimismo y caen primero con miedo."),
    BONDS("TLT", "Bonos largos", "Renta fija", "Deuda del gobierno a 20+ años. Bajan cuando suben las tasas."),
    GOLD("GLD", "Oro", "Refugio", "No paga intereses, pero suele aguantar cuando hay miedo.");

    /** La referencia que se muestra junto al ranking: todo en las 500 empresas mas grandes. */
    public static final String BENCHMARK = "SPY";

    private final String symbol;
    private final String label;
    private final String kind;
    private final String description;

    PortfolioSector(String symbol, String label, String kind, String description) {
        this.symbol = symbol;
        this.label = label;
        this.kind = kind;
        this.description = description;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String symbol() {
        return symbol;
    }

    public String label() {
        return label;
    }

    public String kind() {
        return kind;
    }

    public String description() {
        return description;
    }

    public static Optional<PortfolioSector> byKey(String key) {
        return Arrays.stream(values()).filter(sector -> sector.key().equals(key)).findFirst();
    }
}
