package ru.tinvest.trainer.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import ru.tinvest.trainer.api.InvalidRequestException;
import ru.tinvest.trainer.api.NotFoundException;

/**
 * Модульные тесты {@link FuturesCalculator}, эндпоинтов фьючерсов {@link InstrumentsController} и миграции
 * {@code V24__forts_futures_options.sql} (работают без Docker, INV-4, INV-6).
 */
class FuturesCalculatorTest {

    private final FuturesCalculator calculator = new FuturesCalculator();

    private static FuturesSpec siFutures() {
        return new FuturesSpec(
                "SiH7",
                "Фьючерс на курс USD/RUB (Si-3.27)",
                "USD/RUB",
                1000,
                new BigDecimal("1.0000"),
                new BigDecimal("1.0000"),
                new BigDecimal("14500.00"),
                new BigDecimal("95400.0000"),
                LocalDate.of(2027, 3, 18),
                "CASH",
                "RUB");
    }

    private static FuturesSpec brentFutures() {
        return new FuturesSpec(
                "BRF7",
                "Фьючерс на нефть Brent (BR-1.27)",
                "ICE Brent",
                10,
                new BigDecimal("0.0100"),
                new BigDecimal("9.5400"),
                new BigDecimal("11800.00"),
                new BigDecimal("76.4000"),
                LocalDate.of(2027, 1, 4),
                "CASH",
                "RUB");
    }

    @Test
    void calculatesLongPositionVariationMarginGoRoeAndLeverage() {
        FuturesSpec si = siFutures();
        LocalDate asOf = LocalDate.of(2027, 2, 16);

        FuturesCalculator.FuturesCalculation calc =
                calculator.calculate(si, new BigDecimal("95000"), new BigDecimal("96450"), 2, asOf);

        // (96450 - 95000) / 1 * 1 * 2 = +2900.00 RUB
        assertThat(calc.variationMarginRub()).isEqualByComparingTo("2900.00");
        // 14500 * 2 = 29000.00 RUB
        assertThat(calc.initialMarginTotalRub()).isEqualByComparingTo("29000.00");
        // 96450 * 2 = 192900.00 RUB
        assertThat(calc.notionalRub()).isEqualByComparingTo("192900.00");
        // 2900 / 29000 * 100 = 10.00%
        assertThat(calc.roeOnMarginPct()).isEqualByComparingTo("10.00");
        // 192900 / 29000 = 6.65x
        assertThat(calc.effectiveLeverage()).isEqualByComparingTo("6.65");
        assertThat(calc.daysToExpiration()).isEqualTo(30);
        assertThat(calc.expired()).isFalse();
    }

    @Test
    void calculatesShortPositionProfitWhenPriceFallsAndLossWhenPriceRises() {
        FuturesSpec brent = brentFutures();
        LocalDate asOf = LocalDate.of(2026, 12, 25);

        FuturesCalculator.FuturesCalculation profit =
                calculator.calculate(brent, new BigDecimal("77.40"), new BigDecimal("76.40"), -3, asOf);
        // (76.40 - 77.40) / 0.01 * 9.54 * (-3) = -100 * 9.54 * -3 = +2862.00 RUB
        assertThat(profit.variationMarginRub()).isEqualByComparingTo("2862.00");
        assertThat(profit.initialMarginTotalRub()).isEqualByComparingTo("35400.00");
        assertThat(profit.notionalRub()).isEqualByComparingTo("218656.80");
        assertThat(profit.roeOnMarginPct()).isPositive();
        assertThat(profit.daysToExpiration()).isEqualTo(10);

        FuturesCalculator.FuturesCalculation loss =
                calculator.calculate(brent, new BigDecimal("76.40"), new BigDecimal("77.40"), -3, asOf);
        assertThat(loss.variationMarginRub()).isEqualByComparingTo("-2862.00");
        assertThat(loss.roeOnMarginPct()).isNegative();
    }

