package ru.tinvest.trainer.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.tinvest.trainer.marketdata.TaxCalculator.AccountType;

/**
 * Юнит-тесты серверных калькуляторов дивидендов T+1, НДФЛ 13%/15% с сальдированием и ИИС, а также DCA (TWR/XIRR)
 * (backend#93, backend#94, INV-4, INV-6).
 */
class TaxAndDcaCalculatorTest {

    private final DividendCalculator dividendCalculator = new DividendCalculator();
    private final TaxCalculator taxCalculator = new TaxCalculator();
    private final DcaCalculator dcaCalculator = new DcaCalculator();

    @Test
    void dividendCalculatorComputesExact13And15PercentWithholding() {
        DividendEventSpec sber = new DividendEventSpec(
                1L,
                "SBER",
                "Сбербанк",
                LocalDate.of(2026, 7, 17),
                LocalDate.of(2026, 7, 17),
                LocalDate.of(2026, 7, 16),
                LocalDate.of(2026, 8, 3),
                new BigDecimal("36.5000"),
                new BigDecimal("11.4000"),
                new BigDecimal("9.9200"),
                "RUB");

        DividendCalculator.DividendPayout p13 = dividendCalculator.calculate(sber, 100, 13);
        assertThat(p13.grossPayoutRub()).isEqualByComparingTo("3650.00");
        assertThat(p13.taxWithheldRub()).isEqualByComparingTo("474.50");
        assertThat(p13.netPayoutRub()).isEqualByComparingTo("3175.50");
        assertThat(p13.lastBuyDate()).isEqualTo(LocalDate.of(2026, 7, 16));
        assertThat(p13.exDividendDate()).isEqualTo(LocalDate.of(2026, 7, 17));
        assertThat(p13.recordDate()).isEqualTo(LocalDate.of(2026, 7, 17));

        DividendCalculator.DividendPayout p15 = dividendCalculator.calculate(sber, 100, 15);
        assertThat(p15.taxWithheldRub()).isEqualByComparingTo("547.50");
        assertThat(p15.netPayoutRub()).isEqualByComparingTo("3102.50");

        assertThatThrownBy(() -> dividendCalculator.calculate(sber, 0, 13))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dividendCalculator.calculate(sber, 10, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void taxCalculatorAppliesProgressive13And15NdflAndLossOffsetting() {
        // 13% до 2 400 000, 15% свыше: прибыль 3 500 000, убыток прошлых лет 500 000 -> база 3 000 000.
        // Налог: 2 400 000 * 13% (312 000) + 600 000 * 15% (90 000) = 402 000.
        TaxCalculator.TaxCalculation res = taxCalculator.calculate(
                new BigDecimal("3500000.00"),
                new BigDecimal("500000.00"),
                new BigDecimal("200000.00"),
                AccountType.BROKERAGE,
                BigDecimal.ZERO);

        assertThat(res.lossOffsetUsedRub()).isEqualByComparingTo("500000.00");
        assertThat(res.taxableProfitRub()).isEqualByComparingTo("3000000.00");
        assertThat(res.tradingTaxRub()).isEqualByComparingTo("402000.00");
        // Дивиденды сверх порога 2.4M попадают полностью в 15%: 200 000 * 15% = 30 000.
        assertThat(res.dividendTaxRub()).isEqualByComparingTo("30000.00");
        assertThat(res.totalTaxRub()).isEqualByComparingTo("432000.00");
        assertThat(res.iisDeductionRefundRub()).isEqualByComparingTo("0.00");
        assertThat(res.netAfterTaxRub()).isEqualByComparingTo("3268000.00");
    }

    @Test
    void taxCalculatorHandlesIisTypeAAndTypeBAndIis3Correctly() {
        // ИИС-А: взнос 600 000 -> вычет с потолком 400 000 * 13% = 52 000.
        TaxCalculator.TaxCalculation iisA13 = taxCalculator.calculate(
                new BigDecimal("500000.00"),
                BigDecimal.ZERO,
                new BigDecimal("100000.00"),
                AccountType.IIS_A,
                new BigDecimal("600000.00"));
        assertThat(iisA13.tradingTaxRub()).isEqualByComparingTo("65000.00");
        assertThat(iisA13.dividendTaxRub()).isEqualByComparingTo("13000.00");
        assertThat(iisA13.totalTaxRub()).isEqualByComparingTo("78000.00");
        assertThat(iisA13.iisDeductionRefundRub()).isEqualByComparingTo("52000.00");
        assertThat(iisA13.netAfterTaxRub()).isEqualByComparingTo("574000.00");

        // ИИС-А при доходе > 2 400 000: вычет по ставке 15% = 60 000.
        TaxCalculator.TaxCalculation iisA15 = taxCalculator.calculate(
                new BigDecimal("2600000.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                AccountType.IIS_A,
                new BigDecimal("400000.00"));
        assertThat(iisA15.iisDeductionRefundRub()).isEqualByComparingTo("60000.00");

        // ИИС-Б и ИИС-3: торговая прибыль не облагается НДФЛ, дивиденды облагаются по НК РФ!
        TaxCalculator.TaxCalculation iisB = taxCalculator.calculate(
                new BigDecimal("1000000.00"),
                BigDecimal.ZERO,
                new BigDecimal("100000.00"),
                AccountType.IIS_B,
                new BigDecimal("400000.00"));
        assertThat(iisB.tradingTaxRub()).isEqualByComparingTo("0.00");
        assertThat(iisB.dividendTaxRub()).isEqualByComparingTo("13000.00");
        assertThat(iisB.totalTaxRub()).isEqualByComparingTo("13000.00");
        assertThat(iisB.iisDeductionRefundRub()).isEqualByComparingTo("0.00");
        assertThat(iisB.netAfterTaxRub()).isEqualByComparingTo("1087000.00");

        TaxCalculator.TaxCalculation iis3 = taxCalculator.calculate(
                new BigDecimal("1000000.00"),
                BigDecimal.ZERO,
                new BigDecimal("100000.00"),
                AccountType.IIS_3,
                BigDecimal.ZERO);
        assertThat(iis3.tradingTaxRub()).isEqualByComparingTo("0.00");
        assertThat(iis3.dividendTaxRub()).isEqualByComparingTo("13000.00");
    }

    @Test
    void taxCalculatorHandlesNegativeGrossProfitWithoutTax() {
        TaxCalculator.TaxCalculation res = taxCalculator.calculate(
                new BigDecimal("-150000.00"),
                new BigDecimal("50000.00"),
                BigDecimal.ZERO,
                AccountType.BROKERAGE,
                BigDecimal.ZERO);
        assertThat(res.taxableProfitRub()).isEqualByComparingTo("0.00");
        assertThat(res.lossOffsetUsedRub()).isEqualByComparingTo("0.00");
        assertThat(res.totalTaxRub()).isEqualByComparingTo("0.00");
        assertThat(res.effectiveTaxRatePct()).isEqualByComparingTo("0.00");
    }

    @Test
    void dcaCalculatorComputesExactXirrAndChainLinkedTwr() {
        // Контрольный пример 1: одиночный вклад 100 000 на 365 дней -> 120 000 => XIRR = 20.00%, TWR = 20.00%.
        DcaCalculator.DcaCalculation single = dcaCalculator.calculate(
                List.of(new DcaCalculator.Cashflow(LocalDate.of(2025, 1, 1), new BigDecimal("100000.00"), null)),
                new BigDecimal("120000.00"),
                LocalDate.of(2026, 1, 1));
        assertThat(single.totalContributedRub()).isEqualByComparingTo("100000.00");
        assertThat(single.totalWithdrawnRub()).isEqualByComparingTo("0.00");
        assertThat(single.netInvestedRub()).isEqualByComparingTo("100000.00");
        assertThat(single.netProfitRub()).isEqualByComparingTo("20000.00");
        assertThat(single.simpleReturnPct()).isEqualByComparingTo("20.00");
        assertThat(single.xirrPct()).isEqualByComparingTo("20.00");
        assertThat(single.twrPct()).isEqualByComparingTo("20.00");
        assertThat(single.holdingDays()).isEqualTo(365);

        // Контрольный пример 2: цепной TWR при пополнении в середине года.
        // Период 1: 100 000 -> 110 000 (+10%). Пополнение +100 000 -> старт периода 2 = 210 000.
        // Период 2: 210 000 -> 252 000 (+20%). Цепной TWR = (1.10 * 1.20 - 1) * 100 = 32.00%.
        DcaCalculator.DcaCalculation chain = dcaCalculator.calculate(
                List.of(
                        new DcaCalculator.Cashflow(LocalDate.of(2025, 1, 1), new BigDecimal("100000.00"), null),
                        new DcaCalculator.Cashflow(
                                LocalDate.of(2025, 7, 2), new BigDecimal("100000.00"), new BigDecimal("110000.00"))),
                new BigDecimal("252000.00"),
                LocalDate.of(2026, 1, 1));
        assertThat(chain.totalContributedRub()).isEqualByComparingTo("200000.00");
        assertThat(chain.netProfitRub()).isEqualByComparingTo("52000.00");
        assertThat(chain.twrPct()).isEqualByComparingTo("32.00");
        // XIRR выше простой доходности 26%, так как второй транш работал полгода.
        assertThat(chain.xirrPct()).isGreaterThan(new BigDecimal("30.00"));
    }

    @Test
    void dcaCalculatorHandlesWithdrawalsAndModifiedDietzTwr() {
        // Пополнение 200 000 в начале года, снятие 50 000 в середине года, итог 180 000.
        DcaCalculator.DcaCalculation res = dcaCalculator.calculate(
                List.of(
                        new DcaCalculator.Cashflow(LocalDate.of(2025, 1, 1), new BigDecimal("200000.00"), null),
                        new DcaCalculator.Cashflow(LocalDate.of(2025, 7, 2), new BigDecimal("-50000.00"), null)),
                new BigDecimal("180000.00"),
                LocalDate.of(2026, 1, 1));
        assertThat(res.totalContributedRub()).isEqualByComparingTo("200000.00");
        assertThat(res.totalWithdrawnRub()).isEqualByComparingTo("50000.00");
        assertThat(res.netInvestedRub()).isEqualByComparingTo("150000.00");
        assertThat(res.netProfitRub()).isEqualByComparingTo("30000.00");
        assertThat(res.xirrPct()).isPositive();
        assertThat(res.twrPct()).isPositive();
    }
}
