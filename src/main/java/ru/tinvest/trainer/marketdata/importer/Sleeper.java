package ru.tinvest.trainer.marketdata.importer;

import java.time.Duration;

/** Пауза между запросами; в тестах подменяется, чтобы не ждать по-настоящему. */
@FunctionalInterface
interface Sleeper {

    Sleeper REAL = duration -> {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImportException("Импорт прерван", e);
        }
    };

    void sleep(Duration duration);
}
