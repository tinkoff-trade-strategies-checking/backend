package ru.tinvest.trainer.sessions;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Пользовательский алерт по цене или индикатору RSI ({@code sessions.price_alert}, backend#95). Пороговое значение
 * хранится как {@link BigDecimal} (INV-6).
 */
public record PriceAlert(
        UUID id,
        UUID userId,
        String ticker,
        ConditionType conditionType,
        BigDecimal thresholdValue,
        boolean active,
        @Nullable String note,
        OffsetDateTime createdAt) {

    public enum ConditionType {
        PRICE_ABOVE,
        PRICE_BELOW,
        RSI_BELOW,
        RSI_ABOVE
    }

    /** Представление по схеме {@code PriceAlertResponse} контракта {@code api/openapi.yaml} (INV-6). */
    public PriceAlertResponse toResponse() {
        return new PriceAlertResponse(
                id, ticker, conditionType.name(), formatThreshold(thresholdValue), active, note, createdAt);
    }

    static String formatThreshold(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        int scale = Math.max(2, Math.min(4, stripped.scale()));
        return value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    public record PriceAlertResponse(
            UUID id,
            String ticker,
            String conditionType,
            String thresholdValue,
            boolean active,
            @Nullable String note,
            OffsetDateTime createdAt) {}
}
