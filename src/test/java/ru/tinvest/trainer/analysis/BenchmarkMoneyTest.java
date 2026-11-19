package ru.tinvest.trainer.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Капитал бенчмарка из core приходит с 9 знаками; временное округление до копейки (core#58) не роняет отчёт. */
class BenchmarkMoneyTest {

    @Test
    void roundsNineDigitsToKopecksHalfUp() {
        assertThat(ReportViews.benchmarkMoney(new BigDecimal("10000000.004999999")))
                .isEqualTo("10000000.00");
        assertThat(ReportViews.benchmarkMoney(new BigDecimal("10000000.005000000")))
                .isEqualTo("10000000.01");
        assertThat(ReportViews.benchmarkMoney(new BigDecimal("9876543.2"))).isEqualTo("9876543.20");
    }
}
