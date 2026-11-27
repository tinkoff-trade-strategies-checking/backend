package ru.tinvest.trainer.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import ru.tinvest.trainer.api.InvalidRequestException;
import ru.tinvest.trainer.api.NotFoundException;

/**
 * Модульные тесты {@link OptionGreeksCalculator} (Black-Scholes-Merton) и эндпоинтов опционов
 * {@link InstrumentsController} (работают без Docker, INV-6).
 */
class OptionGreeksCalculatorTest {

    private final OptionGreeksCalculator calculator = new OptionGreeksCalculator();

    private static OptionSpec sampleOption(String optionType, String strike) {
        return new OptionSpec(
                "CALL".equals(optionType) ? "TEST100C" : "TEST100P",
                "Test Option",
                "SiH7",
                optionType,
                new BigDecimal(strike),
                LocalDate.of(2028, 1, 1),
                new BigDecimal("100.00"),
                new BigDecimal("20.00"),
                new BigDecimal("5.00"),
                1,
                new BigDecimal("1000.00"));
    }

    @Test
    void matchesClassicBlackScholesReferenceValuesForAtmCallAndPut() {
        // S = 100, K = 100, T = 1.0 year (365 days), sigma = 20%, r = 5%
        // Аналитические эталоны BSM:
        // Call ≈ 10.4506, Delta ≈ 0.6368, Gamma ≈ 0.018762, Theta/day ≈ -0.01757, Vega(1%) ≈ 0.3752, Rho(1%) ≈ 0.5323
        // Put  ≈ 5.5735,  Delta ≈ -0.3632, Gamma ≈ 0.018762, Theta/day ≈ -0.00454, Vega(1%) ≈ 0.3752, Rho(1%) ≈ -0.4189
        LocalDate asOf = LocalDate.of(2027, 1, 1);

        OptionGreeksCalculator.OptionGreeksCalculation call = calculator.calculate(
                sampleOption("CALL", "100.00"),
                new BigDecimal("100.00"),
                new BigDecimal("20.00"),
                new BigDecimal("5.00"),
                asOf);
        OptionGreeksCalculator.OptionGreeksCalculation put = calculator.calculate(
                sampleOption("PUT", "100.00"),
                new BigDecimal("100.00"),
                new BigDecimal("20.00"),
                new BigDecimal("5.00"),
                asOf);

        assertThat(call.daysToExpiration()).isEqualTo(365);
        assertThat(call.expired()).isFalse();
        assertThat(call.premium().doubleValue()).isCloseTo(10.45, within(0.02));
        assertThat(call.intrinsicValue()).isEqualByComparingTo("0.00");
        assertThat(call.timeValue()).isEqualByComparingTo(call.premium());
        assertThat(call.delta().doubleValue()).isCloseTo(0.6368, within(0.001));
        assertThat(call.gamma().doubleValue()).isCloseTo(0.018762, within(0.0001));
        assertThat(call.theta().doubleValue()).isCloseTo(-0.0176, within(0.0005));
        assertThat(call.vega().doubleValue()).isCloseTo(0.3752, within(0.001));
        assertThat(call.rho().doubleValue()).isCloseTo(0.5323, within(0.002));

        assertThat(put.premium().doubleValue()).isCloseTo(5.57, within(0.02));
        assertThat(put.delta().doubleValue()).isCloseTo(-0.3632, within(0.001));
        assertThat(put.gamma()).isEqualByComparingTo(call.gamma());
        assertThat(put.vega()).isEqualByComparingTo(call.vega());
        assertThat(put.rho().doubleValue()).isCloseTo(-0.4189, within(0.002));

        // Паритет пут-колл: C - P = S - K * exp(-r*T) = 100 - 100 * exp(-0.05) ≈ 4.8771
        double parityLhs = call.premium().subtract(put.premium()).doubleValue();
        double parityRhs = 100.0 - 100.0 * Math.exp(-0.05);
        assertThat(parityLhs).isCloseTo(parityRhs, within(0.02));
        // delta(Call) - delta(Put) = 1
        assertThat(call.delta().subtract(put.delta())).isEqualByComparingTo("1.0000");
    }

