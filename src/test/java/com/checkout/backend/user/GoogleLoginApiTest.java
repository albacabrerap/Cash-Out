package com.checkout.backend.user;

import com.checkout.backend.exceptions.UnauthenticatedException;
import com.checkout.backend.security.google.GoogleIdentity;
import com.checkout.backend.security.google.GoogleSignInUnavailableException;
import com.checkout.backend.security.google.GoogleTokenVerifier;
import com.checkout.backend.support.DatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class GoogleLoginApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    // La firma contra las claves de Google se prueba con el token real; aqui importa que hace la API con el resultado.
    @MockitoBean private GoogleTokenVerifier verifier;

    @AfterEach
    void cleanUp() {
        DatabaseCleaner.clean(jdbc);
    }

    @Test
    @DisplayName("La primera vez crea la cuenta con sus monederos; la segunda entra a la misma")
    void createsAccountThenSignsIn() throws Exception {
        google("sub-1", "lucia@gmail.com", true, "Lucía Pérez");

        String token = accessToken(signIn().andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value("lucia@gmail.com"))
                .andExpect(jsonPath("$.user.name").value("Lucía Pérez"))
                .andExpect(jsonPath("$.user.googleOnly").value(true))
                .andExpect(jsonPath("$.user.googleLinked").value(true))
                .andExpect(jsonPath("$.refreshToken").isNotEmpty()));

        mockMvc.perform(get("/api/v1/token-wallet").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        signIn().andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("Un correo ya registrado y confirmado se enlaza, y la contrasena sigue sirviendo")
    void linksExistingAccountByVerifiedEmail() throws Exception {
        register("Ana@Gmail.com");
        jdbc.update("UPDATE users SET email_verified = true");
        google("sub-ana", "ana@gmail.com", true, "Ana");

        signIn().andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value("Ana@Gmail.com"))
                .andExpect(jsonPath("$.user.googleOnly").value(false))
                .andExpect(jsonPath("$.user.googleLinked").value(true));

        assertThat(jdbc.queryForObject("SELECT google_subject FROM users", String.class)).isEqualTo("sub-ana");
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"Ana@Gmail.com\",\"password\":\"Secreto123!\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Si alguien registro el correo ajeno sin confirmarlo, el dueño entra con Google y esa contrasena deja de servir")
    void googleTakesOverAnUnverifiedAccount() throws Exception {
        String intruder = register("victima@gmail.com");
        String intruderRefresh = JSON.readTree(intruder).get("refreshToken").asString();
        google("sub-dueno", "victima@gmail.com", true, "Dueño");

        signIn().andExpect(status().isOk())
                .andExpect(jsonPath("$.user.googleOnly").value(true))
                .andExpect(jsonPath("$.user.emailVerified").value(true));

        assertThat(jdbc.queryForObject("SELECT password_hash FROM users", String.class)).isNull();
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"victima@gmail.com\",\"password\":\"Secreto123!\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + intruderRefresh + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Una cuenta creada con Google no tiene contrasena: no entra con una ni la puede cambiar")
    void googleAccountHasNoPassword() throws Exception {
        google("sub-3", "carla@gmail.com", true, "Carla");
        String token = accessToken(signIn().andExpect(status().isOk()));

        assertThat(jdbc.queryForObject("SELECT password_hash FROM users", String.class)).isNull();
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"carla@gmail.com\",\"password\":\"Secreto123!\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/users/me/password").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Secreto123!\",\"newPassword\":\"Otro1234!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Tu cuenta entra con Google y no tiene contraseña que cambiar."));
    }

    @Test
    @DisplayName("Sin correo verificado por Google no se entra ni se enlaza nada")
    void rejectsUnverifiedEmail() throws Exception {
        register("ana@gmail.com");
        google("sub-x", "ana@gmail.com", false, "Ana");

        signIn().andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT google_subject FROM users", String.class)).isNull();
    }

    @Test
    @DisplayName("Un token invalido da 401 y una cuenta bloqueada tampoco entra")
    void rejectsInvalidTokenAndBlockedAccount() throws Exception {
        when(verifier.verify(anyString())).thenThrow(new UnauthenticatedException("No pudimos verificar tu cuenta de Google."));
        signIn().andExpect(status().isUnauthorized());

        google("sub-2", "beto@gmail.com", true, "Beto");
        signIn().andExpect(status().isOk());
        jdbc.update("UPDATE users SET status = 'BLOCKED'");
        signIn().andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Sin client ID configurado responde 503 con un mensaje para el usuario")
    void unavailableWithoutClientId() throws Exception {
        when(verifier.verify(anyString())).thenThrow(new GoogleSignInUnavailableException());

        signIn().andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("El inicio con Google no está disponible por ahora. Usa tu correo y contraseña."));
    }

    @Test
    @DisplayName("Sin credential el pedido no es valido")
    void requiresCredential() throws Exception {
        mockMvc.perform(post("/api/v1/auth/google").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    private void google(String subject, String email, boolean verified, String name) {
        doReturn(new GoogleIdentity(subject, email, verified, name)).when(verifier).verify(anyString());
    }

    private ResultActions signIn() throws Exception {
        return mockMvc.perform(post("/api/v1/auth/google").contentType(MediaType.APPLICATION_JSON)
                .content("{\"credential\":\"id-token-de-google\"}"));
    }

    private String accessToken(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString()).get("accessToken").asString();
    }

    /** Devuelve el cuerpo de la respuesta, con los tokens. */
    private String register(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ana\",\"email\":\"" + email + "\",\"password\":\"Secreto123!\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

}
