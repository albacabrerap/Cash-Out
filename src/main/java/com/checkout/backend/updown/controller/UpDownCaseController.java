package com.checkout.backend.updown.controller;

import com.checkout.backend.updown.dto.UpDownCaseRequest;
import com.checkout.backend.updown.dto.UpDownCaseResponse;
import com.checkout.backend.updown.dto.UpDownPreviewResponse;
import com.checkout.backend.updown.service.UpDownCaseService;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** El banco de casos de "¿Sube o baja?". Todo es de ADMIN: los jugadores solo ven los casos dentro de una partida. */
@RestController
@RequestMapping("/updown-cases")
@PreAuthorize("hasRole('ADMIN')")
public class UpDownCaseController {

    private final UpDownCaseService service;

    public UpDownCaseController(UpDownCaseService service) {
        this.service = service;
    }

    /** GET /api/v1/updown-cases */
    @GetMapping
    public ResponseEntity<List<UpDownCaseResponse>> list() {
        return ResponseEntity.ok(service.list());
    }

    /** GET /api/v1/updown-cases/preview?symbol=SPY&cutoff=2024-08-02: que paso de verdad, antes de guardar. */
    @GetMapping("/preview")
    public ResponseEntity<UpDownPreviewResponse> preview(@RequestParam String symbol,
                                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate cutoff) {
        return ResponseEntity.ok(service.preview(symbol, cutoff));
    }

    /** POST /api/v1/updown-cases */
    @PostMapping
    public ResponseEntity<UpDownCaseResponse> create(@Valid @RequestBody UpDownCaseRequest request) {
        UpDownCaseResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/updown-cases/" + created.id())).body(created);
    }

    /** PUT /api/v1/updown-cases/{id} */
    @PutMapping("/{id}")
    public ResponseEntity<UpDownCaseResponse> update(@PathVariable Long id, @Valid @RequestBody UpDownCaseRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    /** DELETE /api/v1/updown-cases/{id} */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

}
