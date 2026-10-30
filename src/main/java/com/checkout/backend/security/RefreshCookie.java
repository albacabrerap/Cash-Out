package com.checkout.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * El token de refresco de la web viaja en una cookie que JavaScript no puede
 * leer: si un dia entra un XSS, no se lo puede llevar.
 *
 * Solo la pide quien manda {@code X-Session-Mode: cookie} (la web). El movil
 * no lo manda y sigue recibiendo el token en el cuerpo, porque lo guarda en el
 * almacenamiento seguro del telefono.
 *
 * SameSite=Strict y la ruta /api/v1/auth: el navegador solo la manda a login,
 * refresco y logout, y nunca desde otro sitio. Por eso refrescar con cookie no
 * abre un CSRF. Exige que la web y la API compartan sitio (el proxy de Vite en
 * desarrollo y el de Vercel en produccion).
 */
@Component
public class RefreshCookie {

    public static final String NAME = "cashout_refresh";
    public static final String MODE_HEADER = "X-Session-Mode";
    static final String PATH = "/api/v1/auth";

    private final boolean secure;
    private final JwtProperties jwt;

    /** {@code secure} solo se apaga para probar por HTTP fuera de localhost: los navegadores ya tratan localhost como seguro. */
    public RefreshCookie(@Value("${auth.refresh-cookie.secure:true}") boolean secure, JwtProperties jwt) {
        this.secure = secure;
        this.jwt = jwt;
    }

    public static boolean requested(HttpServletRequest request) {
        return "cookie".equalsIgnoreCase(request.getHeader(MODE_HEADER));
    }

    public ResponseCookie issue(String refreshToken) {
        return base(refreshToken).maxAge(jwt.expirationRefresh()).build();
    }

    public ResponseCookie clear() {
        return base("").maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value).httpOnly(true).secure(secure).sameSite("Strict").path(PATH);
    }

}
