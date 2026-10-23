package com.checkout.backend.savings.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Un ingreso o un gasto en el listado unificado de GET /api/v1/movements.
 *
 * No hay tabla de movimientos: las dos entidades se proyectan a esta forma en
 * la consulta. El {@code id} solo es unico junto con {@code kind}, porque cada
 * tabla tiene su propia secuencia; el detalle completo esta en /incomes/{id} o
 * /expenses/{id} segun el tipo.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MovementResponse {

    private Long id;

    private MovementKind kind;

    /** La fuente de un ingreso, o la descripcion de un gasto (que puede venir vacia). */
    private String label;

    /** La categoria de un gasto; null en un ingreso. */
    private String category;

    /** Siempre positivo: el signo lo da {@code kind}. */
    private BigDecimal amount;

    private LocalDate date;

    private LocalDateTime createdAt;

}
