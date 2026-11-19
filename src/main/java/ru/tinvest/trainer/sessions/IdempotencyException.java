package ru.tinvest.trainer.sessions;

import org.springframework.http.HttpStatus;

/** Конфликт ключа {@code Idempotency-Key}: 422 {@code IDEMPOTENCY_KEY_REUSED} или 409 {@code IDEMPOTENCY_KEY_IN_PROGRESS}. */
final class IdempotencyException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private IdempotencyException(HttpStatus status, String code, String message) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
    }

    /** Ключ уже использован с другим телом или для другой операции. */
    static IdempotencyException reused() {
        return new IdempotencyException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "IDEMPOTENCY_KEY_REUSED",
                "Idempotency-Key was already used with another request");
    }

    /** Первый запрос с этим ключом ещё выполняется. */
    static IdempotencyException inProgress() {
        return new IdempotencyException(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_KEY_IN_PROGRESS",
                "A request with this Idempotency-Key is still in progress");
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }
}
