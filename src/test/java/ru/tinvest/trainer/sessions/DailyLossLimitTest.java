package ru.tinvest.trainer.sessions;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Точность расчёта порога дневного убытка (kill-switch, INV-6). */
class DailyLossLimitTest {

    @Test
    void disabledWhenLimitIsNull() {
        assertThat(Arena.isDailyLossExceeded(null, new BigDecimal("500000.00"), new BigDecimal("100000.00")))
                .isFalse();
    }

    @Test
    void exactBoundaryPrecision() {
        BigDecimal start = new BigDecimal("500000.00");
        BigDecimal limit = new BigDecimal("5.00");

        // Ровно 5.00% убытка (25000.00) -> срабатывает
        assertThat(Arena.isDailyLossExceeded(limit, start, new BigDecimal("475000.00")))
                .isTrue();
        // На копейку выше порога (24999.99) -> не срабатывает
        assertThat(Arena.isDailyLossExceeded(limit, start, new BigDecimal("475000.01")))
                .isFalse();
        // Прибыль -> не срабатывает
        assertThat(Arena.isDailyLossExceeded(limit, start, new BigDecimal("510000.00")))
                .isFalse();
        // Нулевой или отрицательный капитал начала дня -> срабатывает
        assertThat(Arena.isDailyLossExceeded(limit, BigDecimal.ZERO, BigDecimal.ZERO))
                .isTrue();
    }
}