    @Test
    void higherVolatilityIncreasesPremiumForBothCallAndPut() {
        LocalDate asOf = LocalDate.of(2027, 7, 1);
        OptionSpec callSpec = sampleOption("CALL", "105.00");

        OptionGreeksCalculator.OptionGreeksCalculation lowIv = calculator.calculate(
                callSpec, new BigDecimal("100.00"), new BigDecimal("15.00"), new BigDecimal("18.00"), asOf);
        OptionGreeksCalculator.OptionGreeksCalculation highIv = calculator.calculate(
                callSpec, new BigDecimal("100.00"), new BigDecimal("45.00"), new BigDecimal("18.00"), asOf);

        assertThat(highIv.premium()).isGreaterThan(lowIv.premium());
        assertThat(highIv.vega()).isPositive();
    }

    @Test
    void expiredOptionReturnsIntrinsicValueZeroTimeValueAndZeroGreeks() {
        LocalDate expiry = LocalDate.of(2028, 1, 1);

        OptionGreeksCalculator.OptionGreeksCalculation itmCall = calculator.calculate(
                sampleOption("CALL", "100.00"),
                new BigDecimal("115.00"),
                new BigDecimal("20.00"),
                new BigDecimal("18.00"),
                expiry);
        assertThat(itmCall.expired()).isTrue();
        assertThat(itmCall.daysToExpiration()).isZero();
        assertThat(itmCall.premium()).isEqualByComparingTo("15.00");
        assertThat(itmCall.intrinsicValue()).isEqualByComparingTo("15.00");
        assertThat(itmCall.timeValue()).isEqualByComparingTo("0.00");
        assertThat(itmCall.delta()).isEqualByComparingTo("1.0000");
        assertThat(itmCall.gamma()).isEqualByComparingTo("0.000000");
        assertThat(itmCall.theta()).isEqualByComparingTo("0.0000");
        assertThat(itmCall.vega()).isEqualByComparingTo("0.0000");
        assertThat(itmCall.rho()).isEqualByComparingTo("0.0000");

        OptionGreeksCalculator.OptionGreeksCalculation otmPut = calculator.calculate(
                sampleOption("PUT", "100.00"),
                new BigDecimal("115.00"),
                new BigDecimal("20.00"),
                new BigDecimal("18.00"),
                expiry);
        assertThat(otmPut.premium()).isEqualByComparingTo("0.00");
        assertThat(otmPut.delta()).isEqualByComparingTo("0.0000");
    }

    @Test
    void controllerValidatesParamsAndHandlesNotFound() {
        InstrumentsCatalog catalog = mock(InstrumentsCatalog.class);
        OptionSpec callSpec = sampleOption("CALL", "100.00");
        when(catalog.findOption("TEST100C")).thenReturn(Optional.of(callSpec));
        when(catalog.findOption("UNKNOWN")).thenReturn(Optional.empty());
        when(catalog.listOptions()).thenReturn(List.of(callSpec));

        InstrumentsController controller =
                new InstrumentsController(catalog, new BondCalculator(), new FuturesCalculator(), calculator);

        assertThat(controller.listOptions()).hasSize(1);
        OptionGreeksCalculator.OptionGreeksResponse resp =
                controller.calculateOptionGreeks(" test100c ", "100", "20", "5", "2027-01-01");
        assertThat(resp.ticker()).isEqualTo("TEST100C");
        assertThat(resp.optionType()).isEqualTo("CALL");
        assertThat(new BigDecimal(resp.premium())).isPositive();

        assertThatThrownBy(() -> controller.calculateOptionGreeks("UNKNOWN", null, null, null, null))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> controller.calculateOptionGreeks("TEST100C", "0", "20", "5", "2027-01-01"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> controller.calculateOptionGreeks("TEST100C", "100", "0", "5", "2027-01-01"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> controller.calculateOptionGreeks("TEST100C", "100", "20", "-1", "2027-01-01"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> controller.calculateOptionGreeks("TEST100C", "100", "20", "5", "bad-date"))
                .isInstanceOf(InvalidRequestException.class);
    }
}
