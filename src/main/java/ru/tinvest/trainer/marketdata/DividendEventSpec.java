package ru.tinvest.trainer.marketdata;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Событие дивидендной выплаты MOEX (режим расчётов T+1, backend#93). Все денежные и процентные величины —
 * {@link BigDecimal} (INV-6).
 */
public record DividendEventSpec(
        long id,
        String ticker,
        String issuerName,
        LocalDate recordDate,
        LocalDate exDividendDate,
        LocalDate lastBuyDate,
        LocalDate paymentDate,
        BigDecimal dividendPerShareRub,
        BigDecimal yieldPct,
        BigDecimal estimatedGapPct,
        String currency) {

    /** Представление по схеме {@code DividendEventSummary} контракта {@code api/openapi.yaml} (INV-6). */
    public DividendEventSummary toSummary() {
        return new DividendEventSummary(
                id,
                ticker,
                issuerName,
                recordDate,
                exDividendDate,
                lastBuyDate,
                paymentDate,
                formatPerShare(dividendPerShareRub),
                scale2(yieldPct),
                scale2(estimatedGapPct),
                currency);
    }

    static String formatPerShare(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        int scale = Math.max(2, Math.min(4, stripped.scale()));
        return value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    static String scale2(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    public record DividendEventSummary(
            long id,
            String ticker,
            String issuerName,
            LocalDate recordDate,
            LocalDate exDividendDate,
            LocalDate lastBuyDate,
            LocalDate paymentDate,
            String dividendPerShareRub,
            String yieldPct,
            String estimatedGapPct,
            String currency) {}
}
