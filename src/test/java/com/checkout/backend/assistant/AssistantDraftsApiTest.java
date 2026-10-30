package com.checkout.backend.assistant;

import com.checkout.backend.assistant.classifier.Classification.Kind;
import com.checkout.backend.assistant.classifier.ClassificationInput;
import com.checkout.backend.assistant.classifier.UserRuleClassifier;
import com.checkout.backend.assistant.receipt.ScannedReceipt;
import com.checkout.backend.assistant.receipt.ScannedReceiptRepository;
import com.checkout.backend.savings.expense.model.ExpenseCategory;
import com.checkout.backend.support.DatabaseCleaner;
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
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** El endpoint de borradores de punta a punta: parser, clasificador y aviso de boleta repetida. */
@SpringBootTest
@AutoConfigureMockMvc
class AssistantDraftsApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String QR = "20100070970|03|B001|00012345|5.49|35.90|2026-10-27|1|45678912|aGFzaA==|";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRuleClassifier rules;
    @Autowired private ScannedReceiptRepository scannedReceipts;

    private String token;
    private long userId;

    @BeforeEach
    void setUp() throws Exception {
        JsonNode auth = JSON.readTree(mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ana\",\"email\":\"ana@utec.edu.pe\",\"password\":\"Secreto123!\"}"))
                .andReturn().getResponse().getContentAsString());
        token = "Bearer " + auth.get("accessToken").asString();
        userId = auth.get("user").get("id").asLong();
    }

    @AfterEach
    void cleanUp() {
        DatabaseCleaner.clean(jdbc);
    }

    @Test
    @DisplayName("Un mensaje da una tarjeta por movimiento, con categoria sugerida y sin guardar nada")
    void draftsFromText() throws Exception {
        drafts("{\"text\":\"ayer taxi 12 y chifa 35,90 con yape\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.drafts.length()").value(2))
                .andExpect(jsonPath("$.drafts[0].kind").value("EXPENSE"))
                .andExpect(jsonPath("$.drafts[0].description").value("Taxi"))
                .andExpect(jsonPath("$.drafts[0].amount").value(12.00))
                .andExpect(jsonPath("$.drafts[0].category").value("TRANSPORT"))
                .andExpect(jsonPath("$.drafts[0].paymentMethod").value("YAPE"))
                .andExpect(jsonPath("$.drafts[0].source").value("KEYWORDS"))
                .andExpect(jsonPath("$.drafts[0].receiptRef").doesNotExist())
                .andExpect(jsonPath("$.drafts[1].category").value("FOOD"))
                .andExpect(jsonPath("$.drafts[1].amount").value(35.90));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expenses", Integer.class)).isZero();
    }

    @Test
    @DisplayName("Un ingreso no lleva categoria, y un monto que falta se avisa en vez de inventarse")
    void incomeAndMissingAmount() throws Exception {
        drafts("{\"text\":\"me pagaron 1500 del freelance\"}")
                .andExpect(jsonPath("$.drafts[0].kind").value("INCOME"))
                .andExpect(jsonPath("$.drafts[0].category").doesNotExist());
        drafts("{\"text\":\"almuerzo con yape\"}")
                .andExpect(jsonPath("$.drafts[0].amount").doesNotExist())
                .andExpect(jsonPath("$.drafts[0].warnings[0]").value("MISSING_AMOUNT"));
    }

    @Test
    @DisplayName("Una boleta da su monto y fecha exactos, sin datos del comprador, y la regla del RUC gana")
    void draftFromReceipt() throws Exception {
        rules.learn(new ClassificationInput(userId, null, null, null, "20100070970"), Kind.EXPENSE, ExpenseCategory.FOOD);

        String body = drafts("{\"qr\":\"" + QR + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.drafts.length()").value(1))
                .andExpect(jsonPath("$.drafts[0].description").value("Boleta B001-12345"))
                .andExpect(jsonPath("$.drafts[0].amount").value(35.90))
                .andExpect(jsonPath("$.drafts[0].date").value("2026-10-27"))
                .andExpect(jsonPath("$.drafts[0].category").value("FOOD"))
                .andExpect(jsonPath("$.drafts[0].source").value("USER_RULE"))
                .andExpect(jsonPath("$.drafts[0].receiptRef").isNotEmpty())
                .andExpect(jsonPath("$.drafts[0].alreadyRegisteredAt").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        // El RUC del comercio si viaja (para aprender la regla); el DNI del comprador nunca.
        assertThat(body).contains("\"ruc\":\"20100070970\"").doesNotContain("45678912");
    }

    @Test
    @DisplayName("Una boleta ya registrada se avisa con su fecha; otro usuario la puede registrar sin aviso")
    void warnsAboutDuplicates() throws Exception {
        String ref = JSON.readTree(drafts("{\"qr\":\"" + QR + "\"}").andReturn().getResponse().getContentAsString())
                .get("drafts").get(0).get("receiptRef").asString();
        ScannedReceipt saved = new ScannedReceipt();
        saved.setUserId(userId);
        saved.setFingerprint(ref);
        scannedReceipts.save(saved);

        drafts("{\"qr\":\"" + QR.replace("00012345", "12345") + "\"}")
                .andExpect(jsonPath("$.drafts[0].warnings[0]").value("DUPLICATE_RECEIPT"))
                .andExpect(jsonPath("$.drafts[0].alreadyRegisteredAt").isNotEmpty());

        String other = "Bearer " + JSON.readTree(mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Beto\",\"email\":\"beto@utec.edu.pe\",\"password\":\"Secreto123!\"}"))
                .andReturn().getResponse().getContentAsString()).get("accessToken").asString();
        mockMvc.perform(post("/api/v1/assistant/drafts").header("Authorization", other)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"qr\":\"" + QR + "\"}"))
                .andExpect(jsonPath("$.drafts[0].warnings").isEmpty())
                .andExpect(jsonPath("$.drafts[0].receiptRef").value(not(ref)));
    }

    @Test
    @DisplayName("Una nota de credito es un ingreso")
    void creditNoteIsIncome() throws Exception {
        drafts("{\"qr\":\"20100070970|07|BC01|3|0.00|15.00|2026-10-20|||\"}")
                .andExpect(jsonPath("$.drafts[0].kind").value("INCOME"))
                .andExpect(jsonPath("$.drafts[0].description").value("Nota de crédito BC01-3"))
                .andExpect(jsonPath("$.drafts[0].warnings[0]").value("CREDIT_NOTE"));
    }

    @Test
    @DisplayName("Reglas: uno de los dos campos, con tope de largo, un QR valido y con sesion")
    void validates() throws Exception {
        drafts("{}").andExpect(status().isBadRequest());
        drafts("{\"text\":\"taxi 12\",\"qr\":\"" + QR + "\"}").andExpect(status().isBadRequest());
        drafts("{\"text\":\"" + "a".repeat(501) + "\"}").andExpect(status().isBadRequest());
        drafts("{\"qr\":\"https://www.sunat.gob.pe/\"}").andExpect(status().isBadRequest());
        drafts("{\"text\":\"hola\"}").andExpect(jsonPath("$.drafts[0].amount").doesNotExist());
        mockMvc.perform(post("/api/v1/assistant/drafts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"taxi 12\"}")).andExpect(status().isUnauthorized());
    }

    private ResultActions drafts(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/assistant/drafts").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

}
