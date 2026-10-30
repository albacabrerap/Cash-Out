package com.checkout.backend.game;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Quienes miran una sala por SSE. Cada uno recibe un evento "state" con la
 * vista armada para el (el anfitrion y cada jugador ven cosas distintas).
 * Se llama con el candado de la sala tomado, para que nadie reciba un estado
 * a medio cambiar.
 */
public final class RoomViewers {

    private static final long TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

    private final Map<SseEmitter, Long> viewers = new ConcurrentHashMap<>();

    public SseEmitter subscribe(long viewerId, LongFunction<Object> view) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        Runnable release = () -> viewers.remove(emitter);
        emitter.onCompletion(release);
        emitter.onTimeout(release);
        emitter.onError(error -> release.run());
        viewers.put(emitter, viewerId);
        send(emitter, viewerId, view);
        return emitter;
    }

    public void broadcast(LongFunction<Object> view) {
        viewers.forEach((emitter, viewerId) -> send(emitter, viewerId, view));
    }

    public void close() {
        viewers.keySet().forEach(SseEmitter::complete);
        viewers.clear();
    }

    private void send(SseEmitter emitter, long viewerId, LongFunction<Object> view) {
        try {
            emitter.send(SseEmitter.event().name("state").data(view.apply(viewerId), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            viewers.remove(emitter);
        }
    }

}
