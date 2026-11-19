package ru.tinvest.trainer.marketdata;

/**
 * Шаг свечей. {@code minCandlesPerDay} — нижняя граница числа свечей за торговый день (только основная сессия MOEX
 * 10:00–18:50, 530 минут, округление вниз): по ней отсеиваются заведомо невозможные сочетания шага и длительности до
 * обращения к архиву. Точное число шагов считается по архиву ({@link ArchiveCatalog#sessionSteps}).
 */
public enum Timeframe {
    M1(60, 530),
    M5(300, 106),
    M10(600, 53),
    M15(900, 35),
    H1(3600, 9),
    D1(86400, 1);

    private final int seconds;
    private final int minCandlesPerDay;

    Timeframe(int seconds, int minCandlesPerDay) {
        this.seconds = seconds;
        this.minCandlesPerDay = minCandlesPerDay;
    }

    public int seconds() {
        return seconds;
    }

    public int minCandlesPerDay() {
        return minCandlesPerDay;
    }
}
