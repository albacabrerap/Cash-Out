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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La tasa que se ingresa es una TEA: capitalizada mes a mes durante un anio
 * tiene que dar exactamente esa tasa. Los montos esperados se calcularon aparte
 * con 40 cifras de precision; la web usa los mismos casos.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProjectionRateTest {

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
    @DisplayName("Una TEA de 10% hace crecer el capital exactamente 10% en doce meses")
    void anEffectiveAnnualRateGrowsByItselfInOneYear() throws Exception {
        project("1000.00", "0", "0.10", 12)
                .andExpect(jsonPath("$.compoundFinalAmount").value(1100.00))
                .andExpect(jsonPath("$.simpleFinalAmount").value(1095.69));
        project("1000.00", "0", "0.10", 24)
                .andExpect(jsonPath("$.compoundFinalAmount").value(1210.00));
    }

    @Test
    @DisplayName("En el primer mes todavia no hay intereses que reinvertir: simple y compuesto coinciden")
    void bothScenariosMatchAfterOneMonth() throws Exception {
        project("1000.00", "0", "0.10", 1)
                .andExpect(jsonPath("$.simpleFinalAmount").value(1007.97))
                .andExpect(jsonPath("$.compoundFinalAmount").value(1007.97))
                .andExpect(jsonPath("$.difference").value(0));
    }

    @Test
    @DisplayName("Con aportes mensuales: los valores de la maqueta y un plazo largo")
    void contributionsAccrueFromTheirOwnMonth() throws Exception {
        project("500.00", "120.00", "0.075", 24)
                .andExpect(jsonPath("$.totalContributed").value(3380.00))
                .andExpect(jsonPath("$.simpleFinalAmount").value(3652.75))
                .andExpect(jsonPath("$.compoundFinalAmount").value(3667.18))
                // La diferencia es la de los montos ya redondeados, la que ve el usuario.
                .andExpect(jsonPath("$.difference").value(14.43));
        project("10000.00", "200.00", "0.08", 120)
                .andExpect(jsonPath("$.simpleFinalAmount").value(50908.63))
                .andExpect(jsonPath("$.compoundFinalAmount").value(57614.10));
        project("250.50", "80.00", "0.125", 600)
                .andExpect(jsonPath("$.simpleFinalAmount").value(191531.83))
                .andExpect(jsonPath("$.compoundFinalAmount").value(3011089.31));
    }

    private ResultActions project(String capital, String contribution, String rate, int periods) throws Exception {
        return mockMvc.perform(post("/api/v1/projections")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Escenario\",\"initialCapital\":" + capital
                                + ",\"monthlyContribution\":" + contribution
                                + ",\"annualRate\":" + rate + ",\"periods\":" + periods + "}"))
                .andExpect(status().isCreated());
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
