package com.checkout.backend.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** El token que llega en el enlace del correo de verificacion. */
public record VerifyEmailRequest(@NotBlank @Size(max = 200) String token) {
}
