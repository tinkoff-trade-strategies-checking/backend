package ru.tinvest.trainer.marketdata;

import java.util.Arrays;
import java.util.Optional;

/**
 * Шаг свечей. {@code minCandlesPerDay} — нижняя граница числа свечей за торговый день (только основная сессия MOEX
 * 10:00–18:50, 530 минут, округление вниз): по ней отсеиваются заведомо невозможные сочетания шага и длительности до
 * обращения к архиву. Точное число шагов считается по архиву ({@link ArchiveCatalog#sessionSteps}).
 */
public enum Timeframe {
    M1("1m", 60, 530),
    M5("5m", 300, 106),
    M10("10m", 600, 53),
    M15("15m", 900, 35),
    H1("1h", 3600, 9),
    D1("1d", 86400, 1);

    private final String code;
    private final int seconds;
    private final int minCandlesPerDay;

    Timeframe(String code, int seconds, int minCandlesPerDay) {
        this.code = code;
        this.seconds = seconds;
        this.minCandlesPerDay = minCandlesPerDay;
    }

    /** Значение в контракте API ({@code Timeframe}): {@code 1m}, {@code 5m}, … {@code 1d}. */
    public String code() {
        return code;
    }

    public static Optional<Timeframe> fromCode(String code) {
        return Arrays.stream(values()).filter(t -> t.code.equals(code)).findFirst();
    }

    public int seconds() {
        return seconds;
    }

    public int minCandlesPerDay() {
        return minCandlesPerDay;
    }
}
