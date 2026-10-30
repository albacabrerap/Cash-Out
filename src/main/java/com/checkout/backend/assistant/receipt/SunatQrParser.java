package com.checkout.backend.assistant.receipt;

import com.checkout.backend.assistant.receipt.SunatReceipt.DocumentType;
import com.checkout.backend.exceptions.InvalidRequestException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Lee el texto del QR de un comprobante electronico de SUNAT.
 *
 * El formato es el de la RS 244-2019/SUNAT, anexo, numeral 6.4.3: campos
 * separados por "|" en este orden: RUC, tipo de documento, serie, numero, IGV,
 * total, fecha de emision, tipo y numero de documento del comprador y el valor
 * resumen. Los campos vacios se conservan, y el valor resumen puede ir fuera
 * del QR, asi que basta con los siete primeros.
 *
 * Solo se leen esos siete. El documento del comprador (su DNI o RUC) no sirve
 * para registrar un gasto, puede ser de otra persona y es un dato personal
 * (Ley 29733): queda en un sobrante que nunca se separa ni se guarda.
 *
 * Las fechas y los montos van "con el mismo formato del comprobante" segun la
 * norma, pero en la calle aparecen como 2026-10-28 y como 28/10/2026: se aceptan
 * los dos.
 */
@Component
public class SunatQrParser {

    private static final int REQUIRED_FIELDS = 7;
    private static final Pattern RUC = Pattern.compile("(10|15|16|17|20)\\d{9}");
    private static final Pattern SERIES = Pattern.compile("[A-Z0-9]{4}");
    private static final Pattern NUMBER = Pattern.compile("\\d{1,8}");
    private static final Pattern AMOUNT = Pattern.compile("\\d{1,3}(,\\d{3})*(\\.\\d{1,2})?|\\d+(\\.\\d{1,2})?");
    private static final int[] RUC_WEIGHTS = {5, 4, 3, 2, 7, 6, 5, 4, 3, 2};
    private static final List<DateTimeFormatter> DATES = List.of(DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("dd-MM-uuuu").withResolverStyle(ResolverStyle.STRICT));

    private final Clock clock;

    public SunatQrParser(Clock clock) {
        this.clock = clock;
    }

    public SunatReceipt parse(String qr) {
        String text = qr == null ? "" : qr.strip().replace("﻿", "");
        // Con el limite, todo lo que va despues del septimo campo queda junto en un ultimo trozo que no se mira.
        String[] fields = text.split("\\|", REQUIRED_FIELDS + 1);
        if (fields.length < REQUIRED_FIELDS) {
            throw invalid("Este QR no es de un comprobante electrónico de SUNAT.");
        }
        for (int i = 0; i < REQUIRED_FIELDS; i++) {
            fields[i] = fields[i].strip();
        }

        String ruc = fields[0];
        if (!RUC.matcher(ruc).matches() || !validCheckDigit(ruc)) {
            throw invalid("El RUC del comprobante no es válido.");
        }
        DocumentType type = DocumentType.ofCode(fields[1])
                .orElseThrow(() -> invalid("Este QR no es de una boleta, factura, nota o recibo electrónico."));
        String series = fields[2].toUpperCase();
        if (!SERIES.matcher(series).matches() || !NUMBER.matcher(fields[3]).matches()) {
            throw invalid("La serie o el número del comprobante no son válidos.");
        }

        BigDecimal igv = fields[4].isEmpty() ? null : amount(fields[4]);
        BigDecimal total = amount(fields[5]);
        if (total.signum() <= 0) {
            throw invalid("El total del comprobante tiene que ser mayor que cero.");
        }
        if (igv != null && igv.compareTo(total) > 0) {
            throw invalid("El IGV no puede ser mayor que el total del comprobante.");
        }

        LocalDate issued = date(fields[6]);
        // Un dia de holgura: el comprobante pudo emitirse con la hora de otro huso.
        if (issued.isAfter(LocalDate.now(clock).plusDays(1))) {
            throw invalid("La fecha del comprobante todavía no llega.");
        }

        return new SunatReceipt(ruc, type, series, fields[3], igv, total, issued);
    }

    /** El ultimo digito del RUC es un modulo 11 de los diez anteriores: detecta un QR mal leido o inventado. */
    static boolean validCheckDigit(String ruc) {
        int sum = 0;
        for (int i = 0; i < RUC_WEIGHTS.length; i++) {
            sum += (ruc.charAt(i) - '0') * RUC_WEIGHTS[i];
        }
        int digit = 11 - sum % 11;
        if (digit == 10) {
            digit = 0;
        } else if (digit == 11) {
            digit = 1;
        }
        return digit == ruc.charAt(10) - '0';
    }

    private static BigDecimal amount(String field) {
        if (!AMOUNT.matcher(field).matches()) {
            throw invalid("Los montos del comprobante no son válidos.");
        }
        return new BigDecimal(field.replace(",", "")).setScale(2, RoundingMode.HALF_UP);
    }

    private static LocalDate date(String field) {
        for (DateTimeFormatter format : DATES) {
            try {
                return LocalDate.parse(field, format);
            } catch (DateTimeParseException e) {
                // Se prueba el siguiente formato.
            }
        }
        throw invalid("La fecha del comprobante no es válida.");
    }

    private static InvalidRequestException invalid(String message) {
        return new InvalidRequestException(message);
    }

}
