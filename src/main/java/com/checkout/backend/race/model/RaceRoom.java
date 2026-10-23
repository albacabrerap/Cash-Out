package com.checkout.backend.race.model;

import com.checkout.backend.user.model.User;
import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

/** Una sala de la carrera de trading. La carrera en si corre en memoria (ver RaceEngine). */
@Entity
@Table(name = "race_rooms", uniqueConstraints = @UniqueConstraint(name = "uk_race_rooms_code", columnNames = "code"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RaceRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(min = 6, max = 6)
    @Column(nullable = false, length = 6)
    private String code;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "host_id", nullable = false, foreignKey = @ForeignKey(name = "fk_race_rooms_host"))
    private User host;

    @NotBlank
    @Size(max = 40)
    @Column(name = "scenario_key", nullable = false, length = 40)
    private String scenarioKey;

    @NotNull
    @DecimalMin("0")
    @Column(name = "entry_fee", nullable = false, precision = 19, scale = 2)
    private BigDecimal entryFee;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private RaceStatus status = RaceStatus.LOBBY;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /** Identity is the primary key; two unsaved instances are only equal to themselves. */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RaceRoom other)) return false;
        return id != null && id.equals(other.id);
    }

    /** Constant on purpose: the hash must not change when the id is assigned on persist. */
    @Override
    public int hashCode() {
        return RaceRoom.class.hashCode();
    }
}
