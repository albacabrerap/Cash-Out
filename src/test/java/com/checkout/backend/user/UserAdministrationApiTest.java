package com.checkout.backend.user;

import com.checkout.backend.support.DatabaseCleaner;
import com.checkout.backend.user.model.Role;
import com.checkout.backend.user.model.User;
import com.checkout.backend.user.repository.UserRepository;
import java.util.EnumSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cambio de roles y bloqueo de cuentas desde la administracion.
 *
 * Corre con la cadena de seguridad real por lo mismo que RemainingModulesApiTest:
 * lo que importa es que un bloqueo corte de verdad las sesiones abiertas, y eso
 * depende del filtro JWT, de los refresh tokens y de CurrentUserProvider juntos.
 * Sin @Transactional: cada test commitea y la base se limpia al final.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserAdministrationApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "Secreto123!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    private JsonNode ana;
    private long anaId;
    private long adminId;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        ana = JSON.readTree(register("ana@utec.edu.pe"));
        anaId = ana.get("user").get("id").asLong();

        adminId = userRepository.save(User.builder()
                .name("Alba")
                .email("alba@utec.edu.pe")
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .roles(EnumSet.of(Role.ADMIN))
                .build()).getId();
        adminToken = JSON.readTree(login("alba@utec.edu.pe")).get("accessToken").asString();
    }

    @AfterEach
    void cleanUp() {
        DatabaseCleaner.clean(jdbc);
    }

    @Test
    @DisplayName("Un estudiante no puede cambiar roles ni bloquear a nadie")
    void aStudentCannotAdministrate() throws Exception {
        String anaToken = ana.get("accessToken").asString();

        mockMvc.perform(withToken(put("/api/v1/users/" + adminId + "/roles"), anaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"USER\"]}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(withToken(put("/api/v1/users/" + adminId + "/status"), anaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BLOCKED\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Cambiar roles reemplaza el conjunto y obliga a renovar la sesion")
    void changingRolesRevokesSessions() throws Exception {
        setRoles(anaId, "[\"USER\",\"ADMIN\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", containsInAnyOrder("USER", "ADMIN")));

        // El refresh token emitido con los roles viejos ya no renueva.
        refresh(ana.get("refreshToken").asString()).andExpect(status().isUnauthorized());

        // Al volver a entrar, el token nuevo ya trae ADMIN.
        String renewed = JSON.readTree(login("ana@utec.edu.pe")).get("accessToken").asString();
        mockMvc.perform(withToken(get("/api/v1/users"), renewed)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Los roles no pueden quedar vacios, y GUEST no se combina con otros")
    void rolesAreValidated() throws Exception {
        setRoles(anaId, "[]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("roles"));
        setRoles(anaId, "[\"GUEST\",\"USER\"]").andExpect(status().isBadRequest());
        setRoles(anaId, "[\"GUEST\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", hasSize(1)));
    }

    @Test
    @DisplayName("Nadie cambia sus propios roles ni su propio estado")
    void anAdminCannotTouchTheirOwnAccount() throws Exception {
        setRoles(adminId, "[\"USER\"]").andExpect(status().isBadRequest());
        setStatus(adminId, "BLOCKED").andExpect(status().isBadRequest());

        // Sigue siendo administrador.
        mockMvc.perform(admin(get("/api/v1/users"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Bloquear corta el token vigente, la renovacion y el login")
    void blockingEndsEveryWayIn() throws Exception {
        String anaToken = ana.get("accessToken").asString();
        mockMvc.perform(withToken(get("/api/v1/users/me"), anaToken)).andExpect(status().isOk());

        setStatus(anaId, "BLOCKED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"));

        mockMvc.perform(withToken(get("/api/v1/users/me"), anaToken)).andExpect(status().isUnauthorized());
        refresh(ana.get("refreshToken").asString()).andExpect(status().isUnauthorized());
        loginAttempt("ana@utec.edu.pe").andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Reactivar devuelve el acceso, tambien a una cuenta dada de baja")
    void reactivatingRestoresAccess() throws Exception {
        setStatus(anaId, "BLOCKED").andExpect(status().isOk());
        setStatus(anaId, "ACTIVE").andExpect(status().isOk());
        loginAttempt("ana@utec.edu.pe").andExpect(status().isOk());

        mockMvc.perform(withToken(delete("/api/v1/users/me"), ana.get("accessToken").asString()))
                .andExpect(status().isNoContent());
        loginAttempt("ana@utec.edu.pe").andExpect(status().isUnauthorized());

        setStatus(anaId, "ACTIVE").andExpect(status().isOk());
        loginAttempt("ana@utec.edu.pe").andExpect(status().isOk());
    }

    @Test
    @DisplayName("La baja es del propio usuario y una cuenta inexistente da 404")
    void inactiveIsNotAnAdminChoice() throws Exception {
        setStatus(anaId, "INACTIVE").andExpect(status().isBadRequest());
        setStatus(999_999L, "BLOCKED").andExpect(status().isNotFound());
        setRoles(999_999L, "[\"USER\"]").andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------

    private ResultActions setRoles(long userId, String rolesJson) throws Exception {
        return mockMvc.perform(admin(put("/api/v1/users/" + userId + "/roles"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roles\":" + rolesJson + "}"));
    }

    private ResultActions setStatus(long userId, String status) throws Exception {
        return mockMvc.perform(admin(put("/api/v1/users/" + userId + "/status"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + status + "\"}"));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"));
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        return withToken(request, adminToken);
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private String register(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Ana","email":"%s","password":"%s"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private String login(String email) throws Exception {
        return loginAttempt(email).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private ResultActions loginAttempt(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(email, PASSWORD)));
    }

}
