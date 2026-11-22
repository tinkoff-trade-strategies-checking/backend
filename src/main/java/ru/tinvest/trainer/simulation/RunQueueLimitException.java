package ru.tinvest.trainer.simulation;

/** Превышен лимит очереди прогонов на пользователя (429 {@code TOO_MANY_REQUESTS}). */
final class RunQueueLimitException extends RuntimeException {

    RunQueueLimitException(String message) {
        super(message);
    }
}
