package com.checkout.backend.security.google;

import com.checkout.backend.exceptions.ApiException;
import org.springframework.http.HttpStatus;

/** 503: el inicio con Google existe, pero este servidor no tiene el client ID configurado. */
public class GoogleSignInUnavailableException extends ApiException {

    public GoogleSignInUnavailableException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, "El inicio con Google no está disponible por ahora. Usa tu correo y contraseña.");
    }

}
