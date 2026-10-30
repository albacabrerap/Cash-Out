package com.checkout.backend.updown.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Un caso del banco de "¿Sube o baja?": un activo, el dia de una noticia y lo
 * que se cuenta al revelar. Los precios no se guardan; salen de Alpaca.
 */
@Entity
@Table(name = "updown_cases",
        uniqueConstraints = @UniqueConstraint(name = "uk_updown_cases_symbol_cutoff", columnNames = {"symbol", "cutoff"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpDownCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 12)
    @Column(nullable = false, length = 12)
    private String symbol;

    /** El dia de la noticia: el ultimo cierre que se muestra. */
    @NotNull
    @Column(nullable = false)
    private LocalDate cutoff;

    /** La pista que se ve antes de revelar, como "Índice tecnológico". */
    @NotBlank
    @Size(max = 60)
    @Column(nullable = false, length = 60)
    private String sector;

    /** Lo que se revela al final. */
    @NotBlank
    @Size(max = 160)
    @Column(nullable = false, length = 160)
    private String name;

    @NotBlank
    @Size(max = 300)
    @Column(nullable = false, length = 300)
    private String context;

    @NotBlank
    @Size(max = 600)
    @Column(nullable = false, length = 600)
    private String lesson;

    /** El resultado real del mes siguiente, en %, calculado al guardar. */
    @Column(name = "change_percent", precision = 7, scale = 2)
    private BigDecimal changePercent;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Min(0)
    @Column(name = "times_played", nullable = false)
    @Builder.Default
    private int timesPlayed = 0;

    @Column(name = "last_played_at")
    private LocalDateTime lastPlayedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Identity is the primary key; two unsaved instances are only equal to themselves. */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UpDownCase other)) return false;
        return id != null && id.equals(other.id);
    }

    /** Constant on purpose: the hash must not change when the id is assigned on persist. */
    @Override
    public int hashCode() {
        return UpDownCase.class.hashCode();
    }
}
