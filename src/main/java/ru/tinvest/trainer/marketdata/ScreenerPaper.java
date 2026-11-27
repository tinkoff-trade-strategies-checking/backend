package ru.tinvest.trainer.marketdata;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.jspecify.annotations.Nullable;

/**
 * Строка серверного скринера акций MOEX ({@code GET /api/v1/instruments/screener}, {@code api/openapi.yaml}). Все
 * числовые показатели передаются десятичными строками (INV-6).
 */
public record ScreenerPaper(
        String ticker,
        String name,
        String sector,
        @Nullable String marketCapRub,
        @Nullable String peRatio,
        @Nullable String pbRatio,
        @Nullable String psRatio,
        @Nullable String evEbitda,
        @Nullable String roe,
        @Nullable String netMargin,
        @Nullable String debtToEquity,
        @Nullable String dividendYield,
        @Nullable String beta,
        boolean hasFundamentals) {

    static ScreenerPaper from(String ticker, String name, String sector, @Nullable SecurityFundamental f) {
        if (f == null) {
            return new ScreenerPaper(
                    ticker, name, sector, null, null, null, null, null, null, null, null, null, null, false);
        }
        return new ScreenerPaper(
                ticker,
                name,
                sector,
                moneyOrNull(f.marketCapRub()),
                decimalOrNull(f.peRatio()),
                decimalOrNull(f.pbRatio()),
                decimalOrNull(f.psRatio()),
                decimalOrNull(f.evEbitda()),
                decimalOrNull(f.roe()),
                decimalOrNull(f.netMargin()),
                decimalOrNull(f.debtToEquity()),
                decimalOrNull(f.dividendYield()),
                decimalOrNull(f.beta()),
                true);
    }

    private static @Nullable String moneyOrNull(@Nullable BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static @Nullable String decimalOrNull(@Nullable BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.setScale(Math.max(2, stripped.scale())).toPlainString();
    }
}
