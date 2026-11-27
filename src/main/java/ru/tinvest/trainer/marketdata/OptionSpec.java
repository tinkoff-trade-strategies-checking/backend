package ru.tinvest.trainer.marketdata;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Спецификация опционного контракта срочного рынка MOEX ФОРТС (backend#91). Все денежные, ценовые и процентные
 * величины — {@link BigDecimal} (INV-6).
 */
public record OptionSpec(
        String ticker,
        String name,
        String underlying,
        String optionType,
        BigDecimal strike,
        LocalDate expirationDate,
        BigDecimal underlyingPrice,
        BigDecimal impliedVolPct,
        BigDecimal riskFreeRatePct,
        int contractSize,
        BigDecimal initialMarginRub) {

    /** Представление по схеме {@code OptionSummary} контракта {@code api/openapi.yaml} (INV-6). */
    public OptionSummary toSummary() {
        return new OptionSummary(
                ticker,
                name,
                underlying,
                optionType,
                FuturesSpec.formatPrice(strike),
                expirationDate,
                FuturesSpec.formatPrice(underlyingPrice),
                scale2(impliedVolPct),
                scale2(riskFreeRatePct),
                contractSize,
                scale2(initialMarginRub));
    }

    private static String scale2(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    public record OptionSummary(
            String ticker,
            String name,
            String underlying,
            String optionType,
            String strike,
            LocalDate expirationDate,
            String underlyingPrice,
            String impliedVolPct,
            String riskFreeRatePct,
            int contractSize,
            String initialMarginRub) {}
}
