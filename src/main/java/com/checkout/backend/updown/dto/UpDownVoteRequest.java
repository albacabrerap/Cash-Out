package com.checkout.backend.updown.dto;

import com.checkout.backend.updown.model.UpDownChoice;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** El tope de la apuesta son los puntos del jugador; lo revisa UpDownEngine. */
public record UpDownVoteRequest(@NotNull UpDownChoice choice, @NotNull @Min(0) Integer bet) {
}
