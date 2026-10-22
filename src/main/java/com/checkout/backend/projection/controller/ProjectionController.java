package com.checkout.backend.projection.controller;

import com.checkout.backend.projection.dto.ProjectionPreviewRequest;
import com.checkout.backend.projection.dto.ProjectionPreviewResponse;
import com.checkout.backend.projection.dto.ProjectionRequest;
import com.checkout.backend.projection.dto.ProjectionResponse;
import com.checkout.backend.projection.service.ProjectionService;
import com.checkout.backend.user.service.CurrentUserProvider;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Proyecciones de ahorro del usuario autenticado.
 *
 * No hay PUT ni PATCH: una proyeccion es el resultado de un calculo con unos
 * datos concretos, y cambiarle los datos no la corrige, produce otra distinta.
 * El usuario crea una nueva y compara.
 */
@RestController
@RequestMapping("/projections")
public class ProjectionController {

    private final ProjectionService projectionService;
    private final CurrentUserProvider currentUser;

    public ProjectionController(ProjectionService projectionService,
                                CurrentUserProvider currentUser) {
        this.projectionService = projectionService;
        this.currentUser = currentUser;
    }

    /** GET /api/v1/projections */
    @GetMapping
    public ResponseEntity<List<ProjectionResponse>> list() {
        return ResponseEntity.ok(projectionService.list(currentUser.requireCurrentUser()));
    }

    /**
     * GET /api/v1/projections/preview?initialCapital=&monthlyContribution=&annualRate=&periods=
     *
     * Calcula un escenario sin guardarlo, para la calculadora de la web: lo que
     * se ve antes de guardar sale de la misma formula que lo guardado.
     */
    @GetMapping("/preview")
    public ResponseEntity<ProjectionPreviewResponse> preview(@Valid @ModelAttribute ProjectionPreviewRequest request) {
        return ResponseEntity.ok(projectionService.preview(request));
    }

    /** GET /api/v1/projections/{id} */
    @GetMapping("/{id}")
    public ResponseEntity<ProjectionResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(projectionService.get(currentUser.requireCurrentUser(), id));
    }

    /** POST /api/v1/projections */
    @PostMapping
    public ResponseEntity<ProjectionResponse> create(@Valid @RequestBody ProjectionRequest request) {
        ProjectionResponse created = projectionService.create(currentUser.requireCurrentUser(), request);

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.getId())
                .toUri();

        return ResponseEntity.created(location).body(created);
    }

    /** DELETE /api/v1/projections/{id} */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        projectionService.delete(currentUser.requireCurrentUser(), id);
        return ResponseEntity.noContent().build();
    }

}
