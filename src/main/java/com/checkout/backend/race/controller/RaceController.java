package com.checkout.backend.race.controller;

import com.checkout.backend.race.dto.CreateRaceRequest;
import com.checkout.backend.race.dto.RaceTradeRequest;
import com.checkout.backend.race.dto.RaceView;
import com.checkout.backend.race.service.RaceService;
import com.checkout.backend.user.service.CurrentUserProvider;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Carrera de trading. Cualquier usuario puede abrir una sala; quien la abre es
 * el anfitrion y la proyecta, y los demas entran con el codigo o el QR.
 */
@RestController
@RequestMapping("/race-rooms")
public class RaceController {

    private final RaceService raceService;
    private final CurrentUserProvider currentUser;

    public RaceController(RaceService raceService, CurrentUserProvider currentUser) {
        this.raceService = raceService;
        this.currentUser = currentUser;
    }

    /** POST /api/v1/race-rooms */
    @PostMapping
    public ResponseEntity<RaceView> create(@Valid @RequestBody CreateRaceRequest request) {
        RaceView room = raceService.create(currentUser.requireCurrentUser(), request.entryFee());
        return ResponseEntity.created(URI.create("/api/v1/race-rooms/" + room.code())).body(room);
    }

    /** GET /api/v1/race-rooms/{code} */
    @GetMapping("/{code}")
    public ResponseEntity<RaceView> get(@PathVariable String code) {
        return ResponseEntity.ok(raceService.view(currentUser.requireCurrentUser(), code));
    }

    /**
     * GET /api/v1/race-rooms/{code}/events
     *
     * La sala en vivo por SSE: "state" con todo al conectarse y en cada cambio
     * de fase, y "tick" en cada paso de la carrera.
     */
    @GetMapping("/{code}/events")
    public SseEmitter events(@PathVariable String code) {
        return raceService.subscribe(currentUser.requireCurrentUser(), code);
    }

    /** POST /api/v1/race-rooms/{code}/join */
    @PostMapping("/{code}/join")
    public ResponseEntity<RaceView> join(@PathVariable String code) {
        return ResponseEntity.ok(raceService.join(currentUser.requireCurrentUser(), code));
    }

    /** POST /api/v1/race-rooms/{code}/start: solo el anfitrion. */
    @PostMapping("/{code}/start")
    public ResponseEntity<RaceView> start(@PathVariable String code) {
        return ResponseEntity.ok(raceService.start(currentUser.requireCurrentUser(), code));
    }

    /** POST /api/v1/race-rooms/{code}/cancel: solo el anfitrion y antes de empezar. Devuelve las cuotas. */
    @PostMapping("/{code}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable String code) {
        raceService.cancel(currentUser.requireCurrentUser(), code);
        return ResponseEntity.noContent().build();
    }

    /** POST /api/v1/race-rooms/{code}/trades */
    @PostMapping("/{code}/trades")
    public ResponseEntity<RaceView> trade(@PathVariable String code, @Valid @RequestBody RaceTradeRequest request) {
        return ResponseEntity.ok(raceService.trade(currentUser.requireCurrentUser(), code, request.side(),
                request.fraction()));
    }

}
