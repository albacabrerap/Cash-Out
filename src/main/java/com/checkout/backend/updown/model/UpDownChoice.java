package com.checkout.backend.updown.model;

/** Lo que predice cada jugador para el mes siguiente. */
public enum UpDownChoice {
    UP,
    FLAT,
    DOWN;

    /** "Igual" gana si el precio se movio menos de esto, hacia cualquier lado. */
    public static final double FLAT_BAND_PERCENT = 2.0;

    public static UpDownChoice of(double changePercent) {
        if (changePercent > FLAT_BAND_PERCENT) return UP;
        if (changePercent < -FLAT_BAND_PERCENT) return DOWN;
        return FLAT;
    }
}
