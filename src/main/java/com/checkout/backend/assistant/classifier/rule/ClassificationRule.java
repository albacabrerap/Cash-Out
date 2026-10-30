package com.checkout.backend.assistant.classifier.rule;

import com.checkout.backend.assistant.classifier.Classification.Kind;
import com.checkout.backend.savings.expense.model.ExpenseCategory;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** "Este RUC es Comida" o "tottus es Comida": lo que el usuario corrigio una vez y no quiere volver a corregir. */
@Entity
@Table(name = "classification_rules",
        uniqueConstraints = @UniqueConstraint(name = "uk_classification_rules_user_match",
                columnNames = {"user_id", "match_type", "pattern"}))
@Getter
@Setter
@NoArgsConstructor
public class ClassificationRule {

    public enum MatchType { RUC, KEYWORD }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false, length = 10)
    private MatchType matchType;

    @Column(nullable = false, length = 120)
    private String pattern;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Kind kind;

    /** Null en un ingreso. */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ExpenseCategory category;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Identity is the primary key; two unsaved instances are only equal to themselves. */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ClassificationRule other)) return false;
        return id != null && id.equals(other.id);
    }

    /** Constant on purpose: the hash must not change when the id is assigned on persist. */
    @Override
    public int hashCode() {
        return ClassificationRule.class.hashCode();
    }
}
