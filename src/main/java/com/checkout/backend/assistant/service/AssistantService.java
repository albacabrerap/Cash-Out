package com.checkout.backend.assistant.service;

import com.checkout.backend.assistant.classifier.Classification;
import com.checkout.backend.assistant.classifier.Classification.Kind;
import com.checkout.backend.assistant.classifier.ClassificationInput;
import com.checkout.backend.assistant.classifier.ClassificationService;
import com.checkout.backend.assistant.dto.Draft;
import com.checkout.backend.assistant.dto.DraftRequest;
import com.checkout.backend.assistant.dto.DraftsResponse;
import com.checkout.backend.assistant.parser.MovementTextParser;
import com.checkout.backend.assistant.parser.ParsedMovement;
import com.checkout.backend.assistant.parser.ParsedMovement.Hint;
import com.checkout.backend.assistant.receipt.ReceiptFingerprint;
import com.checkout.backend.assistant.receipt.ScannedReceipt;
import com.checkout.backend.assistant.receipt.ScannedReceiptRepository;
import com.checkout.backend.assistant.receipt.SunatQrParser;
import com.checkout.backend.assistant.receipt.SunatReceipt;
import com.checkout.backend.assistant.receipt.SunatReceipt.DocumentType;
import com.checkout.backend.exceptions.InvalidRequestException;
import com.checkout.backend.user.model.User;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Convierte un mensaje o el QR de una boleta en tarjetas para confirmar: el
 * parser saca montos y fechas, el clasificador sugiere tipo y categoria, y una
 * boleta ya registrada se avisa. No guarda nada: eso pasa al confirmar.
 *
 * Ni el mensaje ni el QR se registran en el log: pueden decir donde compra el
 * usuario y en que gasta.
 */
@Service
public class AssistantService {

    private static final Map<DocumentType, String> DOCUMENT_NAMES = Map.of(
            DocumentType.INVOICE, "Factura",
            DocumentType.RECEIPT, "Boleta",
            DocumentType.CREDIT_NOTE, "Nota de crédito",
            DocumentType.DEBIT_NOTE, "Nota de débito",
            DocumentType.UTILITY_BILL, "Recibo");

    private final MovementTextParser textParser;
    private final SunatQrParser qrParser;
    private final ClassificationService classifier;
    private final ReceiptFingerprint fingerprints;
    private final ScannedReceiptRepository scannedReceipts;

    public AssistantService(MovementTextParser textParser, SunatQrParser qrParser, ClassificationService classifier,
                            ReceiptFingerprint fingerprints, ScannedReceiptRepository scannedReceipts) {
        this.textParser = textParser;
        this.qrParser = qrParser;
        this.classifier = classifier;
        this.fingerprints = fingerprints;
        this.scannedReceipts = scannedReceipts;
    }

    @Transactional(readOnly = true)
    public DraftsResponse drafts(User user, DraftRequest request) {
        boolean hasText = request.text() != null && !request.text().isBlank();
        boolean hasQr = request.qr() != null && !request.qr().isBlank();
        if (hasText == hasQr) {
            throw new InvalidRequestException("Manda un mensaje o el texto del QR de una boleta, uno de los dos.");
        }
        if (hasQr) {
            return new DraftsResponse(List.of(fromReceipt(user, qrParser.parse(request.qr()))));
        }
        return new DraftsResponse(textParser.parse(request.text()).stream()
                .map(movement -> fromText(user, movement))
                .toList());
    }

    private Draft fromText(User user, ParsedMovement movement) {
        Classification suggestion = classifier.classify(
                new ClassificationInput(user.getId(), movement.description(), movement.hint(), null, null));
        List<String> warnings = movement.warnings().stream().map(Enum::name).toList();
        return draft(suggestion, suggestion.kind(), movement, warnings, null, null, null);
    }

    /**
     * La boleta trae monto y fecha exactos, pero no el nombre del comercio: la
     * descripcion queda como "Boleta B001-12345" hasta que el usuario la cambie
     * o haya una regla para ese RUC. Una nota de credito devuelve plata, asi que
     * es un ingreso aunque la regla del comercio diga gasto.
     */
    private Draft fromReceipt(User user, SunatReceipt receipt) {
        boolean credit = receipt.type() == DocumentType.CREDIT_NOTE;
        Classification suggestion = classifier.classify(new ClassificationInput(user.getId(), null,
                credit ? Hint.INCOME : Hint.EXPENSE, null, receipt.ruc()));
        Kind kind = credit ? Kind.INCOME : suggestion.kind();

        String fingerprint = fingerprints.of(user.getId(), receipt);
        LocalDateTime registeredAt = scannedReceipts.findByUserIdAndFingerprint(user.getId(), fingerprint)
                .map(ScannedReceipt::getCreatedAt)
                .orElse(null);
        List<String> warnings = new ArrayList<>();
        if (credit) {
            warnings.add("CREDIT_NOTE");
        }
        if (registeredAt != null) {
            warnings.add("DUPLICATE_RECEIPT");
        }

        String description = DOCUMENT_NAMES.get(receipt.type()) + " " + receipt.series() + "-"
                + Long.parseLong(receipt.number());
        ParsedMovement movement = new ParsedMovement(description, receipt.total(), ParsedMovement.Currency.PEN,
                receipt.issueDate(), null, null, List.of());
        return draft(suggestion, kind, movement, warnings, receipt.ruc(), fingerprint, registeredAt);
    }

    private static Draft draft(Classification suggestion, Kind kind, ParsedMovement movement, List<String> warnings,
                               String ruc, String receiptRef, LocalDateTime registeredAt) {
        boolean income = kind == Kind.INCOME;
        List<Draft.Option> alternatives = income ? List.of() : suggestion.alternatives().stream()
                .map(option -> new Draft.Option(option.category().name(), round(option.probability())))
                .toList();
        return new Draft(kind.name(), movement.description(), movement.amount(), movement.currency().name(), movement.date(),
                movement.paymentMethod() == null ? null : movement.paymentMethod().name(),
                income || suggestion.category() == null ? null : suggestion.category().name(),
                round(suggestion.confidence()), alternatives, suggestion.source().name(), warnings, ruc, receiptRef,
                registeredAt);
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

}
