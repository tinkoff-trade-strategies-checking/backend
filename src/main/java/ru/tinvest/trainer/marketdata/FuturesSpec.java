package ru.tinvest.trainer.marketdata;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Спецификация фьючерсного контракта срочного рынка MOEX ФОРТС (backend#91). Все денежные и ценовые величины —
 * {@link BigDecimal} (INV-6).
 */
public record FuturesSpec(
        String ticker,
        String name,
        String underlying,
        int contractSize,
        BigDecimal priceStep,
        BigDecimal stepPriceRub,
        BigDecimal initialMarginRub,
        BigDecimal lastPrice,
        LocalDate expirationDate,
        String settlementType,
        String currency) {

    /** Представление по схеме {@code FuturesSummary} контракта {@code api/openapi.yaml} (INV-6). */
    public FuturesSummary toSummary() {
        return new FuturesSummary(
                ticker,
                name,
                underlying,
                contractSize,
                formatPrice(priceStep),
                formatPrice(stepPriceRub),
                scale2(initialMarginRub),
                formatPrice(lastPrice),
                expirationDate,
                settlementType,
                currency);
    }

    static String formatPrice(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        int scale = Math.max(2, Math.min(4, stripped.scale()));
        return value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    static String scale2(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    public record FuturesSummary(
            String ticker,
            String name,
            String underlying,
            int contractSize,
            String priceStep,
            String stepPriceRub,
            String initialMarginRub,
            String lastPrice,
            LocalDate expirationDate,
            String settlementType,
            String currency) {}
}
