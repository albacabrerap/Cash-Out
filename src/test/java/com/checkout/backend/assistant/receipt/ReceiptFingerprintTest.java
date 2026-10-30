package com.checkout.backend.assistant.receipt;

import com.checkout.backend.assistant.receipt.SunatReceipt.DocumentType;
import com.checkout.backend.security.JwtProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiptFingerprintTest {

    private static final JwtProperties JWT = new JwtProperties("clave-de-pruebas-que-supera-los-32-caracteres",
            Duration.ofMinutes(15), Duration.ofDays(30));

    private final ReceiptFingerprint fingerprints = new ReceiptFingerprint("", JWT);

    @Test
    @DisplayName("La misma boleta da la misma huella, con o sin ceros a la izquierda en el numero")
    void stable() {
        assertThat(fingerprints.of(7, receipt(DocumentType.RECEIPT, "00012345")))
                .isEqualTo(fingerprints.of(7, receipt(DocumentType.RECEIPT, "12345")))
                .hasSize(64)
                .doesNotContain("20100070970");
    }

    @Test
    @DisplayName("Cambia con otro usuario, otro tipo de comprobante u otra clave")
    void changesWithUserTypeAndKey() {
        String base = fingerprints.of(7, receipt(DocumentType.RECEIPT, "12345"));

        assertThat(fingerprints.of(8, receipt(DocumentType.RECEIPT, "12345"))).isNotEqualTo(base);
        assertThat(fingerprints.of(7, receipt(DocumentType.CREDIT_NOTE, "12345"))).isNotEqualTo(base);
        assertThat(new ReceiptFingerprint("otra-clave-configurada", JWT).of(7, receipt(DocumentType.RECEIPT, "12345")))
                .isNotEqualTo(base);
    }

    private static SunatReceipt receipt(DocumentType type, String number) {
        return new SunatReceipt("20100070970", type, "B001", number, new BigDecimal("5.49"), new BigDecimal("35.90"),
                LocalDate.of(2026, 10, 27));
    }

}
