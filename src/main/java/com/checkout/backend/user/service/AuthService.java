package com.checkout.backend.user.service;

import com.checkout.backend.exceptions.ForbiddenException;
import com.checkout.backend.exceptions.UnauthenticatedException;
import com.checkout.backend.security.google.GoogleIdentity;
import com.checkout.backend.security.google.GoogleTokenVerifier;
import java.util.Locale;
import com.checkout.backend.exceptions.DuplicateResourceException;
import com.checkout.backend.investment_portfolio.service.PortfolioService;
import com.checkout.backend.savings.service.SavingsService;
import com.checkout.backend.security.JwtTokenProvider;
import com.checkout.backend.token_wallet.refresh_token.service.RefreshTokenService;
import com.checkout.backend.token_wallet.service.TokenWalletService;
import com.checkout.backend.user.dto.AuthResponse;
import com.checkout.backend.user.dto.LoginRequest;
import com.checkout.backend.user.dto.RegisterUserRequest;
import com.checkout.backend.user.dto.UserResponse;
import com.checkout.backend.user.model.Role;
import com.checkout.backend.user.model.User;
import com.checkout.backend.user.model.UserStatus;
import com.checkout.backend.user.repository.UserRepository;
import com.checkout.backend.user.verification.EmailVerificationService;
import java.util.EnumSet;
import org.modelmapper.ModelMapper;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registro, inicio y cierre de sesion.
 *
 * Emite dos tokens con papeles distintos. El de acceso es un JWT corto que viaja
 * en cada peticion y no se puede revocar, porque validarlo contra la base en
 * cada llamada anularia la ventaja de que sea autocontenido. El de refresco es
 * un valor opaco, largo y revocable, que solo aparece al renovar. Esa division
 * es lo que permite tener sesiones largas sin que un token robado sirva para
 * siempre.
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider tokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final TokenWalletService walletService;
    private final SavingsService savingsService;
    private final PortfolioService portfolioService;
    private final ModelMapper mapper;
    private final GoogleTokenVerifier googleVerifier;
    private final EmailVerificationService emailVerification;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager,
                       JwtTokenProvider tokenProvider,
                       RefreshTokenService refreshTokenService,
                       TokenWalletService walletService,
                       SavingsService savingsService,
                       PortfolioService portfolioService,
                       ModelMapper mapper,
                       GoogleTokenVerifier googleVerifier,
                       EmailVerificationService emailVerification) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.tokenProvider = tokenProvider;
        this.refreshTokenService = refreshTokenService;
        this.walletService = walletService;
        this.savingsService = savingsService;
        this.portfolioService = portfolioService;
        this.mapper = mapper;
        this.googleVerifier = googleVerifier;
        this.emailVerification = emailVerification;
    }

    /**
     * Da de alta un usuario y le manda el enlace para confirmar su correo. Si
     * la confirmacion es obligatoria, responde sin tokens: entra al abrir el
     * enlace. Si no, lo deja con la sesion iniciada.
     *
     * El rol y el estado los pone el servidor, no el cuerpo de la peticion.
     * RegisterUserRequest no expone esos campos justamente para que nadie pueda
     * registrarse como ADMIN mandando un JSON con un campo de mas.
     *
     * La contrasena en claro no se guarda ni se registra en ningun log: entra
     * por el request, se convierte en hash y se descarta.
     */
    @Transactional
    public AuthResponse register(RegisterUserRequest request) {
        // Comprobar antes da un mensaje entendible; el UNIQUE de la tabla es la
        // red para dos registros simultaneos, y su violacion termina en el mismo
        // 409 por el manejador global.
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("Ya existe una cuenta con ese correo.");
        }

        User user = userRepository.save(User.builder()
                .name(request.getName())
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .birthDate(request.getBirthDate())
                .roles(EnumSet.of(Role.USER))
                .status(UserStatus.ACTIVE)
                .build());

        // Las tres cuentas 1:1 del usuario se crean aqui, en la misma transaccion
        // que el usuario. Antes se creaban de forma diferida, en el primer acceso a
        // cada modulo, y eso dejaba una carrera: dos peticiones simultaneas de un
        // usuario nuevo podian ver las dos que su monedero no existia, y la que
        // perdia recibia un 409 por el UNIQUE hablando de un conflicto que el
        // usuario no habia provocado.
        //
        // Crearlas al registrar cierra esa ventana, y ademas hace que el estado de
        // un usuario recien creado sea completo en vez de depender de por donde
        // entre primero. Si falla cualquiera de las tres, no queda usuario a medias:
        // es una sola transaccion.
        createAccounts(user);
        emailVerification.send(user);
        if (emailVerification.required()) {
            return AuthResponse.builder()
                    .user(mapper.map(user, UserResponse.class))
                    .emailVerificationRequired(true)
                    .build();
        }
        return buildAuthResponse(user);
    }

    /**
     * Entra, o se registra, con la cuenta de Google.
     *
     * La cuenta se busca primero por el "sub" de Google, que no cambia. Si no
     * hay, por el correo: alguien que ya se registro con contrasena queda
     * enlazado en vez de recibir un 409. Eso solo se hace con un correo que
     * Google confirma como verificado; si no, cualquiera podria apropiarse de
     * una cuenta poniendo un correo ajeno en su cuenta de Google.
     *
     * La cuenta nueva no tiene contrasena: Google no la comparte, asi que solo
     * entra con Google. La que ya tenia contrasena la conserva y suma Google.
     */
    @Transactional
    public AuthResponse loginWithGoogle(String credential) {
        GoogleIdentity google = googleVerifier.verify(credential);
        if (!google.emailVerified() || google.email() == null) {
            throw new UnauthenticatedException("Google no confirmó tu correo. Verifícalo en tu cuenta de Google o regístrate con contraseña.");
        }

        User user = userRepository.findByGoogleSubject(google.subject())
                .or(() -> userRepository.findByEmailIgnoreCase(google.email()).map(this::linkGoogle))
                .orElseGet(() -> registerFromGoogle(google));
        if (user.getGoogleSubject() == null) {
            user.setGoogleSubject(google.subject());
        }

        // Lo mismo que el login con contrasena: una cuenta bloqueada o dada de baja no entra.
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new UnauthenticatedException("Credenciales inválidas.");
        }
        return buildAuthResponse(user);
    }

    /**
     * Enlaza Google a una cuenta que ya existia con ese correo. Si esa cuenta
     * nunca confirmo el correo, quien la creo pudo ser otra persona usando un
     * correo ajeno, con una contrasena que conoce. Google acaba de probar quien
     * es el dueño: se borra esa contrasena y se cierran sus sesiones, y la
     * cuenta queda solo con Google.
     */
    private User linkGoogle(User existing) {
        if (!existing.isEmailVerified()) {
            existing.setPasswordHash(null);
            refreshTokenService.revokeAll(existing);
            existing.setEmailVerified(true);
        }
        return existing;
    }

    private User registerFromGoogle(GoogleIdentity google) {
        String name = google.name() == null || google.name().isBlank()
                ? google.email().substring(0, google.email().indexOf('@'))
                : google.name().strip();
        User user = userRepository.save(User.builder()
                .name(name.length() > 120 ? name.substring(0, 120) : name)
                .email(google.email().toLowerCase(Locale.ROOT))
                .googleSubject(google.subject())
                .emailVerified(true)
                .roles(EnumSet.of(Role.USER))
                .status(UserStatus.ACTIVE)
                .build());
        createAccounts(user);
        return user;
    }

    private void createAccounts(User user) {
        walletService.getOrCreate(user);
        savingsService.getOrCreate(user);
        portfolioService.getOrCreate(user);
    }

    /**
     * Inicia sesion.
     *
     * La comparacion la hace el AuthenticationManager y no este metodo: ahi se
     * hace en tiempo constante y se aplican de paso los estados de la cuenta,
     * bloqueada o deshabilitada, que UserPrincipal traduce.
     *
     * Cualquier fallo sale como AuthenticationException y el manejador global lo
     * convierte en un 401 sin detalle. Distinguir "no existe" de "contrasena
     * incorrecta" convertiria el login en un verificador de que correos estan
     * registrados.
     */
    @Transactional
    public AuthResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword()));

        User user = userRepository.findByEmail(authentication.getName()).orElseThrow();
        // Despues de comprobar la contrasena: asi este 403 no revela que correos estan registrados.
        if (emailVerification.required() && !user.isEmailVerified()) {
            throw new ForbiddenException("Confirma tu correo para entrar: abre el enlace que te enviamos.");
        }
        return buildAuthResponse(user);
    }

    /** Abre el enlace del correo: confirma la cuenta y deja la sesion iniciada. */
    @Transactional
    public AuthResponse verifyEmail(String token) {
        User user = emailVerification.verify(token);
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new UnauthenticatedException("Credenciales inválidas.");
        }
        return buildAuthResponse(user);
    }

    /** Manda otro enlace. No avisa si el correo no existe o ya esta confirmado, para no revelarlo. */
    @Transactional
    public void resendVerification(String email) {
        userRepository.findByEmailIgnoreCase(email)
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .ifPresent(emailVerification::send);
    }

    /**
     * Cambia un token de refresco por un par nuevo.
     *
     * La rotacion y la deteccion de reutilizacion viven en RefreshTokenService.
     */
    @Transactional
    public AuthResponse refresh(String refreshToken) {
        return buildAuthResponse(refreshTokenService.rotate(refreshToken));
    }

    /**
     * Cierra la sesion revocando el token de refresco.
     *
     * El token de acceso sigue siendo valido hasta que expire: es el precio de
     * no consultar la base en cada peticion. Por eso su vida es de minutos.
     */
    @Transactional
    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken);
    }

    private AuthResponse buildAuthResponse(User user) {
        return AuthResponse.builder()
                .accessToken(tokenProvider.generateAccessToken(user))
                .refreshToken(refreshTokenService.issue(user))
                .tokenType("Bearer")
                .expiresIn(tokenProvider.accessTokenExpiresInSeconds())
                .user(mapper.map(user, UserResponse.class))
                .build();
    }

}
