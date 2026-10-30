package com.checkout.backend.email;

import com.checkout.backend.exceptions.EmailSenderException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Envio de correo de punta a punta, con la cadena de seguridad real.
 *
 * El JavaMailSender se sustituye por un doble: lo que hay que comprobar no es
 * que Gmail acepte el mensaje, sino a quien se le pide enviarlo y que pasa
 * cuando falla. Un servidor SMTP de verdad haria la suite lenta y dependiente
 * de la red.
 *
 * El envio ocurre en otro hilo, asi que las comprobaciones usan el timeout de
 * Mockito en vez de verificar al instante: sin eso el test miraria el doble
 * antes de que el hilo de correo llegue a tocarlo, y fallaria de forma
 * intermitente, que es peor que fallar siempre.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EmailApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "Secreto123!";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JavaMailSender mailSender;

    @Autowired
    private EmailService emailService;

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Ana","email":"ana@utec.edu.pe","password":"%s"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        token = JSON.readTree(body).get("accessToken").asString();
        // Registrarse manda el correo de verificacion en segundo plano: se espera
        // y se olvida, para que cada test cuente solo sus propios envios.
        verify(mailSender, timeout(3000)).send(any(SimpleMailMessage.class));
        clearInvocations(mailSender);
    }

    // ------------------------------------------------------------------
    // Seguridad: el destinatario no lo elige el cliente
    // ------------------------------------------------------------------

    @Test
    @DisplayName("El correo va al usuario del token, aunque el cuerpo pida otro destinatario")
    void theRecipientComesFromTheTokenOnly() throws Exception {
        mockMvc.perform(post("/api/v1/emails")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        // "recipient" es justo el campo que la version anterior
                        // aceptaba. Se manda a proposito para comprobar que ya
                        // no tiene ningun efecto.
                        .content("""
                                {"subject":"Hola","body":"Contenido",
                                 "recipient":"atacante@example.com"}
                                """))
                .andExpect(status().isAccepted());

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, timeout(3000)).send(sent.capture());

        assertThat(sent.getValue().getTo()).containsExactly("ana@utec.edu.pe");
        assertThat(sent.getValue().getSubject()).isEqualTo("Hola");
    }

    @Test
    @DisplayName("401: sin token no se puede pedir ningun envio")
    void sendingRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/emails")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subject":"Hola","body":"Contenido"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("El adjunto sale de lo que se sube, y su nombre pierde la ruta")
    void theAttachmentComesFromTheUpload() throws Exception {
        given(mailSender.createMimeMessage()).willReturn(new MimeMessage((Session) null));

        MockMultipartFile email = new MockMultipartFile("email", "",
                MediaType.APPLICATION_JSON_VALUE,
                "{\"subject\":\"Resumen\",\"body\":\"Adjunto va\"}".getBytes());

        // El nombre trae una ruta a proposito: la version anterior leia ficheros
        // del servidor por ruta, y aqui debe quedarse solo el nombre del archivo
        // que el usuario subio.
        MockMultipartFile file = new MockMultipartFile("file", "../../etc/passwd",
                MediaType.TEXT_PLAIN_VALUE, "contenido del usuario".getBytes());

        mockMvc.perform(multipart("/api/v1/emails/with-attachment")
                        .file(email)
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted());

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, timeout(3000)).send(sent.capture());

        assertThat(sent.getValue().getAllRecipients())
                .extracting(Object::toString)
                .containsExactly("ana@utec.edu.pe");
        assertThat(attachmentNames(sent.getValue())).containsExactly("passwd");
    }

    @Test
    @DisplayName("400: un adjunto vacio se rechaza")
    void anEmptyAttachmentIsRejected() throws Exception {
        MockMultipartFile email = new MockMultipartFile("email", "",
                MediaType.APPLICATION_JSON_VALUE,
                "{\"subject\":\"Resumen\",\"body\":\"Adjunto va\"}".getBytes());
        MockMultipartFile file = new MockMultipartFile("file", "vacio.txt",
                MediaType.TEXT_PLAIN_VALUE, new byte[0]);

        mockMvc.perform(multipart("/api/v1/emails/with-attachment")
                        .file(email)
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // Validacion
    // ------------------------------------------------------------------

    @Test
    @DisplayName("400: asunto y cuerpo vacios se rechazan y se nombran los campos")
    void blankFieldsAreRejected() throws Exception {
        mockMvc.perform(post("/api/v1/emails")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subject":"  ","body":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'subject')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'body')]").exists());
    }

    // ------------------------------------------------------------------
    // Asincronia
    // ------------------------------------------------------------------

    @Test
    @DisplayName("202: la respuesta no espera al envio, ni siquiera si va a fallar")
    void theResponseDoesNotWaitForTheSend() throws Exception {
        doThrow(new MailSendException("SMTP caido"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        long start = System.currentTimeMillis();

        mockMvc.perform(post("/api/v1/emails")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subject":"Hola","body":"Contenido"}
                                """))
                // 202 aunque el envio vaya a fallar: cuando el cliente recibe la
                // respuesta el correo solo esta encolado, y un 200 afirmaria algo
                // que todavia no ocurrio.
                .andExpect(status().isAccepted());

        assertThat(System.currentTimeMillis() - start).isLessThan(1000);
    }

    @Test
    @DisplayName("Un fallo de SMTP se envuelve en EmailSenderException")
    void anSmtpFailureBecomesADomainException() {
        doThrow(new MailSendException("Connection refused to smtp.gmail.com:587 user=checkout"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        // Se llama al servicio directamente para poder observar la excepcion: a
        // traves del endpoint viaja en otro hilo y la respuesta ya se fue.
        assertThatThrownBy(() -> emailService
                .send(new EmailDetails("ana@utec.edu.pe", "Hola", "Contenido")).join())
                .hasCauseInstanceOf(EmailSenderException.class);
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /**
     * Nombres de los adjuntos del mensaje.
     *
     * Hay que recorrer las partes: getContent de un MimeMessage multipart
     * devuelve el objeto MimeMultipart, asi que mirar su toString solo da la
     * referencia de Java y no el contenido.
     */
    private static List<String> attachmentNames(MimeMessage message) throws Exception {
        MimeMultipart multipart = (MimeMultipart) message.getContent();

        List<String> names = new ArrayList<>();
        for (int i = 0; i < multipart.getCount(); i++) {
            String filename = multipart.getBodyPart(i).getFileName();
            if (filename != null) {
                names.add(filename);
            }
        }
        return names;
    }

}
