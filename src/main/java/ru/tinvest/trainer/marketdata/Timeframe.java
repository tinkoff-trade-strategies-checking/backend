package ru.tinvest.trainer.marketdata;

/**
 * Шаг свечей. {@code candlesPerDay} — оценка числа свечей за торговый день для лимита свечей ручной сессии:
 * основная сессия MOEX 10:00–18:50 и вечерняя 19:05–23:50 (815 минут), округление вверх.
 */
public enum Timeframe {
    M1(60, 815),
    M5(300, 163),
    M10(600, 82),
    M15(900, 55),
    H1(3600, 14),
    D1(86400, 1);

    private final int seconds;
    private final int candlesPerDay;

    Timeframe(int seconds, int candlesPerDay) {
        this.seconds = seconds;
        this.candlesPerDay = candlesPerDay;
    }

    public int seconds() {
        return seconds;
    }

    public int candlesPerDay() {
        return candlesPerDay;
    }
}
