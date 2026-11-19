package ru.tinvest.trainer.sessions;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * Команда журнала сессии для выгрузки отчёта: что подал пользователь и принято ли (данные журнала, не пересчёт).
 * {@code type} — {@code place}, {@code cancel}, {@code replace}; {@code orderId} — заявка ядра у отмены и замены;
 * параметры заявки у отмены пустые. {@code ocoLeg} — вторая нога OCO той же команды подачи (тот же {@code seq}). Только для завершённой (раскрытой) сессии (INV-7).
 */
public record CommandLogEntry(
        long seq,
        long step,
        String type,
        @Nullable Long orderId,
        @Nullable String ticker,
        @Nullable String side,
        @Nullable String kind,
        @Nullable Long lots,
        @Nullable BigDecimal limitPrice,
        @Nullable BigDecimal stopPrice,
        @Nullable BigDecimal trailingPercent,
        @Nullable String tif,
        boolean ocoLeg,
        boolean accepted,
        @Nullable String rejectReason) {}
