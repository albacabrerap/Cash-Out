package com.checkout.backend.security;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import jakarta.servlet.DispatcherType;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Cadena de seguridad de la API.
 *
 * @EnableMethodSecurity activa @PreAuthorize sobre metodos. La proteccion por
 * ruta que hay aqui abajo dice quien puede entrar a cada URL; las anotaciones
 * dicen quien puede ejecutar una operacion concreta. Las dos hacen falta: una
 * ruta puede estar abierta a cualquier usuario autenticado y aun asi tener
 * dentro una accion reservada a ADMIN.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    /**
     * Rutas que no exigen token.
     *
     * Son las que un usuario sin sesion necesita para conseguirla, mas la
     * documentacion. Cualquier otra cosa requiere autenticacion por defecto, que
     * es el orden correcto: una ruta nueva nace protegida y se abre a
     * conciencia, en vez de nacer abierta y depender de que alguien se acuerde
     * de cerrarla.
     */
    private static final String[] PUBLIC_PATHS = {
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            // Publico a proposito: el token de refresco que va en el cuerpo ES
            // la credencial. Exigir ademas un token de acceso valido impediria
            // cerrar sesion justo cuando mas hace falta, con la sesion ya
            // caducada, y dejaria el refresh token vivo hasta su expiracion.
            "/api/v1/auth/logout",
            "/",
            "/api/v1/health",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final List<String> allowedOrigins;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          RestAuthenticationEntryPoint authenticationEntryPoint,
                          RestAccessDeniedHandler accessDeniedHandler,
                          @Value("${cors.allowed-origins}") List<String> allowedOrigins) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // Sin CSRF porque no hay cookies de sesion: el token viaja en un
                // encabezado que el navegador no adjunta solo, y sin envio
                // automatico no hay peticion falsificable desde otro sitio.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // STATELESS: el servidor no guarda nada entre peticiones y cada
                // una se autentica sola con su token. Tambien evita que Spring
                // cree una sesion por peticion, que con un cliente sin cookies
                // seria basura acumulandose en memoria.
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        // El preflight del navegador viaja sin token: si se
                        // exigiera autenticacion, el navegador ni llegaria a
                        // mandar la peticion real.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // El SSE de precios en vivo termina en un despacho ASYNC que
                        // ya no trae el token; la peticion original si se autentico.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .anyRequest().authenticated())

                // Sin estas dos lineas, un 401 o un 403 de la cadena de filtros
                // saldrian con el formato de error del contenedor y no con el de
                // la API.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))

                // Antes del filtro de usuario y contrasena: para cuando la
                // cadena llegue ahi, el contexto ya tiene al usuario del token.
                .addFilterBefore(jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class)

                .build();
    }

    /**
     * BCrypt con el coste por defecto (10).
     *
     * Es adaptativo a proposito: el hash guarda su propio coste, asi que subirlo
     * mas adelante no invalida los hashes viejos, que se siguen verificando con
     * el coste con el que se crearon.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * El AuthenticationManager que usa el login. Se toma el que Spring ya
     * construyo con el UserDetailsService y el PasswordEncoder de arriba, en vez
     * de armar uno a mano y arriesgarse a que queden desalineados.
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
            throws Exception {
        return configuration.getAuthenticationManager();
    }

    /**
     * CORS para el front.
     *
     * Los origenes se leen de una propiedad y no estan fijos en el codigo,
     * porque cambian entre local, despliegue de prueba y produccion. No se usa
     * "*": con allowCredentials en true el navegador lo rechaza, y aunque no
     * fuera asi, abrir la API a cualquier origen deja que cualquier pagina haga
     * peticiones en nombre del usuario.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        // Location viaja en los 201 y el navegador no la expone salvo que se
        // declare aqui.
        configuration.setExposedHeaders(List.of("Location"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

}
