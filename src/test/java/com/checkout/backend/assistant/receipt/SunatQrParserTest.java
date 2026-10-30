package com.checkout.backend.assistant.receipt;

import com.checkout.backend.assistant.receipt.SunatReceipt.DocumentType;
import com.checkout.backend.exceptions.InvalidRequestException;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** QRs como los de la RS 244-2019/SUNAT. Hoy es el 28/10/2026 en Lima; los RUC tienen su digito verificador bien. */
class SunatQrParserTest {

    private final SunatQrParser parser = new SunatQrParser(
            Clock.fixed(Instant.parse("2026-10-28T15:00:00Z"), ZoneId.of("America/Lima")));

    @Test
    @DisplayName("Una boleta completa, con valor resumen y el pipe final que ponen muchas impresoras")
    void readsAReceipt() {
        SunatReceipt receipt = parser.parse("20100070970|03|B001|00012345|5.49|35.90|2026-10-27|1|45678912|aGFzaA==|");

        assertThat(receipt.ruc()).isEqualTo("20100070970");
        assertThat(receipt.type()).isEqualTo(DocumentType.RECEIPT);
        assertThat(receipt.series()).isEqualTo("B001");
        assertThat(receipt.igv()).isEqualByComparingTo("5.49");
        assertThat(receipt.total()).isEqualByComparingTo("35.90");
        assertThat(receipt.issueDate()).isEqualTo(LocalDate.of(2026, 10, 27));
    }

    @Test
    @DisplayName("El DNI o RUC del comprador no queda en ningun lado: el resultado no tiene donde guardarlo")
    void dropsTheBuyerDocument() {
        SunatReceipt receipt = parser.parse("20100070970|03|B001|12345|5.49|35.90|2026-10-27|1|45678912|aGFzaA==|");

        assertThat(SunatReceipt.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("ruc", "type", "series", "number", "igv", "total", "issueDate");
        assertThat(receipt.toString()).doesNotContain("45678912");
    }

    @Test
    @DisplayName("Una factura a un RUC, con la fecha en dd/MM/yyyy y espacios alrededor de los campos")
    void readsAnInvoiceWithSpaces() {
        SunatReceipt receipt = parser.parse(" 20512345671 | 01 | f002 | 981 | 180.00 | 1,180.00 | 15/10/2026 | 6 | 10471234562 ");

        assertThat(receipt.type()).isEqualTo(DocumentType.INVOICE);
        assertThat(receipt.series()).isEqualTo("F002");
        assertThat(receipt.total()).isEqualByComparingTo("1180.00");
        assertThat(receipt.issueDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(receipt.toString()).doesNotContain("10471234562");
    }

    @Test
    @DisplayName("Una boleta sin IGV ni datos del comprador: los campos vacios se conservan")
    void keepsEmptyFields() {
        SunatReceipt receipt = parser.parse("10471234562|03|EB01|77||20.00|2026-10-01|-||");

        assertThat(receipt.igv()).isNull();
        assertThat(receipt.total()).isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("La clave ignora los ceros a la izquierda: dos impresiones de la misma boleta son la misma")
    void keyIdentifiesTheDocument() {
        SunatReceipt padded = parser.parse("20100070970|03|B001|00012345|5.49|35.90|2026-10-27|||");
        SunatReceipt plain = parser.parse("20100070970|03|B001|12345|5.49|35.90|27/10/2026");

        assertThat(padded.key()).isEqualTo("20100070970-03-B001-12345").isEqualTo(plain.key());
    }

    @Test
    @DisplayName("Una nota de credito se reconoce como tal: devuelve plata")
    void readsACreditNote() {
        assertThat(parser.parse("20100070970|07|BC01|3|0.00|15.00|2026-10-20|||").type())
                .isEqualTo(DocumentType.CREDIT_NOTE);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = ';', value = {
            "https://www.sunat.gob.pe/;Este QR no es de un comprobante electrónico de SUNAT.",
            "20100070970|03|B001|1|5.49;Este QR no es de un comprobante electrónico de SUNAT.",
            "20100070971|03|B001|1|5.49|35.90|2026-10-27;El RUC del comprobante no es válido.",
            "30100070970|03|B001|1|5.49|35.90|2026-10-27;El RUC del comprobante no es válido.",
            "20100070970|12|B001|1|5.49|35.90|2026-10-27;Este QR no es de una boleta, factura, nota o recibo electrónico.",
            "20100070970|03|B0-1|1|5.49|35.90|2026-10-27;La serie o el número del comprobante no son válidos.",
            "20100070970|03|B001|123456789|5.49|35.90|2026-10-27;La serie o el número del comprobante no son válidos.",
            "20100070970|03|B001|1|5.49|S/35.90|2026-10-27;Los montos del comprobante no son válidos.",
            "20100070970|03|B001|1|0.00|0.00|2026-10-27;El total del comprobante tiene que ser mayor que cero.",
            "20100070970|03|B001|1|50.00|35.90|2026-10-27;El IGV no puede ser mayor que el total del comprobante.",
            "20100070970|03|B001|1|5.49|35.90|31/02/2026;La fecha del comprobante no es válida.",
            "20100070970|03|B001|1|5.49|35.90|2026-11-05;La fecha del comprobante todavía no llega."
    })
    void rejectsInvalidQrs(String qr, String message) {
        assertThatThrownBy(() -> parser.parse(qr))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage(message);
    }

    @Test
    @DisplayName("Sin texto no hay comprobante")
    void rejectsEmpty() {
        assertThatThrownBy(() -> parser.parse(null)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> parser.parse("   ")).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("El digito verificador del RUC es un modulo 11")
    void checksTheRucDigit() {
        assertThat(SunatQrParser.validCheckDigit("20100070970")).isTrue();
        assertThat(SunatQrParser.validCheckDigit("10471234562")).isTrue();
        assertThat(SunatQrParser.validCheckDigit("10471234563")).isFalse();
    }

}
