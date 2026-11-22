package ru.tinvest.trainer.analysis;

/** Сессия ещё не завершена: сравнение и выгрузка недоступны (409 {@code NOT_FINISHED}, INV-1/INV-7). */
final class SessionNotFinishedException extends RuntimeException {

    SessionNotFinishedException(String message) {
        super(message);
    }
}
