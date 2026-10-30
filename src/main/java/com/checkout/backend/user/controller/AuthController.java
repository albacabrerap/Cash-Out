package com.checkout.backend.user.controller;

import com.checkout.backend.exceptions.UnauthenticatedException;
import com.checkout.backend.security.RefreshCookie;
import com.checkout.backend.user.dto.AuthResponse;
import com.checkout.backend.user.dto.GoogleLoginRequest;
import com.checkout.backend.user.dto.LoginRequest;
import com.checkout.backend.user.dto.RefreshTokenRequest;
import com.checkout.backend.user.dto.RegisterUserRequest;
import com.checkout.backend.user.dto.ResendVerificationRequest;
import com.checkout.backend.user.dto.VerifyEmailRequest;
import com.checkout.backend.user.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entrada y salida de la aplicacion.
 *
 * Es el unico controller con rutas publicas, declaradas en SecurityConfig. Son
 * las que un usuario sin sesion necesita para conseguir una; todo lo demas exige
 * token.
 *
 * /auth no nombra un recurso sino una accion, y eso se aparta de la convencion
 * que sigue el resto de la API. Es deliberado: iniciar sesion no crea ni
 * modifica una entidad, y forzarlo a parecer un recurso — POST /sessions —
 * describiria peor lo que pasa de lo que ayudaria la coherencia.
 *
 * El token de refresco sale en el cuerpo o, si el cliente manda
 * X-Session-Mode: cookie, en una cookie HttpOnly (ver RefreshCookie). Refresh y
 * logout lo aceptan de las dos formas.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final RefreshCookie refreshCookie;

    public AuthController(AuthService authService, RefreshCookie refreshCookie) {
        this.authService = authService;
        this.refreshCookie = refreshCookie;
    }

    /**
     * POST /api/v1/auth/register
     *
     * 201 porque crea un usuario. No lleva Location: el recurso creado solo es
     * accesible en /users/me, que no depende de un id. Si hay que verificar el
     * correo, el cuerpo trae al usuario sin tokens.
     */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterUserRequest request,
                                                 HttpServletRequest http) {
        return respond(HttpStatus.CREATED, authService.register(request), http);
    }

    /**
     * POST /api/v1/auth/login
     *
     * 200 y no 201: no crea nada del lado del servidor. La sesion vive en el
     * token que se lleva el cliente. 403 si la cuenta no verifico su correo.
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return respond(HttpStatus.OK, authService.login(request), http);
    }

    /**
     * POST /api/v1/auth/google
     *
     * "Continuar con Google": recibe el ID token de Google, entra o crea la
     * cuenta, y responde con los mismos tokens que el login.
     */
    @PostMapping("/google")
    public ResponseEntity<AuthResponse> google(@Valid @RequestBody GoogleLoginRequest request,
                                               HttpServletRequest http) {
        return respond(HttpStatus.OK, authService.loginWithGoogle(request.getCredential()), http);
    }

    /** POST /api/v1/auth/refresh: con el token en el cuerpo (movil) o en la cookie (web). */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody(required = false) RefreshTokenRequest request,
                                                @CookieValue(name = RefreshCookie.NAME, required = false) String cookie,
                                                HttpServletRequest http) {
        String token = presented(request, cookie);
        if (token == null) {
            throw new UnauthenticatedException("No hay una sesión activa.");
        }
        return respond(HttpStatus.OK, authService.refresh(token), http);
    }

    /**
     * POST /api/v1/auth/logout
     *
     * 204 sin cuerpo. Responde igual aunque el token no exista: decir lo
     * contrario permitiria averiguar que tokens son validos sin tener ninguno.
     * Siempre borra la cookie.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody(required = false) RefreshTokenRequest request,
                                       @CookieValue(name = RefreshCookie.NAME, required = false) String cookie) {
        String token = presented(request, cookie);
        if (token != null) {
            authService.logout(token);
        }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, refreshCookie.clear().toString()).build();
    }

    /** POST /api/v1/auth/verify-email: el enlace del correo. Verifica y deja la sesion abierta. */
    @PostMapping("/verify-email")
    public ResponseEntity<AuthResponse> verifyEmail(@Valid @RequestBody VerifyEmailRequest request,
                                                    HttpServletRequest http) {
        return respond(HttpStatus.OK, authService.verifyEmail(request.token()), http);
    }

    /**
     * POST /api/v1/auth/resend-verification
     *
     * 202 siempre, exista o no la cuenta: si no, serviria para averiguar que
     * correos estan registrados.
     */
    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        authService.resendVerification(request.email());
        return ResponseEntity.accepted().build();
    }

    private static String presented(RefreshTokenRequest body, String cookie) {
        String token = body != null && body.getRefreshToken() != null ? body.getRefreshToken() : cookie;
        return token == null || token.isBlank() ? null : token;
    }

    /** Si la web pidio cookie, el token de refresco sale de la respuesta y va a la cookie. */
    private ResponseEntity<AuthResponse> respond(HttpStatus status, AuthResponse auth, HttpServletRequest http) {
        if (!RefreshCookie.requested(http) || auth.getRefreshToken() == null) {
            return ResponseEntity.status(status).body(auth);
        }
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, refreshCookie.issue(auth.getRefreshToken()).toString())
                .body(auth.toBuilder().refreshToken(null).build());
    }

}
