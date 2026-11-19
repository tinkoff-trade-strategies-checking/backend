package ru.tinvest.trainer.sessions;

import java.util.UUID;

/** Сессия завершена (статус записан); публикуется в транзакции завершения. Слушает модуль отчёта. */
public record SessionFinished(UUID sessionId) {}
