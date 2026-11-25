package ru.tinvest.trainer.marketdata;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Point-in-time срез фундаментальных показателей бумаги MOEX (INV-1, INV-9). Процентные поля хранятся и отдаются уже
 * умноженными на 100 (например, {@code 23.40} = 23.40%).
 */
public record SecurityFundamental(
        String ticker,
        LocalDate reportDate,
        LocalDate availableAt,
        String periodType,
        String currency,
        @Nullable BigDecimal marketCapRub,
        @Nullable BigDecimal peRatio,
        @Nullable BigDecimal pbRatio,
        @Nullable BigDecimal psRatio,
        @Nullable BigDecimal evEbitda,
        @Nullable BigDecimal roe,
        @Nullable BigDecimal roa,
        @Nullable BigDecimal grossMargin,
        @Nullable BigDecimal operatingMargin,
        @Nullable BigDecimal netMargin,
        @Nullable BigDecimal revenueGrowthYoy,
        @Nullable BigDecimal epsGrowthYoy,
        @Nullable BigDecimal debtToEquity,
        @Nullable BigDecimal currentRatio,
        @Nullable BigDecimal eps,
        @Nullable BigDecimal bookValuePerShare,
        @Nullable BigDecimal dividendYield,
        @Nullable BigDecimal payoutRatio,
        @Nullable BigDecimal freeFloat,
        @Nullable BigDecimal beta) {

    /** Представление по схеме {@code FundamentalSnapshot} контракта {@code api/openapi.yaml} (INV-6). */
    public Map<String, @Nullable Object> toSnapshotMap() {
        Map<String, @Nullable Object> out = new LinkedHashMap<>();
        out.put("ticker", ticker);
        out.put("reportDate", reportDate.toString());
        out.put("availableAt", availableAt.toString());
        out.put("periodType", periodType);
        out.put("currency", currency);
        out.put("marketCapRub", moneyOrNull(marketCapRub));
        out.put("peRatio", decimalOrNull(peRatio));
        out.put("pbRatio", decimalOrNull(pbRatio));
        out.put("psRatio", decimalOrNull(psRatio));
        out.put("evEbitda", decimalOrNull(evEbitda));
        out.put("roe", decimalOrNull(roe));
        out.put("roa", decimalOrNull(roa));
        out.put("grossMargin", decimalOrNull(grossMargin));
        out.put("operatingMargin", decimalOrNull(operatingMargin));
        out.put("netMargin", decimalOrNull(netMargin));
        out.put("revenueGrowthYoy", decimalOrNull(revenueGrowthYoy));
        out.put("epsGrowthYoy", decimalOrNull(epsGrowthYoy));
        out.put("debtToEquity", decimalOrNull(debtToEquity));
        out.put("currentRatio", decimalOrNull(currentRatio));
        out.put("eps", moneyOrNull(eps));
        out.put("bookValuePerShare", moneyOrNull(bookValuePerShare));
        out.put("dividendYield", decimalOrNull(dividendYield));
        out.put("payoutRatio", decimalOrNull(payoutRatio));
        out.put("freeFloat", decimalOrNull(freeFloat));
        out.put("beta", decimalOrNull(beta));
        return out;
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
