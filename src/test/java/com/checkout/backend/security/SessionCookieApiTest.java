package com.checkout.backend.security;

import com.checkout.backend.support.DatabaseCleaner;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** La web guarda el token de refresco en una cookie HttpOnly; el movil lo sigue recibiendo en el cuerpo. */
@SpringBootTest
@AutoConfigureMockMvc
class SessionCookieApiTest {

    private static final String LOGIN = "{\"email\":\"ana@utec.edu.pe\",\"password\":\"Secreto123!\"}";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        DatabaseCleaner.clean(jdbc);
    }

    @Test
    @DisplayName("Con X-Session-Mode: cookie el token de refresco va en una cookie HttpOnly y no en el cuerpo")
    void webGetsAnHttpOnlyCookie() throws Exception {
        MockHttpServletResponse response = register(true);

        String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).startsWith(RefreshCookie.NAME + "=")
                .contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/v1/auth", "Max-Age=");
        assertThat(response.getContentAsString()).contains("accessToken").doesNotContain("refreshToken");
    }

    @Test
    @DisplayName("El movil, sin la cabecera, recibe el token en el cuerpo y ninguna cookie, como siempre")
    void mobileKeepsTheTokenInTheBody() throws Exception {
        MockHttpServletResponse response = register(false);

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(response.getContentAsString()).contains("refreshToken");
    }

    @Test
    @DisplayName("Refrescar con la cookie la rota; la vieja ya no sirve y el logout la borra")
    void refreshRotatesTheCookie() throws Exception {
        Cookie first = cookieOf(register(true));

        MockHttpServletResponse refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
                        .header(RefreshCookie.MODE_HEADER, "cookie").cookie(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andReturn().getResponse();
        Cookie second = cookieOf(refreshed);
        assertThat(second.getValue()).isNotEqualTo(first.getValue());

        // Reusar la anterior es una señal de robo: se revoca la sesion entera.
        mockMvc.perform(post("/api/v1/auth/refresh").cookie(first)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/refresh").cookie(second)).andExpect(status().isUnauthorized());

        Cookie third = cookieOf(mockMvc.perform(post("/api/v1/auth/login").header(RefreshCookie.MODE_HEADER, "cookie")
                .contentType(MediaType.APPLICATION_JSON).content(LOGIN)).andReturn().getResponse());
        mockMvc.perform(post("/api/v1/auth/logout").cookie(third))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
        mockMvc.perform(post("/api/v1/auth/refresh").cookie(third)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Sin cookie ni cuerpo no hay sesion que refrescar")
    void refreshWithoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/logout")).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Las respuestas de la API llevan CSP y Referrer-Policy, y HSTS cuando llegaron por HTTPS")
    void securityHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
        mockMvc.perform(get("/api/v1/health").secure(true))
                .andExpect(header().exists("Strict-Transport-Security"));
    }

    private MockHttpServletResponse register(boolean cookie) throws Exception {
        var request = post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Ana\",\"email\":\"ana@utec.edu.pe\",\"password\":\"Secreto123!\"}");
        if (cookie) {
            request.header(RefreshCookie.MODE_HEADER, "cookie");
        }
        return mockMvc.perform(request).andExpect(status().isCreated()).andReturn().getResponse();
    }

    private static Cookie cookieOf(MockHttpServletResponse response) {
        Cookie cookie = response.getCookie(RefreshCookie.NAME);
        assertThat(cookie).isNotNull();
        return cookie;
    }

}
