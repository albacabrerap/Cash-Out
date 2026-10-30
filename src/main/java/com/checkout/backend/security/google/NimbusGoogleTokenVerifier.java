package com.checkout.backend.security.google;

import com.checkout.backend.exceptions.UnauthenticatedException;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * Valida el ID token como pide Google: firma con sus claves publicas (JWKS),
 * emisor accounts.google.com, audiencia igual a nuestro client ID y vigencia.
 * Sin la audiencia, un token emitido para otra app serviria para entrar aqui.
 */
@Component
public class NimbusGoogleTokenVerifier implements GoogleTokenVerifier {

    private static final String JWKS = "https://www.googleapis.com/oauth2/v3/certs";
    private static final Set<String> ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

    private final String clientId;
    private volatile JwtDecoder decoder;

    public NimbusGoogleTokenVerifier(@Value("${google.client-id:}") String clientId) {
        this.clientId = clientId.strip();
    }

    @Override
    public GoogleIdentity verify(String idToken) {
        if (clientId.isEmpty()) {
            throw new GoogleSignInUnavailableException();
        }
        Jwt jwt;
        try {
            jwt = decoder().decode(idToken);
        } catch (JwtException e) {
            throw new UnauthenticatedException("No pudimos verificar tu cuenta de Google. Inténtalo de nuevo.");
        }
        return new GoogleIdentity(
                jwt.getSubject(),
                jwt.getClaimAsString("email"),
                Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified")),
                jwt.getClaimAsString("name"));
    }

    // Se arma al primer uso: el servidor arranca aunque no haya red hacia Google.
    private JwtDecoder decoder() {
        if (decoder == null) {
            OAuth2TokenValidator<Jwt> issuer = jwt ->
                    ISSUERS.contains(jwt.getClaimAsString("iss")) ? success() : failure("El token no lo emitió Google.");
            OAuth2TokenValidator<Jwt> audience = jwt ->
                    jwt.getAudience() != null && jwt.getAudience().contains(clientId)
                            ? success() : failure("El token es de otra aplicación.");
            NimbusJwtDecoder built = NimbusJwtDecoder.withJwkSetUri(JWKS).build();
            built.setJwtValidator(new DelegatingOAuth2TokenValidator<>(List.of(new JwtTimestampValidator(), issuer, audience)));
            decoder = built;
        }
        return decoder;
    }

    private static OAuth2TokenValidatorResult success() {
        return OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
    }

}
