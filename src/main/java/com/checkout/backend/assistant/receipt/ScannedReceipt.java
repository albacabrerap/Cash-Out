package com.checkout.backend.assistant.receipt;

import com.checkout.backend.savings.expense.model.Expense;
import com.checkout.backend.savings.income.model.Income;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * Una boleta ya registrada, guardada solo como huella (ver ReceiptFingerprint).
 * Borrar el gasto o el ingreso la borra: asi la boleta se puede volver a registrar.
 */
@Entity
@Table(name = "scanned_receipts",
        uniqueConstraints = @UniqueConstraint(name = "uk_scanned_receipts_user_fingerprint",
                columnNames = {"user_id", "fingerprint"}))
@Getter
@Setter
@NoArgsConstructor
public class ScannedReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 64)
    private String fingerprint;

    /** El gasto que se creo con la boleta; null si fue una nota de credito. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "expense_id", foreignKey = @ForeignKey(name = "fk_scanned_receipts_expense"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Expense expense;

    /** El ingreso que se creo con una nota de credito. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "income_id", foreignKey = @ForeignKey(name = "fk_scanned_receipts_income"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Income income;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Identity is the primary key; two unsaved instances are only equal to themselves. */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ScannedReceipt other)) return false;
        return id != null && id.equals(other.id);
    }

    /** Constant on purpose: the hash must not change when the id is assigned on persist. */
    @Override
    public int hashCode() {
        return ScannedReceipt.class.hashCode();
    }
}
