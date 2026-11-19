package ru.tinvest.trainer.sessions;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import ru.tinvest.trainer.api.InvalidRequestException;

/** Общие для контроллеров сессий разбор {@code Idempotency-Key} и тело ошибки ключа. */
final class SessionProblems {

    private SessionProblems() {}

    /** Ключ идемпотентности из заголовка; нет заголовка — {@code null}. */
    static @Nullable UUID key(@Nullable String header) {
        if (header == null) {
            return null;
        }
        try {
            return UUID.fromString(header);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("Idempotency-Key must be a UUID");
        }
    }

    static ProblemDetail idempotency(IdempotencyException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        problem.setTitle(e.status() == HttpStatus.CONFLICT ? "Conflict" : "Unprocessable Content");
        problem.setProperty("code", e.code());
        return problem;
    }
}
