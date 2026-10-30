package com.checkout.backend.token_wallet.refresh_token.service;

import com.checkout.backend.exceptions.UnauthenticatedException;
import com.checkout.backend.security.JwtProperties;
import com.checkout.backend.security.OpaqueTokens;
import com.checkout.backend.token_wallet.refresh_token.model.RefreshToken;
import com.checkout.backend.token_wallet.refresh_token.repository.RefreshTokenRepository;
import com.checkout.backend.user.model.User;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Emite, valida y rota los tokens de refresco.
 *
 * El token de refresco es opaco, no un JWT: no tiene que transportar
 * informacion, solo servir de llave contra una fila. Eso lo hace revocable, que
 * es justo lo que el token de acceso no puede ser.
 *
 * En la tabla se guarda su SHA-256, nunca el token. Es el mismo criterio que
 * con una contrasena: quien consiga leer la base no puede hacerse pasar por
 * nadie. No lleva salt ni bcrypt porque el token ya es un valor aleatorio de 256
 * bits, y contra eso un diccionario no sirve de nada.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    /** 32 bytes de entropia: adivinarlo no es un ataque viable. */
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenFamilyRevoker familyRevoker;
    private final JwtProperties properties;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository,
                               RefreshTokenFamilyRevoker familyRevoker,
                               JwtProperties properties) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.familyRevoker = familyRevoker;
        this.properties = properties;
    }

    /**
     * Emite un token nuevo y guarda su hash.
     *
     * @return el token en claro, que es la unica vez que existe fuera del
     *         cliente: despues de esto solo queda el hash
     */
    @Transactional
    public String issue(User user) {
        String token = OpaqueTokens.generate(random, TOKEN_BYTES);

        refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .tokenHash(OpaqueTokens.hash(token))
                .expiresAt(LocalDateTime.now().plus(properties.expirationRefresh()))
                .build());

        return token;
    }

    /**
     * Canjea un token por otro y devuelve su dueno.
     *
     * Rota siempre: el token entregado se revoca y se emite uno nuevo. Sin
     * rotacion, un token robado sirve hasta que caduca; con rotacion, en cuanto
     * el dueno legitimo lo usa el del ladron deja de valer.
     *
     * Hay dos formas de que un canje no prospere y no significan lo mismo:
     *
     * <ul>
     *   <li><b>Reutilizacion.</b> El token llega ya revocado. Es la senal
     *       clasica de que alguien tiene una copia: el legitimo ya lo roto y el
     *       viejo solo puede estar en manos de un tercero. Se revoca la familia
     *       entera y el usuario vuelve a entrar, que es el precio correcto ante
     *       esa sospecha. La revocacion va en una transaccion aparte
     *       ({@link RefreshTokenFamilyRevoker}) porque esta se deshace con la
     *       excepcion que viene justo despues.</li>
     *   <li><b>Carrera.</b> El token llega vivo pero otra peticion lo canjea
     *       primero. No es un ataque: es un cliente que disparo dos veces, o
     *       dos pestanas. Se rechaza solo esta peticion y la sesion del ganador
     *       sigue viva. Revocar la familia aqui echaria de la aplicacion a un
     *       usuario legitimo por hacer doble clic.</li>
     * </ul>
     */
    @Transactional
    public User rotate(String presentedToken) {
        RefreshToken stored = refreshTokenRepository.findByTokenHash(OpaqueTokens.hash(presentedToken))
                .orElseThrow(() -> new UnauthenticatedException("El token de refresco no es valido."));

        if (stored.getRevokedAt() != null) {
            log.warn("Reutilizacion de un refresh token ya revocado del usuario {}. "
                    + "Se revoca la familia completa.", stored.getUser().getId());
            familyRevoker.revokeFamily(stored.getUser().getId());
            throw new UnauthenticatedException("El token de refresco no es valido.");
        }

        if (!stored.isActive()) {
            throw new UnauthenticatedException("El token de refresco expiro.");
        }

        // Compare-and-set: la base decide quien gana. Comprobar arriba y
        // escribir aqui sin esta condicion dejaria pasar a los dos.
        LocalDateTime now = LocalDateTime.now();
        User owner = stored.getUser();
        if (refreshTokenRepository.revokeIfActive(stored.getId(), now) == 0) {
            log.info("Canje simultaneo del mismo refresh token del usuario {}. "
                    + "Gana la otra peticion.", owner.getId());
            throw new UnauthenticatedException("El token de refresco no es valido.");
        }

        // La base ya lo tiene revocado; esto alinea la entidad cargada.
        stored.setRevokedAt(now);
        return owner;
    }

    /**
     * Cierra la sesion revocando el token presentado.
     *
     * Un token desconocido no da error: el efecto que el cliente pide — que ese
     * token deje de servir — ya se cumple. Responder distinto convertiria el
     * logout en una forma de averiguar que tokens existen.
     */
    @Transactional
    public void revoke(String presentedToken) {
        refreshTokenRepository.findByTokenHash(OpaqueTokens.hash(presentedToken))
                .filter(token -> token.getRevokedAt() == null)
                .ifPresent(token -> token.setRevokedAt(LocalDateTime.now()));
    }

    /** Revoca todo lo del usuario: cerrar sesion en todos los dispositivos. */
    @Transactional
    public int revokeAll(User user) {
        return refreshTokenRepository.revokeAllByUserId(user.getId(), LocalDateTime.now());
    }

}
