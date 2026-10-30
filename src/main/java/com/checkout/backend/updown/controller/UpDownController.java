package com.checkout.backend.updown.controller;

import com.checkout.backend.updown.dto.UpDownView;
import com.checkout.backend.updown.dto.UpDownVoteRequest;
import com.checkout.backend.updown.service.UpDownService;
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
 * "¿Sube o baja?". Quien abre la sala es el anfitrion y la proyecta; los demas
 * entran con el codigo o el QR y votan desde el celular.
 */
@RestController
@RequestMapping("/updown-rooms")
public class UpDownController {

    private final UpDownService service;
    private final CurrentUserProvider currentUser;

    public UpDownController(UpDownService service, CurrentUserProvider currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    /** POST /api/v1/updown-rooms */
    @PostMapping
    public ResponseEntity<UpDownView> create() {
        UpDownView room = service.create(currentUser.requireCurrentUser());
        return ResponseEntity.created(URI.create("/api/v1/updown-rooms/" + room.code())).body(room);
    }

    /** GET /api/v1/updown-rooms/{code} */
    @GetMapping("/{code}")
    public ResponseEntity<UpDownView> get(@PathVariable String code) {
        return ResponseEntity.ok(service.view(currentUser.requireCurrentUser(), code));
    }

    /** GET /api/v1/updown-rooms/{code}/events: un evento "state" al conectarse, en cada cambio y cada segundo de votacion. */
    @GetMapping("/{code}/events")
    public SseEmitter events(@PathVariable String code) {
        return service.subscribe(currentUser.requireCurrentUser(), code);
    }

    /** POST /api/v1/updown-rooms/{code}/join */
    @PostMapping("/{code}/join")
    public ResponseEntity<UpDownView> join(@PathVariable String code) {
        return ResponseEntity.ok(service.join(currentUser.requireCurrentUser(), code));
    }

    /** POST /api/v1/updown-rooms/{code}/start: solo el anfitrion. */
    @PostMapping("/{code}/start")
    public ResponseEntity<UpDownView> start(@PathVariable String code) {
        return ResponseEntity.ok(service.start(currentUser.requireCurrentUser(), code));
    }

    /** POST /api/v1/updown-rooms/{code}/votes: un voto por ronda, sin cambios. */
    @PostMapping("/{code}/votes")
    public ResponseEntity<UpDownView> vote(@PathVariable String code, @Valid @RequestBody UpDownVoteRequest request) {
        return ResponseEntity.ok(service.vote(currentUser.requireCurrentUser(), code, request.choice(), request.bet()));
    }

    /** POST /api/v1/updown-rooms/{code}/reveal: el anfitrion cierra la votacion antes de tiempo. */
    @PostMapping("/{code}/reveal")
    public ResponseEntity<UpDownView> reveal(@PathVariable String code) {
        return ResponseEntity.ok(service.reveal(currentUser.requireCurrentUser(), code));
    }

    /** POST /api/v1/updown-rooms/{code}/next: siguiente ronda, o el final despues de la ultima. */
    @PostMapping("/{code}/next")
    public ResponseEntity<UpDownView> next(@PathVariable String code) {
        return ResponseEntity.ok(service.next(currentUser.requireCurrentUser(), code));
    }

    /** POST /api/v1/updown-rooms/{code}/cancel: solo el anfitrion. */
    @PostMapping("/{code}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable String code) {
        service.cancel(currentUser.requireCurrentUser(), code);
        return ResponseEntity.noContent().build();
    }

}
