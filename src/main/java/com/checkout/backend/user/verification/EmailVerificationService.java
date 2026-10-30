package com.checkout.backend.user.verification;

import com.checkout.backend.email.EmailDetails;
import com.checkout.backend.email.EmailService;
import com.checkout.backend.exceptions.InvalidRequestException;
import com.checkout.backend.security.OpaqueTokens;
import com.checkout.backend.user.model.User;
import com.checkout.backend.user.repository.UserRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manda y valida los enlaces para confirmar el correo.
 *
 * Con auth.require-verified-email=true una cuenta sin confirmar no puede
 * entrar. Se puede apagar en desarrollo, donde no siempre hay SMTP: el enlace
 * igual se manda, pero no se exige.
 *
 * Las fechas usan el reloj de la JVM, igual que @CreationTimestamp, para que
 * comparar el vencimiento y el ultimo envio no dependa de la zona horaria.
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    static final Duration VALIDITY = Duration.ofHours(24);
    /** Pedir otro enlace antes de esto no sirve: el anterior todavia esta llegando. */
    static final Duration RESEND_GAP = Duration.ofMinutes(1);
    private static final int TOKEN_BYTES = 32;

    private final EmailVerificationTokenRepository tokens;
    private final UserRepository users;
    private final EmailService email;
    private final String frontendUrl;
    private final boolean required;
    private final SecureRandom random = new SecureRandom();

    public EmailVerificationService(EmailVerificationTokenRepository tokens, UserRepository users, EmailService email,
                                    @Value("${app.frontend-url}") String frontendUrl,
                                    @Value("${auth.require-verified-email:true}") boolean required) {
        this.tokens = tokens;
        this.users = users;
        this.email = email;
        this.frontendUrl = frontendUrl.endsWith("/") ? frontendUrl.substring(0, frontendUrl.length() - 1) : frontendUrl;
        this.required = required;
    }

    public boolean required() {
        return required;
    }

    /** Manda un enlace nuevo, salvo que el correo ya este confirmado o el ultimo se haya mandado hace segundos. */
    @Transactional
    public void send(User user) {
        if (user.isEmailVerified()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        boolean recent = tokens.findTopByUserIdOrderByCreatedAtDesc(user.getId())
                .map(last -> last.getCreatedAt().isAfter(now.minus(RESEND_GAP)))
                .orElse(false);
        if (recent) {
            return;
        }

        String token = OpaqueTokens.generate(random, TOKEN_BYTES);
        EmailVerificationToken saved = new EmailVerificationToken();
        saved.setUserId(user.getId());
        saved.setTokenHash(OpaqueTokens.hash(token));
        saved.setExpiresAt(now.plus(VALIDITY));
        tokens.save(saved);

        String link = frontendUrl + "/verificar-correo?token=" + token;
        email.send(new EmailDetails(user.getEmail(), "Confirma tu correo en Cash-Out", """
                Hola, %s:

                Para empezar a usar Cash-Out, confirma tu correo con este enlace:

                %s

                El enlace vence en 24 horas y sirve una sola vez. Si no creaste esta cuenta, ignora este correo.
                """.formatted(user.getName(), link)))
                .exceptionally(error -> {
                    log.warn("No se pudo mandar el correo de verificacion al usuario {}: {}", user.getId(),
                            error.getMessage());
                    return null;
                });
    }

    /** Marca el correo como confirmado. El enlace sirve una vez y dentro de las 24 horas. */
    @Transactional
    public User verify(String token) {
        LocalDateTime now = LocalDateTime.now();
        EmailVerificationToken stored = tokens.findByTokenHash(OpaqueTokens.hash(token))
                .filter(candidate -> candidate.getUsedAt() == null && candidate.getExpiresAt().isAfter(now))
                .orElseThrow(() -> new InvalidRequestException("El enlace no es válido o ya venció. Pide uno nuevo."));
        stored.setUsedAt(now);
        User user = users.findById(stored.getUserId())
                .orElseThrow(() -> new InvalidRequestException("El enlace no es válido o ya venció. Pide uno nuevo."));
        user.setEmailVerified(true);
        return user;
    }

}
