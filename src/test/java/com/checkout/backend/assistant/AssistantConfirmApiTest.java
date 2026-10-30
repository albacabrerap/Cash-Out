package com.checkout.backend.assistant;

import com.checkout.backend.support.DatabaseCleaner;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Confirmar una tarjeta: crea el gasto o ingreso, marca la boleta y aprende de las correcciones. */
@SpringBootTest
@AutoConfigureMockMvc
class AssistantConfirmApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String QR = "20100070970|03|B001|00012345|5.49|35.90|2026-10-27|1|45678912||";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        token = "Bearer " + JSON.readTree(mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ana\",\"email\":\"ana@utec.edu.pe\",\"password\":\"Secreto123!\"}"))
                .andReturn().getResponse().getContentAsString()).get("accessToken").asString();
        // Un gasto descuenta del ahorro: sin saldo, ExpenseService lo rechaza.
        jdbc.update("UPDATE savings SET current_balance = 1000");
    }

    @AfterEach
    void cleanUp() {
        DatabaseCleaner.clean(jdbc);
    }

    @Test
    @DisplayName("Confirmar un gasto lo crea con lo que el usuario dejo en la tarjeta y descuenta del ahorro")
    void confirmsAnExpense() throws Exception {
        confirm("{\"kind\":\"EXPENSE\",\"amount\":12.50,\"date\":\"2026-10-27\",\"description\":\"Taxi\","
                + "\"category\":\"TRANSPORT\",\"learn\":false}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("EXPENSE"))
                .andExpect(jsonPath("$.learned").value(false));

        assertThat(jdbc.queryForObject("SELECT category FROM expenses", String.class)).isEqualTo("TRANSPORT");
        assertThat(jdbc.queryForObject("SELECT current_balance FROM savings", Double.class)).isEqualTo(987.50);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM classification_rules", Integer.class)).isZero();
    }

    @Test
    @DisplayName("Un ingreso usa la descripcion como origen; sin descripcion, uno por defecto")
    void confirmsAnIncome() throws Exception {
        confirm("{\"kind\":\"INCOME\",\"amount\":1500,\"date\":\"2026-10-27\",\"description\":\"Freelance\"}")
                .andExpect(status().isCreated());
        confirm("{\"kind\":\"INCOME\",\"amount\":20,\"date\":\"2026-10-27\"}").andExpect(status().isCreated());

        assertThat(jdbc.queryForList("SELECT source FROM incomes ORDER BY id", String.class))
                .containsExactly("Freelance", "Registrado con el asistente");
    }

    @Test
    @DisplayName("Si el usuario corrigio la categoria, la proxima vez sale sola")
    void learnsFromCorrections() throws Exception {
        drafts("{\"text\":\"donde pepe 15\"}").andExpect(jsonPath("$.drafts[0].source").value("KEYWORDS"));

        confirm("{\"kind\":\"EXPENSE\",\"amount\":15,\"date\":\"2026-10-27\",\"description\":\"Donde Pepe\","
                + "\"category\":\"FOOD\",\"learn\":true}")
                .andExpect(jsonPath("$.learned").value(true));

        drafts("{\"text\":\"almuerzo donde pepe 18\"}")
                .andExpect(jsonPath("$.drafts[0].category").value("FOOD"))
                .andExpect(jsonPath("$.drafts[0].source").value("USER_RULE"));
    }

    @Test
    @DisplayName("Una boleta confirmada aprende por RUC, se avisa si se escanea otra vez y no se registra dos veces")
    void receiptFlow() throws Exception {
        JsonNode draft = firstDraft("{\"qr\":\"" + QR + "\"}");
        confirm(receiptConfirmation(draft)).andExpect(status().isCreated()).andExpect(jsonPath("$.learned").value(true));

        JsonNode again = firstDraft("{\"qr\":\"" + QR + "\"}");
        assertThat(again.get("warnings").toString()).contains("DUPLICATE_RECEIPT");
        assertThat(again.get("category").asString()).isEqualTo("FOOD");
        assertThat(again.get("source").asString()).isEqualTo("USER_RULE");

        confirm(receiptConfirmation(again)).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expenses", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT current_balance FROM savings", Double.class)).isEqualTo(964.10);
    }

    @Test
    @DisplayName("Borrar el gasto de una boleta deja registrarla otra vez")
    void deletingTheExpenseFreesTheReceipt() throws Exception {
        JsonNode draft = firstDraft("{\"qr\":\"" + QR + "\"}");
        long id = JSON.readTree(confirm(receiptConfirmation(draft)).andReturn().getResponse().getContentAsString())
                .get("id").asLong();

        mockMvc.perform(delete("/api/v1/expenses/" + id).header("Authorization", token)).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scanned_receipts", Integer.class)).isZero();
        assertThat(firstDraft("{\"qr\":\"" + QR + "\"}").get("warnings").toString()).doesNotContain("DUPLICATE_RECEIPT");
    }

    @Test
    @DisplayName("Dos confirmaciones simultaneas de la misma boleta dejan un solo gasto")
    void concurrentConfirmationsCreateOneExpense() throws Exception {
        String body = receiptConfirmation(firstDraft("{\"qr\":\"" + QR + "\"}"));
        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> task = () -> {
                startTogether.await();
                return confirm(body).andReturn().getResponse().getStatus();
            };
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : pool.invokeAll(List.of(task, task))) {
                statuses.add(future.get());
            }

            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expenses", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT current_balance FROM savings", Double.class)).isEqualTo(964.10);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Reglas: un gasto lleva categoria, el monto es positivo, la fecha no es futura y la referencia es una huella")
    void validates() throws Exception {
        confirm("{\"kind\":\"EXPENSE\",\"amount\":10,\"date\":\"2026-10-27\"}").andExpect(status().isBadRequest());
        confirm("{\"kind\":\"EXPENSE\",\"amount\":0,\"date\":\"2026-10-27\",\"category\":\"FOOD\"}")
                .andExpect(status().isBadRequest());
        confirm("{\"kind\":\"EXPENSE\",\"amount\":10,\"date\":\"2999-01-01\",\"category\":\"FOOD\"}")
                .andExpect(status().isBadRequest());
        confirm("{\"kind\":\"EXPENSE\",\"amount\":10,\"date\":\"2026-10-27\",\"category\":\"FOOD\",\"receiptRef\":\"abc\"}")
                .andExpect(status().isBadRequest());
        confirm("{\"kind\":\"EXPENSE\",\"amount\":5000,\"date\":\"2026-10-27\",\"category\":\"FOOD\"}")
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expenses", Integer.class)).isZero();
    }

    /** Lo que mandaria la web al confirmar la tarjeta de una boleta, eligiendo Comida. */
    private static String receiptConfirmation(JsonNode draft) {
        return "{\"kind\":\"EXPENSE\",\"amount\":" + draft.get("amount").asString() + ",\"date\":\""
                + draft.get("date").asString() + "\",\"description\":\"" + draft.get("description").asString()
                + "\",\"category\":\"FOOD\",\"ruc\":\"" + draft.get("ruc").asString() + "\",\"receiptRef\":\""
                + draft.get("receiptRef").asString() + "\",\"learn\":true}";
    }

    private JsonNode firstDraft(String body) throws Exception {
        return JSON.readTree(drafts(body).andReturn().getResponse().getContentAsString()).get("drafts").get(0);
    }

    private ResultActions drafts(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/assistant/drafts").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions confirm(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/assistant/confirm").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

}
