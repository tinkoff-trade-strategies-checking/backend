package ru.tinvest.trainer.simulation;

import ru.tinvest.trainer.worker.WorkerTypes.StrategyCheck;

/** Стратегия содержит статические ошибки DSL уровня {@code ERROR} (422 {@code STRATEGY_INVALID}). */
final class RunStrategyInvalidException extends RuntimeException {

    private final StrategyCheck check;

    RunStrategyInvalidException(StrategyCheck check) {
        super("Strategy contains compilation or lookahead errors");
        this.check = check;
    }

    StrategyCheck check() {
        return check;
    }
}
