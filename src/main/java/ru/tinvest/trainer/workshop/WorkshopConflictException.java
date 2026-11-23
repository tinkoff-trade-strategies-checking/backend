package ru.tinvest.trainer.workshop;

import ru.tinvest.trainer.api.ErrorCode;

/** Конфликт состояния или уникальности в мастерской (409 {@code CONFLICT}). */
final class WorkshopConflictException extends RuntimeException {

    private final ErrorCode code;

    private WorkshopConflictException(ErrorCode code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    static WorkshopConflictException duplicateName() {
        return new WorkshopConflictException(ErrorCode.CONFLICT, "Algorithm with this name already exists");
    }

    static WorkshopConflictException systemAlgorithmImmutable() {
        return new WorkshopConflictException(
                ErrorCode.CONFLICT, "System platform algorithm cannot be hidden or deleted");
    }

    ErrorCode code() {
        return code;
    }
}
