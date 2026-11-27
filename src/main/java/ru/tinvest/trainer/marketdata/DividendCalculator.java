package ru.tinvest.trainer.marketdata;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import org.springframework.stereotype.Service;

/**
 * Точный серверный расчёт дивидендной выплаты, удерживаемого НДФЛ (13% / 15%) и дат отсечки T+1 на
 * {@link BigDecimal} (INV-4, INV-6, backend#93).
 */
@Service
public class DividendCalculator {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal RATE_13 = new BigDecimal("13");
    private static final BigDecimal RATE_15 = new BigDecimal("15");

    public DividendPayout calculate(DividendEventSpec event, int shares, int taxRatePct) {
        if (shares < 1) {
            throw new IllegalArgumentException("shares must be >= 1");
        }
        BigDecimal rate = switch (taxRatePct) {
            case 13 -> RATE_13;
            case 15 -> RATE_15;
            default -> throw new IllegalArgumentException("taxRatePct must be 13 or 15");
        };

        BigDecimal grossPayoutRub = event.dividendPerShareRub()
                .multiply(BigDecimal.valueOf(shares), MC)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal taxWithheldRub = grossPayoutRub.multiply(rate, MC).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        BigDecimal netPayoutRub = grossPayoutRub.subtract(taxWithheldRub).setScale(2, RoundingMode.HALF_UP);

        return new DividendPayout(
                event.ticker(),
                event.issuerName(),
                shares,
                rate.setScale(2, RoundingMode.HALF_UP),
                event.dividendPerShareRub(),
                grossPayoutRub,
                taxWithheldRub,
                netPayoutRub,
                event.lastBuyDate(),
                event.exDividendDate(),
                event.recordDate(),
                event.paymentDate(),
                event.yieldPct().setScale(2, RoundingMode.HALF_UP),
                event.estimatedGapPct().setScale(2, RoundingMode.HALF_UP),
                event.currency());
    }

    public record DividendPayout(
            String ticker,
            String issuerName,
            int shares,
            BigDecimal taxRatePct,
            BigDecimal dividendPerShareRub,
            BigDecimal grossPayoutRub,
            BigDecimal taxWithheldRub,
            BigDecimal netPayoutRub,
            LocalDate lastBuyDate,
            LocalDate exDividendDate,
            LocalDate recordDate,
            LocalDate paymentDate,
            BigDecimal yieldPct,
            BigDecimal estimatedGapPct,
            String currency) {

        /** Представление по схеме {@code DividendPayoutResponse} контракта {@code api/openapi.yaml} (INV-6). */
        public DividendPayoutResponse toResponse() {
            return new DividendPayoutResponse(
                    ticker,
                    issuerName,
                    shares,
                    taxRatePct.toPlainString(),
                    DividendEventSpec.formatPerShare(dividendPerShareRub),
                    grossPayoutRub.toPlainString(),
                    taxWithheldRub.toPlainString(),
                    netPayoutRub.toPlainString(),
                    lastBuyDate,
                    exDividendDate,
                    recordDate,
                    paymentDate,
                    yieldPct.toPlainString(),
                    estimatedGapPct.toPlainString(),
                    currency);
        }
    }

    public record DividendPayoutResponse(
            String ticker,
            String issuerName,
            int shares,
            String taxRatePct,
            String dividendPerShareRub,
            String grossPayoutRub,
            String taxWithheldRub,
            String netPayoutRub,
            LocalDate lastBuyDate,
            LocalDate exDividendDate,
            LocalDate recordDate,
            LocalDate paymentDate,
            String yieldPct,
            String estimatedGapPct,
            String currency) {}
}
