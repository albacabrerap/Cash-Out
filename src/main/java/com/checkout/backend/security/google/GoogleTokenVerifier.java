package com.checkout.backend.security.google;

/**
 * Verifica el ID token que la web recibe de Google Identity Services.
 *
 * Es una interfaz para que los tests no dependan de la red ni de las claves de
 * Google: la implementacion real valida la firma contra sus claves publicas.
 */
public interface GoogleTokenVerifier {

    /**
     * @throws com.checkout.backend.exceptions.UnauthenticatedException si el token no es valido
     * @throws GoogleSignInUnavailableException si el backend no tiene configurado el client ID
     */
    GoogleIdentity verify(String idToken);

}
