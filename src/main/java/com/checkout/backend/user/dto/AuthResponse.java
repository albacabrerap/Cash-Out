package com.checkout.backend.user.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Built once by the authentication service and never mutated afterwards, so it
 * exposes no setter. It is assembled by hand rather than mapped, because none
 * of its fields come from a single entity.
 *
 * Los tokens faltan en dos casos: el de refresco cuando la web lo pidio en
 * cookie, y los dos cuando la cuenta todavia tiene que verificar su correo.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuthResponse {

    private String accessToken;

    private String refreshToken;

    private String tokenType;

    private Long expiresIn;

    private UserResponse user;

    /** true si hay que abrir el enlace del correo antes de poder entrar. */
    private Boolean emailVerificationRequired;

}
