package ru.tinvest.trainer.marketdata;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * Спецификация облигации MOEX (ОФЗ или корпоративный выпуск, backend#90). Все денежные и процентные значения —
 * {@link BigDecimal} (INV-6).
 */
public record BondSpec(
        String ticker,
        String name,
        String issuer,
        String bondType,
        BigDecimal faceValueRub,
        BigDecimal couponRatePct,
        BigDecimal couponValueRub,
        int couponPeriodDays,
        LocalDate nextCouponDate,
        LocalDate maturityDate,
        @Nullable LocalDate offerDate,
        BigDecimal accruedInterestRub,
        BigDecimal ytmPct,
        BigDecimal durationYears,
        boolean amortized,
        BigDecimal pricePct,
        String currency) {

    /** Представление по схеме {@code BondSummary} контракта {@code api/openapi.yaml} (INV-6). */
    public BondSummary toSummary() {
        return new BondSummary(
                ticker,
                name,
                issuer,
                bondType,
                scale2(faceValueRub),
                scale2(couponRatePct),
                scale2(couponValueRub),
                couponPeriodDays,
                nextCouponDate,
                maturityDate,
                offerDate,
                scale2(accruedInterestRub),
                scale2(ytmPct),
                scale2(durationYears),
                amortized,
                scale2(pricePct),
                currency);
    }

    private static String scale2(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    public record BondSummary(
            String ticker,
            String name,
            String issuer,
            String bondType,
            String faceValueRub,
            String couponRatePct,
            String couponValueRub,
            int couponPeriodDays,
            LocalDate nextCouponDate,
            LocalDate maturityDate,
            @Nullable LocalDate offerDate,
            String accruedInterestRub,
            String ytmPct,
            String durationYears,
            boolean amortized,
            String pricePct,
            String currency) {}
}
