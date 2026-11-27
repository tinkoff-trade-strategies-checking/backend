package ru.tinvest.trainer.marketdata;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Спецификация БПИФ/ETF (включая фонды ликвидности под RUONIA), валюты или драгметалла MOEX (backend#92).
 */
public record EtfSpec(
        String ticker,
        String name,
        String assetClass,
        String category,
        String underlyingIndex,
        boolean tracksRuonia,
        BigDecimal expenseRatioPct,
        BigDecimal navPriceRub,
        String currency,
        String description) {

    /** Представление по схеме {@code EtfSummary} контракта {@code api/openapi.yaml} (INV-6). */
    public EtfSummary toSummary() {
        return new EtfSummary(
                ticker,
                name,
                assetClass,
                category,
                underlyingIndex,
                tracksRuonia,
                formatPct(expenseRatioPct),
                formatNav(navPriceRub),
                currency,
                description);
    }

    private static String formatPct(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        int scale = Math.max(2, Math.min(4, stripped.scale()));
        return value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    private static String formatNav(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        int scale = Math.max(2, Math.min(4, stripped.scale()));
        return value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    public record EtfSummary(
            String ticker,
            String name,
            String assetClass,
            String category,
            String underlyingIndex,
            boolean tracksRuonia,
            String expenseRatioPct,
            String navPriceRub,
            String currency,
            String description) {}
}
