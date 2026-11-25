package ru.tinvest.trainer.sessions;

import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.tinvest.trainer.api.InvalidRequestException;

/**
 * Арена (контракт {@code api/openapi.yaml}): снимок сессии, подача, отмена и замена заявки, оценка заявки. Ответы
 * строит {@link BlindMasking} по режиму сессии.
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class ArenaController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final Arena arena;

    ArenaController(Arena arena) {
        this.arena = arena;
    }

    @GetMapping
    Map<String, Object> snapshot(@PathVariable UUID sessionId) {
        return arena.snapshot(sessionId);
    }

    @GetMapping("/fundamentals")
    Map<String, Object> fundamentals(@PathVariable UUID sessionId) {
        return arena.fundamentals(sessionId);
    }

    @PostMapping("/orders")
    Object place(
            @PathVariable UUID sessionId,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) @Nullable String key,
            @RequestBody OrderRequest body) {
        return arena.place(sessionId, key(key), body);
    }

    @DeleteMapping("/orders/{orderId}")
    Object cancel(
            @PathVariable UUID sessionId,
            @PathVariable UUID orderId,
            @RequestParam long seenStep,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) @Nullable String key) {
        if (seenStep < 0) {
            throw new InvalidRequestException("seenStep must not be negative");
        }
        return arena.cancel(sessionId, orderId, seenStep, key(key));
    }

    @PutMapping("/orders/{orderId}")
    Object replace(
            @PathVariable UUID sessionId,
            @PathVariable UUID orderId,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) @Nullable String key,
            @RequestBody OrderRequest body) {
        return arena.replace(sessionId, orderId, key(key), body);
    }

    @PostMapping("/order-estimate")
    Map<String, Object> estimate(@PathVariable UUID sessionId, @RequestBody OrderRequest body) {
        return arena.estimate(sessionId, body);
    }

    @ExceptionHandler(IdempotencyException.class)
    ProblemDetail idempotencyConflict(IdempotencyException e) {
        return SessionProblems.idempotency(e);
    }

    private static @Nullable UUID key(@Nullable String header) {
        return SessionProblems.key(header);
    }
}
