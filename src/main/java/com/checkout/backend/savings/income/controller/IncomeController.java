package com.checkout.backend.savings.income.controller;

import com.checkout.backend.savings.income.dto.IncomeRequest;
import com.checkout.backend.savings.income.dto.IncomeResponse;
import com.checkout.backend.savings.income.service.IncomeService;
import com.checkout.backend.user.service.CurrentUserProvider;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import com.checkout.backend.web.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Ingresos del usuario autenticado.
 */
@RestController
@RequestMapping("/incomes")
public class IncomeController {

    private final IncomeService incomeService;
    private final CurrentUserProvider currentUser;

    public IncomeController(IncomeService incomeService, CurrentUserProvider currentUser) {
        this.incomeService = incomeService;
        this.currentUser = currentUser;
    }

    /**
     * GET /api/v1/incomes?from=2026-01-01&to=2026-03-31&search=&sort=
     *
     * El rango va en query params y no en la ruta porque filtra una coleccion
     * que ya existe; no nombra un recurso distinto. @DateTimeFormat fija el
     * formato ISO, de modo que una fecha mal escrita da 400 en el binding en vez
     * de depender del locale del servidor.
     *
     * {@code search} busca en la fuente y en la descripcion, sin distinguir
     * mayusculas. {@code sort} acepta {@code date}, {@code amount} o
     * {@code source}; por defecto, fecha descendente.
     */
    @GetMapping
    public ResponseEntity<PageResponse<IncomeResponse>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(
                incomeService.list(currentUser.requireCurrentUser(), from, to, search, pageable));
    }

    /** GET /api/v1/incomes/{id} */
    @GetMapping("/{id}")
    public ResponseEntity<IncomeResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(incomeService.get(currentUser.requireCurrentUser(), id));
    }

    /** POST /api/v1/incomes */
    @PostMapping
    public ResponseEntity<IncomeResponse> create(@Valid @RequestBody IncomeRequest request) {
        IncomeResponse created = incomeService.create(currentUser.requireCurrentUser(), request);

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.getId())
                .toUri();

        return ResponseEntity.created(location).body(created);
    }

    /**
     * PUT /api/v1/incomes/{id}
     *
     * Reemplaza el ingreso completo, que es lo que PUT significa. El saldo se
     * ajusta por la diferencia dentro del servicio.
     */
    @PutMapping("/{id}")
    public ResponseEntity<IncomeResponse> update(@PathVariable Long id,
                                                @Valid @RequestBody IncomeRequest request) {
        return ResponseEntity.ok(
                incomeService.update(currentUser.requireCurrentUser(), id, request));
    }

    /** DELETE /api/v1/incomes/{id} */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        incomeService.delete(currentUser.requireCurrentUser(), id);
        return ResponseEntity.noContent().build();
    }

}
