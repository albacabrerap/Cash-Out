package com.checkout.backend.projection;

import com.checkout.backend.support.DatabaseCleaner;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/v1/projections/preview: la calculadora de la web, que calcula sin guardar. */
@SpringBootTest
@AutoConfigureMockMvc
class ProjectionPreviewTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        token = registerAndGetToken();
    }

    @AfterEach
    void cleanUp() {
        DatabaseCleaner.clean(jdbc);
    }

    @Test
    @DisplayName("Da los mismos montos que se guardarian, y no guarda nada")
    void previewMatchesCreateWithoutSaving() throws Exception {
        mockMvc.perform(get("/api/v1/projections/preview")
                        .header("Authorization", "Bearer " + token)
                        .param("initialCapital", "500")
                        .param("monthlyContribution", "120")
                        .param("annualRate", "0.075")
                        .param("periods", "24"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalContributed").value(3380.00))
                .andExpect(jsonPath("$.simpleFinalAmount").value(3652.75))
                .andExpect(jsonPath("$.compoundFinalAmount").value(3667.18))
                .andExpect(jsonPath("$.difference").value(14.43));

        mockMvc.perform(get("/api/v1/projections").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("Con tasa cero no divide entre cero")
    void zeroRate() throws Exception {
        mockMvc.perform(get("/api/v1/projections/preview")
                        .header("Authorization", "Bearer " + token)
                        .param("initialCapital", "1000")
                        .param("monthlyContribution", "100")
                        .param("annualRate", "0")
                        .param("periods", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simpleFinalAmount").value(2200.00))
                .andExpect(jsonPath("$.compoundFinalAmount").value(2200.00))
                .andExpect(jsonPath("$.difference").value(0));
    }

    @Test
    @DisplayName("400 con el detalle por campo: faltantes, fuera de rango y no numericos")
    void rejectsInvalidParameters() throws Exception {
        mockMvc.perform(get("/api/v1/projections/preview")
                        .header("Authorization", "Bearer " + token)
                        .param("initialCapital", "-1")
                        .param("monthlyContribution", "0")
                        .param("annualRate", "1.5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field").value(containsInAnyOrder(
                        "initialCapital", "annualRate", "periods")));

        // Un valor que no es numero impide construir el record: se informa ese y no se valida el resto.
        mockMvc.perform(get("/api/v1/projections/preview")
                        .header("Authorization", "Bearer " + token)
                        .param("initialCapital", "500")
                        .param("monthlyContribution", "abc")
                        .param("annualRate", "0.1")
                        .param("periods", "12"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("monthlyContribution"));

        mockMvc.perform(get("/api/v1/projections/preview")
                        .header("Authorization", "Bearer " + token)
                        .param("initialCapital", "0")
                        .param("monthlyContribution", "0")
                        .param("annualRate", "0.1")
                        .param("periods", "601"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("periods"));
    }

    @Test
    @DisplayName("Sin sesion responde 401")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/projections/preview")
                        .param("initialCapital", "500")
                        .param("monthlyContribution", "120")
                        .param("annualRate", "0.075")
                        .param("periods", "24"))
                .andExpect(status().isUnauthorized());
    }

    private String registerAndGetToken() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ana\",\"email\":\"ana" + UUID.randomUUID()
                                + "@utec.edu.pe\",\"password\":\"Secreto123!\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("accessToken").asString();
    }
}