    @Test
    void handlesExpirationDateAndPastExpirationGracefully() {
        FuturesSpec si = siFutures();

        FuturesCalculator.FuturesCalculation onExpiry = calculator.calculate(
                si, new BigDecimal("95000"), new BigDecimal("95500"), 1, LocalDate.of(2027, 3, 18));
        assertThat(onExpiry.daysToExpiration()).isZero();
        assertThat(onExpiry.expired()).isTrue();

        FuturesCalculator.FuturesCalculation afterExpiry =
                calculator.calculate(si, new BigDecimal("95000"), new BigDecimal("95500"), 1, LocalDate.of(2027, 4, 1));
        assertThat(afterExpiry.daysToExpiration()).isZero();
        assertThat(afterExpiry.expired()).isTrue();
    }

    @Test
    void rejectsInvalidInputs() {
        FuturesSpec si = siFutures();
        LocalDate asOf = LocalDate.of(2027, 2, 1);

        assertThatThrownBy(() -> calculator.calculate(si, BigDecimal.ZERO, new BigDecimal("95400"), 1, asOf))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(si, new BigDecimal("95400"), new BigDecimal("-1"), 1, asOf))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(si, new BigDecimal("95400"), new BigDecimal("95400"), 0, asOf))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void controllerValidatesParamsAndHandlesNotFound() {
        InstrumentsCatalog catalog = mock(InstrumentsCatalog.class);
        FuturesSpec si = siFutures();
        when(catalog.findFutures("SIH7")).thenReturn(Optional.of(si));
        when(catalog.findFutures("UNKNOWN")).thenReturn(Optional.empty());
        when(catalog.listFutures()).thenReturn(List.of(si));

        InstrumentsController controller =
                new InstrumentsController(catalog, new BondCalculator(), calculator, new OptionGreeksCalculator());

        assertThat(controller.listFutures()).hasSize(1);
        FuturesCalculator.FuturesCalculationResponse resp =
                controller.calculateFutures(" sih7 ", "95000", "96000", "2", "2027-02-16");
        assertThat(resp.ticker()).isEqualTo("SiH7");
        assertThat(resp.variationMarginRub()).isEqualTo("2000.00");
        assertThat(resp.initialMarginTotalRub()).isEqualTo("29000.00");

        assertThatThrownBy(() -> controller.calculateFutures("UNKNOWN", null, null, "1", "2027-02-16"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> controller.calculateFutures("SiH7", "95000", "96000", "0", "2027-02-16"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> controller.calculateFutures("SiH7", "-10", "96000", "1", "2027-02-16"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> controller.calculateFutures("SiH7", "95000", "96000", "abc", "2027-02-16"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> controller.calculateFutures("SiH7", "95000", "96000", "1", "bad-date"))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void migrationV24Seeds12FuturesAnd12Options() throws IOException {
        String sql = Files.readString(
                Path.of("src/main/resources/db/migration/V24__forts_futures_options.sql"), StandardCharsets.UTF_8);

        Pattern futuresPattern = Pattern.compile("\\('([A-Za-z0-9]+)',\\s*'Фьючерс[^']+',");
        Matcher fm = futuresPattern.matcher(sql);
        Set<String> futures = new HashSet<>();
        while (fm.find()) {
            futures.add(fm.group(1));
        }
        assertThat(futures)
                .containsExactlyInAnyOrder(
                        "SiH7", "RIH7", "MXH7", "BRF7", "GDH7", "SRH7", "GZH7", "CRH7", "NGF7", "EUH7", "LKH7", "SVH7");

        Pattern optionPattern =
                Pattern.compile("\\('([A-Za-z0-9]+)',\\s*'(?:Call|Put)[^']+',\\s*'[^']+',\\s*'(CALL|PUT)'");
        Matcher om = optionPattern.matcher(sql);
        Set<String> options = new HashSet<>();
        int calls = 0;
        int puts = 0;
        while (om.find()) {
            options.add(om.group(1));
            if ("CALL".equals(om.group(2))) {
                calls++;
            } else {
                puts++;
            }
        }
        assertThat(options).hasSize(12);
        assertThat(calls).isEqualTo(6);
        assertThat(puts).isEqualTo(6);
    }
}
