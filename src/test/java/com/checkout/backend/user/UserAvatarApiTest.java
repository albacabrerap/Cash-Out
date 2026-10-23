package com.checkout.backend.user;

import com.checkout.backend.support.DatabaseCleaner;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class UserAvatarApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F'};

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        token = register();
    }

    @AfterEach
    void cleanUp() {
        DatabaseCleaner.clean(jdbc);
    }

    @Test
    @DisplayName("Sube la foto: la cuenta dice cuando cambio y la foto se descarga con su tipo")
    void uploadsAvatar() throws Exception {
        as(get("/api/v1/users/me")).andExpect(jsonPath("$.avatarUpdatedAt").doesNotExist());

        upload("foto.png", "image/png", PNG)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUpdatedAt").isNotEmpty());

        as(get("/api/v1/users/me")).andExpect(jsonPath("$.avatarUpdatedAt").isNotEmpty());
        as(get("/api/v1/users/me/avatar"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control", "no-cache, private"))
                .andExpect(content().bytes(PNG));
    }

    @Test
    @DisplayName("El tipo sale de los bytes: un JPEG que dice ser PNG se guarda como JPEG y un SVG no entra")
    void typeComesFromBytes() throws Exception {
        upload("foto.png", "image/png", JPEG).andExpect(status().isOk());
        as(get("/api/v1/users/me/avatar")).andExpect(content().contentType("image/jpeg"));

        upload("foto.png", "image/png", "<svg onload=\"alert(1)\"/>".getBytes())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La foto tiene que ser JPG, PNG o GIF."));
    }

    @Test
    @DisplayName("Una foto de mas de 2 MB se rechaza")
    void rejectsLargeFiles() throws Exception {
        byte[] big = Arrays.copyOf(PNG, 2 * 1024 * 1024 + 1);

        upload("foto.png", "image/png", big)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La foto pesa más de 2 MB."));
    }

    @Test
    @DisplayName("Quitar la foto deja la cuenta sin foto y la descarga responde 404")
    void removesAvatar() throws Exception {
        upload("foto.png", "image/png", PNG).andExpect(status().isOk());

        as(delete("/api/v1/users/me/avatar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUpdatedAt").doesNotExist());
        as(get("/api/v1/users/me/avatar")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Sin sesion no se descarga ninguna foto")
    void requiresSession() throws Exception {
        mockMvc.perform(get("/api/v1/users/me/avatar")).andExpect(status().isUnauthorized());
    }

    private ResultActions upload(String name, String type, byte[] bytes) throws Exception {
        return as(multipart(HttpMethod.PUT, "/api/v1/users/me/avatar").file(new MockMultipartFile("file", name, type, bytes)));
    }

    private ResultActions as(AbstractMockHttpServletRequestBuilder<?> builder) throws Exception {
        return mockMvc.perform(builder.header("Authorization", "Bearer " + token));
    }

    private String register() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ana\",\"email\":\"ana" + UUID.randomUUID()
                                + "@utec.edu.pe\",\"password\":\"Secreto123!\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("accessToken").asString();
    }

}
