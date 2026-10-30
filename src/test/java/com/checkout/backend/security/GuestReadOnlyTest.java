package com.checkout.backend.security;

import com.checkout.backend.support.DatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GUEST mira pero no escribe: cualquier metodo que no sea GET o HEAD le da 403. */
@SpringBootTest
@AutoConfigureMockMvc
class GuestReadOnlyTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        DatabaseCleaner.clean(jdbc);
    }

    @Test
    @DisplayName("Un invitado puede leer sus datos pero no crear ni cambiar nada; puede cerrar sesion")
    void guestCanOnlyRead() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Gina\",\"email\":\"gina@utec.edu.pe\",\"password\":\"Secreto123!\"}"));
        jdbc.update("UPDATE user_roles SET role = 'GUEST'");
        String body = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"gina@utec.edu.pe\",\"password\":\"Secreto123!\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = "Bearer " + JSON.readTree(body).get("accessToken").asString();
        String refresh = JSON.readTree(body).get("refreshToken").asString();

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", token)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/expenses").header("Authorization", token)).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/expenses").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"FOOD\",\"amount\":12,\"date\":\"2026-10-20\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/users/me").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Otra\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isNoContent());
    }

}
