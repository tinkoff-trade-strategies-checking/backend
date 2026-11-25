package ru.tinvest.trainer.marketdata;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Параметры запуска ручной сессии (контракт: {@code CreateSessionRequest}).
 *
 * @param customStartDate только для {@link StartMode#CUSTOM}
 * @param paperCount только для {@link StartMode#RANDOM}: сколько бумаг выбрать случайно (>= 1)
 * @param paperIds только для {@link StartMode#CUSTOM}: бумаги пользователя (>= 1), см. {@link Papers#id}
 */
public record LaunchRequest(
        int durationDays,
        Timeframe timeframe,
        Regime regime,
        StartMode startMode,
        @Nullable LocalDate customStartDate,
        @Nullable Integer paperCount,
        @Nullable List<UUID> paperIds,
        BigDecimal initialCapital) {}
