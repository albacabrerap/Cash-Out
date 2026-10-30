package com.checkout.backend.assistant.controller;

import com.checkout.backend.assistant.dto.ConfirmDraftRequest;
import com.checkout.backend.assistant.dto.ConfirmDraftResponse;
import com.checkout.backend.assistant.dto.DraftRequest;
import com.checkout.backend.assistant.dto.DraftsResponse;
import com.checkout.backend.assistant.service.AssistantService;
import com.checkout.backend.assistant.service.DraftConfirmationService;
import com.checkout.backend.user.service.CurrentUserProvider;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Registrar gastos e ingresos escribiendo una frase o escaneando una boleta. */
@RestController
@RequestMapping("/assistant")
public class AssistantController {

    private final AssistantService service;
    private final DraftConfirmationService confirmations;
    private final CurrentUserProvider currentUser;

    public AssistantController(AssistantService service, DraftConfirmationService confirmations,
                               CurrentUserProvider currentUser) {
        this.service = service;
        this.confirmations = confirmations;
        this.currentUser = currentUser;
    }

    /**
     * POST /api/v1/assistant/drafts
     *
     * Devuelve tarjetas para confirmar, sin guardar nada. POST y no GET aunque
     * no cree nada: el mensaje puede ser largo y no debe quedar en la URL ni en
     * los logs de acceso.
     */
    @PostMapping("/drafts")
    public ResponseEntity<DraftsResponse> drafts(@Valid @RequestBody DraftRequest request) {
        return ResponseEntity.ok(service.drafts(currentUser.requireCurrentUser(), request));
    }

    /**
     * POST /api/v1/assistant/confirm
     *
     * Guarda una tarjeta ya revisada: 201 con el id del gasto o ingreso creado.
     * 409 si la boleta ya estaba registrada; 400 si no hay saldo para el gasto.
     */
    @PostMapping("/confirm")
    public ResponseEntity<ConfirmDraftResponse> confirm(@Valid @RequestBody ConfirmDraftRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(confirmations.confirm(currentUser.requireCurrentUser(), request));
    }

}
