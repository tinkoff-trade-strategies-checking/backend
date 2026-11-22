package ru.tinvest.trainer.workshop;

import ru.tinvest.trainer.worker.WorkerTypes.StrategyCheck;

/** Статические ошибки DSL не позволяют опубликовать версию (422 {@code STRATEGY_INVALID}). */
final class StrategyInvalidException extends RuntimeException {

    private final StrategyCheck check;

    StrategyInvalidException(StrategyCheck check) {
        super("Strategy contains static errors and cannot be published", null, false, false);
        this.check = check;
    }

    StrategyCheck check() {
        return check;
    }
}
