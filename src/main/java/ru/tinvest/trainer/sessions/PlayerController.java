package ru.tinvest.trainer.sessions;

import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ru.tinvest.trainer.api.InvalidRequestException;

/**
 * Плеер, поток событий и завершение сессии (контракт {@code api/openapi.yaml}). Ответы и события строит
 * {@link BlindMasking} по режиму сессии.
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class PlayerController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String LAST_EVENT_ID = "Last-Event-ID";

    private final Player player;
    private final SessionEvents events;
    private final Sessions sessions;

    PlayerController(Player player, SessionEvents events, Sessions sessions) {
        this.player = player;
        this.events = events;
        this.sessions = sessions;
    }

    @PostMapping("/player")
    Object control(
            @PathVariable UUID sessionId,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) @Nullable String key,
            @RequestBody PlayerCommand body) {
        return player.control(sessionId, SessionProblems.key(key), body);
    }

    @PostMapping("/finish")
    Object finish(@PathVariable UUID sessionId) {
        return player.finish(sessionId);
    }

    /**
     * Поток событий сессии. Владелец проверяется до открытия потока (чужая сессия — 404). С {@code Last-Event-ID} сразу
     * приходят события после него; восстановление состояния при потере — снимком (INV-8).
     */
    @GetMapping("/events")
    SseEmitter stream(
            @PathVariable UUID sessionId,
            @RequestHeader(name = LAST_EVENT_ID, required = false) @Nullable String lastEventId,
            HttpServletResponse response) {
        sessions.requireOwned(sessionId);
        Long last = parseLastEventId(lastEventId);
        response.setHeader("Cache-Control", "no-cache");
        // прокси не должен буферизовать поток
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(0L);
        SessionEvents.Subscription subscription = events.subscribe(sessionId, last, new SessionEvents.Sink() {
            @Override
            public void send(long eventId, String type, String data) throws Exception {
                emitter.send(
                        SseEmitter.event().id(Long.toString(eventId)).name(type).data(data, MediaType.TEXT_PLAIN));
            }

            @Override
            public void ping() throws Exception {
                emitter.send(SseEmitter.event().comment("keepalive"));
            }

            @Override
            public void abort() {
                emitter.completeWithError(new java.io.IOException("Client is gone or too slow"));
            }
        });
        emitter.onCompletion(subscription::close);
        emitter.onTimeout(subscription::close);
        emitter.onError(e -> subscription.close());
        return emitter;
    }

    private static @Nullable Long parseLastEventId(@Nullable String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            long value = Long.parseLong(header.trim());
            if (value < 0) {
                throw new InvalidRequestException("Last-Event-ID must not be negative");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new InvalidRequestException("Last-Event-ID must be an event number");
        }
    }

    @ExceptionHandler(IdempotencyException.class)
    ProblemDetail idempotencyConflict(IdempotencyException e) {
        return SessionProblems.idempotency(e);
    }

    @ExceptionHandler(SessionConflictException.class)
    ProblemDetail conflict(SessionConflictException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Conflict");
        e.code().applyTo(problem);
        return problem;
    }
}
