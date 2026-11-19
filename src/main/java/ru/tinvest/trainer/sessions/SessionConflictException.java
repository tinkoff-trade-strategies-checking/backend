package ru.tinvest.trainer.sessions;

import ru.tinvest.trainer.api.ErrorCode;

/**
 * Конфликт команды плеера или завершения с состоянием сессии: 409 с машинным кодом {@code SESSION_NOT_ACTIVE} или
 * {@code PLAYER_STATE_CONFLICT} ({@code api/openapi.yaml}). Текст — константа, без данных сессии (INV-7).
 */
final class SessionConflictException extends RuntimeException {

    private final ErrorCode code;

    private SessionConflictException(ErrorCode code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    /** Сессия уже завершена. */
    static SessionConflictException notActive() {
        return new SessionConflictException(ErrorCode.SESSION_NOT_ACTIVE, "The session is not active");
    }

    /** Команда не подходит состоянию плеера: шаг во время игры, игра или шаг на последнем шаге. */
    static SessionConflictException playerState(String message) {
        return new SessionConflictException(ErrorCode.PLAYER_STATE_CONFLICT, message);
    }

    ErrorCode code() {
        return code;
    }
}
