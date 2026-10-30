package com.checkout.backend.updown.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * @param changePercent el resultado real del mes siguiente, en %
 * @param outcome       UP, FLAT o DOWN segun ese resultado
 * @param lastPlayedAt  null si nunca salio en una partida
 */
public record UpDownCaseResponse(long id, String symbol, LocalDate cutoff, String sector, String name, String context,
                                 String lesson, boolean active, Double changePercent, String outcome, int timesPlayed,
                                 LocalDateTime lastPlayedAt) {
}
