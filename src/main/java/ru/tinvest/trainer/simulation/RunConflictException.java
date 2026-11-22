package ru.tinvest.trainer.simulation;

import ru.tinvest.trainer.api.ErrorCode;

/** Конфликт состояния прогона (409 {@code CONFLICT}). */
final class RunConflictException extends RuntimeException {

    private final ErrorCode code;

    RunConflictException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    ErrorCode code() {
        return code;
    }
}
