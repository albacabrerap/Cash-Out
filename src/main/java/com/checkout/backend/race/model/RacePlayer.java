package com.checkout.backend.race.model;

import com.checkout.backend.user.model.User;
import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Un jugador inscrito en una sala. Su id es la referencia de la cuota, el premio
 * y la devolucion en el libro de fichas.
 */
@Entity
@Table(name = "race_players",
        uniqueConstraints = @UniqueConstraint(name = "uk_race_players_room_user", columnNames = {"room_id", "user_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RacePlayer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false, foreignKey = @ForeignKey(name = "fk_race_players_room"))
    private RaceRoom room;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_race_players_user"))
    private User user;

    @CreationTimestamp
    @Column(name = "joined_at", nullable = false, updatable = false)
    private LocalDateTime joinedAt;

    @Column(name = "final_value", precision = 19, scale = 2)
    private BigDecimal finalValue;

    @Column(name = "final_rank")
    private Integer finalRank;

    @NotNull
    @DecimalMin("0")
    @Column(nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal prize = BigDecimal.ZERO;

    /** Identity is the primary key; two unsaved instances are only equal to themselves. */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RacePlayer other)) return false;
        return id != null && id.equals(other.id);
    }

    /** Constant on purpose: the hash must not change when the id is assigned on persist. */
    @Override
    public int hashCode() {
        return RacePlayer.class.hashCode();
    }
}
