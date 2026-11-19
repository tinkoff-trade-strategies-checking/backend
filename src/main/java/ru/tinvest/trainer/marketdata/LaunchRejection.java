package ru.tinvest.trainer.marketdata;

import org.jspecify.annotations.Nullable;

/**
 * Отказ в запуске (контракт: {@code LaunchProblem}). Поля — только имя параметра и числа лимита, без дат, тикеров и
 * метрик (INV-1, INV-7).
 *
 * @param field имя поля запроса, из-за которого отказ
 * @param barsCount для {@link LaunchRejectReason#TOO_MANY_BARS}: сколько свечей получилось бы
 * @param maxBars для {@link LaunchRejectReason#TOO_MANY_BARS}: предел
 */
public record LaunchRejection(
        LaunchRejectReason reason,
        @Nullable String field,
        @Nullable Integer barsCount,
        @Nullable Integer maxBars) implements LaunchResult {

    static LaunchRejection of(LaunchRejectReason reason, String field) {
        return new LaunchRejection(reason, field, null, null);
    }
}
