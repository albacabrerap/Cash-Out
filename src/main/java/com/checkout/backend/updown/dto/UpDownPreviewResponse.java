package com.checkout.backend.updown.dto;

import java.util.List;

/**
 * Lo que paso de verdad con un activo y una fecha, para escribir la leccion
 * mirando el resultado. Los precios van normalizados a 100 el dia de la noticia.
 */
public record UpDownPreviewResponse(String symbol, String cutoff, List<Double> past, List<Double> future,
                                    double changePercent, String outcome) {
}
