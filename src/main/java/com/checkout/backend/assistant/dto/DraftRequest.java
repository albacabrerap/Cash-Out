package com.checkout.backend.assistant.dto;

import jakarta.validation.constraints.Size;

/**
 * Lo que el usuario escribio o lo que leyo la camara del QR de una boleta: uno
 * de los dos. Ninguno se guarda ni se registra en el log.
 */
public record DraftRequest(@Size(max = 500) String text, @Size(max = 1000) String qr) {
}
