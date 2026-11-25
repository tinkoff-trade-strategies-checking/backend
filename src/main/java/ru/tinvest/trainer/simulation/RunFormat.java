package ru.tinvest.trainer.simulation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.jspecify.annotations.Nullable;
import ru.tinvest.trainer.worker.WorkerTypes.OrderKind;
import ru.tinvest.trainer.worker.WorkerTypes.OrderPhase;
import ru.tinvest.trainer.worker.WorkerTypes.TradeSide;

/** Форматирование значений прогона и отчёта алгоритма по правилам {@code api/openapi.yaml} и INV-6. */
final class RunFormat {

    private RunFormat() {}

    static String number(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.setScale(Math.max(2, stripped.scale())).toPlainString();
    }

    static @Nullable String numberOrNull(@Nullable BigDecimal value) {
        return value == null ? null : number(value);
    }

    static String money(BigDecimal value) {
        return value.setScale(2).toPlainString();
    }

    static String instant(long unixMs) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                Instant.ofEpochMilli(unixMs).atOffset(ZoneOffset.UTC));
    }

    static String label(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("Индекс бумаги должен быть >= 0: " + index);
        }
        StringBuilder letters = new StringBuilder();
        int n = index;
        do {
            letters.append((char) ('A' + (n % 26)));
            n = n / 26 - 1;
        } while (n >= 0);
        return "Бумага " + letters.reverse();
    }

    static String side(TradeSide side) {
        return side == TradeSide.BUY ? "buy" : "sell";
    }

    static String legType(OrderKind kind) {
        return switch (kind) {
            case MARKET -> "market";
            case LIMIT -> "limit";
            case STOP -> "stop";
            case STOP_LIMIT -> "stop_limit";
            case TAKE_PROFIT -> "take_profit";
            case TRAILING_STOP -> "trailing";
        };
    }

    static String orderStatus(OrderPhase phase) {
        return switch (phase) {
            case ACTIVE -> "active";
            case PARTIALLY_FILLED -> "partial";
            case FILLED -> "filled";
            case CANCELLED -> "cancelled";
            case EXPIRED -> "expired";
            case REJECTED -> "rejected";
        };
    }

    static String pct(double fraction) {
        return number(BigDecimal.valueOf(finite(fraction)).movePointRight(2).setScale(6, RoundingMode.HALF_UP));
    }

    static @Nullable String pctOrNull(@Nullable Double fraction) {
        return fraction == null ? null : pct(fraction);
    }

    static String ratio(double value) {
        return number(BigDecimal.valueOf(finite(value)).setScale(6, RoundingMode.HALF_UP));
    }

    static @Nullable String ratioOrNull(@Nullable Double value) {
        return value == null ? null : ratio(value);
    }

    private static double finite(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalStateException("Метрика отчёта ядра не число: " + value);
        }
        return value;
    }
}
