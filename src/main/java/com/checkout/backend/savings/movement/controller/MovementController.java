package com.checkout.backend.savings.movement.controller;

import com.checkout.backend.savings.dto.MonthlyFlowsResponse;
import com.checkout.backend.savings.dto.MovementResponse;
import com.checkout.backend.savings.movement.service.MovementService;
import com.checkout.backend.user.service.CurrentUserProvider;
import com.checkout.backend.web.PageResponse;
import java.time.LocalDate;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ingresos y gastos juntos, solo lectura. Para crear, editar o borrar se usan
 * /incomes y /expenses, que son los que mueven el saldo.
 */
@RestController
@RequestMapping("/movements")
public class MovementController {

    private final MovementService movementService;
    private final CurrentUserProvider currentUser;

    public MovementController(MovementService movementService, CurrentUserProvider currentUser) {
        this.movementService = movementService;
        this.currentUser = currentUser;
    }

    /**
     * GET /api/v1/movements?from=&to=&search=&page=&size=&sort=
     *
     * {@code search} busca en la fuente y descripcion de los ingresos y en la
     * descripcion de los gastos. {@code sort} acepta {@code date} o
     * {@code amount}; por defecto, fecha descendente.
     */
    @GetMapping
    public ResponseEntity<PageResponse<MovementResponse>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(
                movementService.list(currentUser.requireCurrentUser(), from, to, search, pageable));
    }

    /**
     * GET /api/v1/movements/monthly?months=6
     *
     * Ingresos, gastos y ahorro de cada mes hasta el mes en curso, para el
     * grafico del resumen. Sin {@code months}, desde el primer movimiento.
     */
    @GetMapping("/monthly")
    public ResponseEntity<MonthlyFlowsResponse> monthly(@RequestParam(required = false) Integer months) {
        return ResponseEntity.ok(movementService.monthly(currentUser.requireCurrentUser(), months));
    }

}
