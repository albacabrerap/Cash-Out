package com.checkout.backend.user;

import com.checkout.backend.email.EmailDetails;
import com.checkout.backend.email.EmailService;
import com.checkout.backend.support.DatabaseCleaner;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Con la verificacion obligatoria: sin abrir el enlace del correo, la cuenta no entra. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "auth.require-verified-email=true")
class EmailVerificationApiTest {

    private static final Pattern LINK = Pattern.compile("http://localhost:5173/verificar-correo\\?token=([\\w-]+)");
    private static final String LOGIN = "{\"email\":\"ana@utec.edu.pe\",\"password\":\"Secreto123!\"}";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private EmailService email;

    @BeforeEach
    void setUp() {
        when(email.send(any())).thenReturn(CompletableFuture.completedFuture(null));
    }

    @AfterEach
    void cleanUp() {
        DatabaseCleaner.clean(jdbc);
    }

    @Test
    @DisplayName("Registrarse manda el enlace y no da tokens; con el enlace se entra, y el enlace sirve una vez")
    void verifyWithTheLink() throws Exception {
        send("/api/v1/auth/register", "{\"name\":\"Ana\",\"email\":\"ana@utec.edu.pe\",\"password\":\"Secreto123!\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.emailVerificationRequired").value(true))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.user.emailVerified").value(false));
        send("/api/v1/auth/login", LOGIN).andExpect(status().isForbidden());

        String token = sentToken();
        send("/api/v1/auth/verify-email", "{\"token\":\"" + token + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.emailVerified").value(true));
        send("/api/v1/auth/login", LOGIN).andExpect(status().isOk());

        send("/api/v1/auth/verify-email", "{\"token\":\"" + token + "\"}").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT token_hash FROM email_verification_tokens", String.class))
                .hasSize(64).isNotEqualTo(token);
    }

    @Test
    @DisplayName("Una contrasena incorrecta da 401 aunque la cuenta no este confirmada: el 403 no revela correos")
    void wrongPasswordStillLooksTheSame() throws Exception {
        register();
        send("/api/v1/auth/login", "{\"email\":\"ana@utec.edu.pe\",\"password\":\"Otra1234!\"}")
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Un enlace vencido no sirve; reenviar manda otro, pero no dos seguidos")
    void expiredLinkAndResend() throws Exception {
        register();
        String token = sentToken();
        jdbc.update("UPDATE email_verification_tokens SET expires_at = DATEADD('HOUR', -1, CURRENT_TIMESTAMP)");
        send("/api/v1/auth/verify-email", "{\"token\":\"" + token + "\"}").andExpect(status().isBadRequest());

        clearInvocations(email);
        send("/api/v1/auth/resend-verification", "{\"email\":\"ana@utec.edu.pe\"}").andExpect(status().isAccepted());
        verify(email, never()).send(any());

        jdbc.update("UPDATE email_verification_tokens SET created_at = DATEADD('MINUTE', -2, CURRENT_TIMESTAMP)");
        send("/api/v1/auth/resend-verification", "{\"email\":\"ANA@utec.edu.pe\"}").andExpect(status().isAccepted());
        send("/api/v1/auth/verify-email", "{\"token\":\"" + sentToken() + "\"}").andExpect(status().isOk());
    }

    @Test
    @DisplayName("Reenviar a un correo que no existe responde igual y no manda nada")
    void resendDoesNotRevealAccounts() throws Exception {
        send("/api/v1/auth/resend-verification", "{\"email\":\"nadie@utec.edu.pe\"}").andExpect(status().isAccepted());
        verify(email, never()).send(any());
    }

    private void register() throws Exception {
        send("/api/v1/auth/register", "{\"name\":\"Ana\",\"email\":\"ana@utec.edu.pe\",\"password\":\"Secreto123!\"}")
                .andExpect(status().isCreated());
    }

    /** El token del ultimo correo enviado. */
    private String sentToken() {
        ArgumentCaptor<EmailDetails> sent = ArgumentCaptor.forClass(EmailDetails.class);
        verify(email, times(1)).send(sent.capture());
        assertThat(sent.getValue().recipient()).isEqualTo("ana@utec.edu.pe");
        Matcher link = LINK.matcher(sent.getValue().body());
        assertThat(link.find()).isTrue();
        clearInvocations(email);
        return link.group(1);
    }

    private ResultActions send(String path, String body) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

}
