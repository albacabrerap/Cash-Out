package com.checkout.backend.race.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** La cuota la elige el anfitrion entre 0, 10, 20 y 50 fichas (ver RaceService). */
public record CreateRaceRequest(@NotNull BigDecimal entryFee) {
}
