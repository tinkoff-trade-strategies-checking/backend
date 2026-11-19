package ru.tinvest.trainer.sessions;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import ru.tinvest.trainer.api.InvalidRequestException;
import ru.tinvest.trainer.worker.WorkerTypes.OrderKind;
import ru.tinvest.trainer.worker.WorkerTypes.OrderParams;
import ru.tinvest.trainer.worker.WorkerTypes.RejectCode;
import ru.tinvest.trainer.worker.WorkerTypes.Tif;
import ru.tinvest.trainer.worker.WorkerTypes.TradeSide;

/**
 * Тело подачи, замены и оценки заявки ({@code PlaceOrderRequest}, {@code ReplaceOrderRequest},
 * {@code EstimateOrderRequest}): одна запись с необязательными полями. Нарушение формата (не те значения перечислений,
 * цена не десятичная строка) — 400; не заданная обязательная цена типа — отказ {@code INVALID_PARAMS} с полем.
 */
record OrderRequest(
        @Nullable Long seenStep,
        @Nullable Integer paperIndex,
        @Nullable UUID paperId,
        @Nullable String side,
        @Nullable String type,
        @Nullable Long lots,
        @Nullable String tif,
        @Nullable String limitPrice,
        @Nullable String stopPrice,
        @Nullable String takeProfitPrice,
        @Nullable String trailingPct) {

    private static final Pattern PRICE = Pattern.compile("^\\d+(\\.\\d{1,9})?$");
    private static final Pattern PCT = Pattern.compile("^\\d+(\\.\\d{1,6})?$");

    /** Тип в запросе подачи и оценки: {@code oco} — две ноги. */
    static final String OCO = "oco";

    /** Шаг, который видел клиент; обязателен в подаче и замене. */
    long requireSeenStep() {
        if (seenStep == null || seenStep < 0) {
            throw new InvalidRequestException("seenStep is required and must not be negative");
        }
        return seenStep;
    }

    boolean isOco() {
        return OCO.equals(type);
    }

    /**
     * Ноги заявки подачи или оценки: одна заявка либо стоп и тейк-профит OCO. Бумага определяется по режиму сессии:
     * {@code paperIndex} в слепой, {@code paperId} в раскрытой (не тот вид — 400).
     *
     * @param blindPrice цены в слепой шкале (перевод в рубли делает {@code EstimateOrder})
     */
    List<OrderParams> legs(SessionRecord session, boolean blindPrice) {
        PaperRecord paper = paper(session);
        TradeSide tradeSide = parseSide();
        long lotCount = parseLots();
        Tif timeInForce = parseTif();
        if (type == null) {
            throw new InvalidRequestException("type is required");
        }
        if (isOco()) {
            return List.of(
                    leg(paper.secid(), tradeSide, OrderKind.STOP, lotCount, timeInForce, blindPrice),
                    leg(paper.secid(), tradeSide, OrderKind.TAKE_PROFIT, lotCount, timeInForce, blindPrice));
        }
        return List.of(leg(paper.secid(), tradeSide, kind(), lotCount, timeInForce, blindPrice));
    }

    /**
     * Новая заявка вместо существующей (замена): бумага, сторона и тип берутся у заменяемой ноги, в запросе —
     * количество, срок и цены.
     */
    OrderParams replacement(OrderParams existing, boolean blindPrice) {
        return leg(existing.paperKey(), existing.side(), existing.kind(), parseLots(), parseTif(), blindPrice);
    }

    /** Нога типа {@code kind}: цены берутся из запроса по правилам {@code OrderParams} контракта. */
    private OrderParams leg(
            String paperKey, TradeSide tradeSide, OrderKind kind, long lotCount, Tif timeInForce, boolean blind) {
        BigDecimal limit = null;
        BigDecimal stop = null;
        BigDecimal trailing = null;
        switch (kind) {
            case MARKET -> {}
            case LIMIT -> limit = required(limitPrice, "limitPrice", PRICE);
            case STOP -> stop = required(stopPrice, "stopPrice", PRICE);
            case STOP_LIMIT -> {
                stop = required(stopPrice, "stopPrice", PRICE);
                limit = required(limitPrice, "limitPrice", PRICE);
            }
            // уровень тейка в ядре — limit_price тейк-профит заявки
            case TAKE_PROFIT -> limit = required(takeProfitPrice, "takeProfitPrice", PRICE);
            case TRAILING_STOP -> trailing = required(trailingPct, "trailingPct", PCT);
        }
        return new OrderParams(paperKey, tradeSide, kind, lotCount, limit, stop, trailing, timeInForce, blind);
    }

    private static BigDecimal required(@Nullable String value, String field, Pattern format) {
        if (value == null) {
            throw new FormRejection(RejectCode.INVALID_PARAMS, field);
        }
        if (!format.matcher(value).matches()) {
            throw new InvalidRequestException(field + " has an invalid format");
        }
        return new BigDecimal(value);
    }

    private PaperRecord paper(SessionRecord session) {
        if (session.blind()) {
            if (paperIndex == null) {
                throw new InvalidRequestException("paperIndex is required in a blind session");
            }
            return session.papers().stream()
                    .filter(p -> p.paperIndex() == paperIndex)
                    .findFirst()
                    .orElseThrow(() -> new FormRejection(RejectCode.INVALID_PARAMS, "paperIndex"));
        }
        if (paperId == null) {
            throw new InvalidRequestException("paperId is required in an open session");
        }
        return session.papers().stream()
                .filter(p -> p.paperId().equals(paperId))
                .findFirst()
                .orElseThrow(() -> new FormRejection(RejectCode.INVALID_PARAMS, "paperId"));
    }

    private TradeSide parseSide() {
        return switch (Objects.requireNonNullElse(side, "")) {
            case "buy" -> TradeSide.BUY;
            case "sell" -> TradeSide.SELL;
            default -> throw new InvalidRequestException("side is invalid");
        };
    }

    private OrderKind kind() {
        return switch (Objects.requireNonNullElse(type, "")) {
            case "market" -> OrderKind.MARKET;
            case "limit" -> OrderKind.LIMIT;
            case "stop" -> OrderKind.STOP;
            case "stop_limit" -> OrderKind.STOP_LIMIT;
            case "take_profit" -> OrderKind.TAKE_PROFIT;
            case "trailing" -> OrderKind.TRAILING_STOP;
            default -> throw new InvalidRequestException("type is invalid");
        };
    }

    private Tif parseTif() {
        return switch (Objects.requireNonNullElse(tif, "")) {
            case "day" -> Tif.DAY;
            case "gtc" -> Tif.GTC;
            default -> throw new InvalidRequestException("tif is invalid");
        };
    }

    private long parseLots() {
        if (lots == null || lots < 1) {
            throw new InvalidRequestException("lots must be a positive integer");
        }
        return lots;
    }

    /** Отпечаток запроса для идемпотентности: одинаковые тела дают одинаковый отпечаток. */
    String fingerprint(String operation) {
        String canonical = String.join(
                "|",
                operation,
                String.valueOf(seenStep),
                String.valueOf(paperIndex),
                String.valueOf(paperId),
                String.valueOf(side),
                String.valueOf(type),
                String.valueOf(lots),
                String.valueOf(tif),
                decimalText(limitPrice),
                decimalText(stopPrice),
                decimalText(takeProfitPrice),
                decimalText(trailingPct));
        return sha256(canonical);
    }

    /** Отпечаток отмены: тело пустое, значим только {@code seenStep}. */
    static String cancelFingerprint(UUID orderId, long seenStep) {
        return sha256("cancel|" + orderId + "|" + seenStep);
    }

    // «100» и «100.00» — одна цена
    private static String decimalText(@Nullable String value) {
        if (value == null) {
            return "null";
        }
        try {
            BigDecimal number = new BigDecimal(value).stripTrailingZeros();
            return number.signum() == 0 ? "0" : number.toPlainString();
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }
}
