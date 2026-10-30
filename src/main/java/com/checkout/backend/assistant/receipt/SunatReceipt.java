package com.checkout.backend.assistant.receipt;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Optional;

/**
 * Lo que se usa del QR de un comprobante electronico de SUNAT. No trae el
 * nombre del comercio ni lo que se compro: eso sale de consultar el RUC. Del
 * comprador no se guarda nada (ver SunatQrParser).
 *
 * @param igv null si el comprobante no lo consigna (por ejemplo, una venta exonerada)
 */
public record SunatReceipt(String ruc, DocumentType type, String series, String number, BigDecimal igv,
                           BigDecimal total, LocalDate issueDate) {

    /** Los comprobantes electronicos que llevan QR, con su codigo del catalogo 01 de SUNAT. */
    public enum DocumentType {
        INVOICE("01"),
        RECEIPT("03"),
        /** Devuelve plata: si se registra, es un ingreso o resta un gasto. */
        CREDIT_NOTE("07"),
        DEBIT_NOTE("08"),
        UTILITY_BILL("14");

        private final String code;

        DocumentType(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static Optional<DocumentType> ofCode(String code) {
            return Arrays.stream(values()).filter(type -> type.code.equals(code)).findFirst();
        }
    }

    /**
     * Identifica el comprobante en todo el pais: RUC, tipo, serie y numero.
     * Sirve para no registrar dos veces la misma boleta. El numero va sin
     * ceros a la izquierda porque cada impresora los pone distinto.
     */
    public String key() {
        return ruc + "-" + type.code() + "-" + series + "-" + Long.parseLong(number);
    }

}
