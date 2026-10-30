package com.checkout.backend.assistant.receipt;

import com.checkout.backend.security.JwtProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * La huella de una boleta de un usuario: HMAC-SHA256 de su id y la clave de la
 * boleta (RUC, tipo, serie y numero).
 *
 * Un SHA-256 comun no alcanza: los RUC son publicos, las series pocas y los
 * numeros correlativos, asi que con la base en la mano se podrian probar todas
 * las boletas posibles. Con HMAC hace falta ademas la clave, que no esta en la
 * base. El id del usuario va adentro para que dos personas puedan registrar la
 * misma boleta (una cuenta compartida) y no se pueda ver que la comparten.
 *
 * La clave es RECEIPT_HASH_KEY. Si no esta, se deriva de JWT_SECRET, que ya es
 * secreto: asi no hace falta configurar una variable mas para que funcione.
 * Cambiarla vuelve irreconocibles las huellas guardadas (solo se pierde el aviso
 * de duplicado de lo ya registrado).
 */
@Component
public class ReceiptFingerprint {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public ReceiptFingerprint(@Value("${assistant.receipt-key:}") String configured, JwtProperties jwt) {
        byte[] material = configured.isBlank()
                ? hmac(jwt.secret().getBytes(StandardCharsets.UTF_8), "cash-out/receipt-fingerprint")
                : configured.getBytes(StandardCharsets.UTF_8);
        this.key = new SecretKeySpec(material, ALGORITHM);
    }

    public String of(long userId, SunatReceipt receipt) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal((userId + "|" + receipt.key()).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 no esta disponible en esta JVM", e);
        }
    }

    private static byte[] hmac(byte[] secret, String label) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(label.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 no esta disponible en esta JVM", e);
        }
    }

}
