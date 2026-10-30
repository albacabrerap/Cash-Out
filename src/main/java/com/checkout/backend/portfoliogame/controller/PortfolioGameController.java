package com.checkout.backend.portfoliogame.controller;

import com.checkout.backend.portfoliogame.dto.AllocationRequest;
import com.checkout.backend.portfoliogame.dto.PortfolioGameView;
import com.checkout.backend.portfoliogame.service.PortfolioGameService;
import com.checkout.backend.user.service.CurrentUserProvider;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * "Arma tu portafolio". Quien abre la sala es el anfitrion y la proyecta; los
 * demas entran con el codigo o el QR y arman su portafolio desde el celular.
 */
@RestController
@RequestMapping("/portfolio-rooms")
public class PortfolioGameController {

    private final PortfolioGameService service;
    private final CurrentUserProvider currentUser;

    public PortfolioGameController(PortfolioGameService service, CurrentUserProvider currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    /** POST /api/v1/portfolio-rooms */
    @PostMapping
    public ResponseEntity<PortfolioGameView> create() {
        PortfolioGameView room = service.create(currentUser.requireCurrentUser());
        return ResponseEntity.created(URI.create("/api/v1/portfolio-rooms/" + room.code())).body(room);
    }

    /** GET /api/v1/portfolio-rooms/{code} */
    @GetMapping("/{code}")
    public ResponseEntity<PortfolioGameView> get(@PathVariable String code) {
        return ResponseEntity.ok(service.view(currentUser.requireCurrentUser(), code));
    }

    /** GET /api/v1/portfolio-rooms/{code}/events: un evento "state" al conectarse y en cada cambio. */
    @GetMapping("/{code}/events")
    public SseEmitter events(@PathVariable String code) {
        return service.subscribe(currentUser.requireCurrentUser(), code);
    }

    /** POST /api/v1/portfolio-rooms/{code}/join: solo antes de empezar. */
    @PostMapping("/{code}/join")
    public ResponseEntity<PortfolioGameView> join(@PathVariable String code) {
        return ResponseEntity.ok(service.join(currentUser.requireCurrentUser(), code));
    }

    /** PUT /api/v1/portfolio-rooms/{code}/allocation: guarda el reparto y deja al jugador listo. */
    @PutMapping("/{code}/allocation")
    public ResponseEntity<PortfolioGameView> allocate(@PathVariable String code,
                                                      @Valid @RequestBody AllocationRequest request) {
        return ResponseEntity.ok(service.allocate(currentUser.requireCurrentUser(), code, request.weights()));
    }

    /** DELETE /api/v1/portfolio-rooms/{code}/allocation: vuelve a editarlo; el reparto se conserva. */
    @DeleteMapping("/{code}/allocation")
    public ResponseEntity<PortfolioGameView> unready(@PathVariable String code) {
        return ResponseEntity.ok(service.unready(currentUser.requireCurrentUser(), code));
    }

    /** POST /api/v1/portfolio-rooms/{code}/start: solo el anfitrion; revela la primera etapa. */
    @PostMapping("/{code}/start")
    public ResponseEntity<PortfolioGameView> start(@PathVariable String code) {
        return ResponseEntity.ok(service.start(currentUser.requireCurrentUser(), code));
    }

    /** POST /api/v1/portfolio-rooms/{code}/next: revela la siguiente etapa; la ultima termina el juego. */
    @PostMapping("/{code}/next")
    public ResponseEntity<PortfolioGameView> next(@PathVariable String code) {
        return ResponseEntity.ok(service.next(currentUser.requireCurrentUser(), code));
    }

    /** POST /api/v1/portfolio-rooms/{code}/cancel: solo el anfitrion. */
    @PostMapping("/{code}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable String code) {
        service.cancel(currentUser.requireCurrentUser(), code);
        return ResponseEntity.noContent().build();
    }

}
