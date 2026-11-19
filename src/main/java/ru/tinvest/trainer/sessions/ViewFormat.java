package ru.tinvest.trainer.sessions;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import ru.tinvest.trainer.worker.WorkerTypes.OrderKind;
import ru.tinvest.trainer.worker.WorkerTypes.OrderPhase;
import ru.tinvest.trainer.worker.WorkerTypes.OrderState;
import ru.tinvest.trainer.worker.WorkerTypes.RejectCode;
import ru.tinvest.trainer.worker.WorkerTypes.RunState;
import ru.tinvest.trainer.worker.WorkerTypes.TradeSide;

/** Общие правила представления ответов арены (форматы чисел и перечислений контракта) для слепого и раскрытого вида. */
public final class ViewFormat {

    private ViewFormat() {}

    /** Цена и процент ({@code Price}, {@code Pct}): десятичная строка без экспоненты, не меньше двух знаков. */
    public static String number(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.setScale(Math.max(2, stripped.scale())).toPlainString();
    }

    public static @Nullable String numberOrNull(@Nullable BigDecimal value) {
        return value == null ? null : number(value);
    }

    /**
     * Рубли ({@code Money}): два знака. Суммы ядра уже округлены до копейки; лишний знак — ошибка ядра
     * ({@link ArithmeticException}), backend не округляет (INV-6, «Кто считает»).
     */
    public static String money(BigDecimal value) {
        return value.setScale(2).toPlainString();
    }

    /** Время бара: RFC 3339, секунды всегда. */
    public static String instant(long unixMs) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                Instant.ofEpochMilli(unixMs).atOffset(ZoneOffset.UTC));
    }

    /** Подпись бумаги по индексу: «Бумага A». */
    public static String label(int index) {
        return "Бумага " + (char) ('A' + index);
    }

    public static String side(TradeSide side) {
        return side == TradeSide.BUY ? "buy" : "sell";
    }

    public static String legType(OrderKind kind) {
        return switch (kind) {
            case MARKET -> "market";
            case LIMIT -> "limit";
            case STOP -> "stop";
            case STOP_LIMIT -> "stop_limit";
            case TAKE_PROFIT -> "take_profit";
            case TRAILING_STOP -> "trailing";
        };
    }

    static String status(OrderPhase phase) {
        return switch (phase) {
            case ACTIVE -> "active";
            case PARTIALLY_FILLED -> "partial";
            case FILLED -> "filled";
            case CANCELLED -> "cancelled";
            case EXPIRED -> "expired";
            case REJECTED -> "rejected";
        };
    }

    /** Причина заявки в ответах ({@code OrderReason}) — те же имена, что у {@code Reason} ядра. */
    static @Nullable String orderReason(@Nullable RejectCode code) {
        return code == null ? null : code.name();
    }

    /**
     * Причина отказа команды ({@code RejectReason}): пять причин контракта. Остальные причины ядра (снятие по ликвидности,
     * замена, конец срока) к отказу подачи, отмены и замены не относятся; на случай появления — {@code INVALID_PARAMS}.
     */
    static String rejectReason(RejectCode code) {
        return switch (code) {
            case INVALID_PARAMS, INSUFFICIENT_CASH, INSUFFICIENT_POSITION, SESSION_NOT_ACTIVE, ORDER_NOT_ACTIVE ->
                code.name();
            default -> RejectCode.INVALID_PARAMS.name();
        };
    }

    /**
     * Уровни ноги в полях контракта: {@code [limitPrice, stopPrice, takeProfitPrice]}. В ядре уровень тейк-профита —
     * {@code limit_price} заявки этого типа.
     */
    static BigDecimal[] levels(OrderKind kind, @Nullable BigDecimal limit, @Nullable BigDecimal stop) {
        return switch (kind) {
            case LIMIT -> new BigDecimal[] {limit, null, null};
            case STOP -> new BigDecimal[] {null, stop, null};
            case STOP_LIMIT -> new BigDecimal[] {limit, stop, null};
            case TAKE_PROFIT -> new BigDecimal[] {null, null, limit};
            case MARKET, TRAILING_STOP -> new BigDecimal[] {null, null, null};
        };
    }

    /**
     * Имя поля ядра в имени поля запроса: уровень тейка — {@code takeProfitPrice}, процент трейлинга —
     * {@code trailingPct}, бумага — {@code paperIndex}/{@code paperId}. {@code null} — поля нет.
     */
    static @Nullable String clientField(@Nullable String coreField, List<OrderKind> kinds, boolean blind) {
        if (coreField == null || coreField.isEmpty()) {
            return null;
        }
        return switch (coreField) {
            case "limitPrice" ->
                kinds.contains(OrderKind.TAKE_PROFIT)
                                && !kinds.contains(OrderKind.LIMIT)
                                && !kinds.contains(OrderKind.STOP_LIMIT)
                        ? "takeProfitPrice"
                        : "limitPrice";
            case "trailingPercent" -> "trailingPct";
            case "instrument" -> blind ? "paperIndex" : "paperId";
            default -> coreField;
        };
    }

    /** Общие поля заявки ({@code OrderBase}); цены уже в шкале режима. */
    static Map<String, Object> orderBase(
            ArenaState state, OrderState order, BigDecimal[] levels, @Nullable BigDecimal trailingStopLevel) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", state.orderIds().get(order.id()));
        out.put("side", side(order.spec().side()));
        out.put("type", legType(order.spec().kind()));
        out.put("lots", order.spec().lots());
        out.put("filledLots", order.filledLots());
        out.put("tif", order.spec().tif().name().toLowerCase(Locale.ROOT));
        out.put("status", status(order.phase()));
        out.put("step", order.placedStep());
        out.put("seenStep", state.seenSteps().getOrDefault(ArenaState.seqOf(order.id()), order.placedStep()));
        out.put("closedStep", order.closedStep() >= 0 ? order.closedStep() : null);
        out.put("ocoSiblingId", order.ocoSiblingId() >= 0 ? state.orderIds().get(order.ocoSiblingId()) : null);
        out.put("limitPrice", numberOrNull(levels[0]));
        out.put("stopPrice", numberOrNull(levels[1]));
        out.put("takeProfitPrice", numberOrNull(levels[2]));
        out.put("trailingPct", numberOrNull(order.spec().trailingPercent()));
        out.put("trailingStopLevel", numberOrNull(trailingStopLevel));
        out.put("reason", orderReason(order.reason()));
        out.put(
                "rejectField",
                clientField(
                        order.invalidField(),
                        List.of(order.spec().kind()),
                        state.session().blind()));
        return out;
    }

    /** Общие поля снимка ({@code SnapshotBase}); {@code playing} и {@code speed} — состояние плеера сессии. */
    static Map<String, Object> snapshotBase(ArenaState state, int day) {
        SessionRecord session = state.session();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", session.id());
        out.put("regime", session.regime().code());
        out.put("step", state.step());
        out.put("totalSteps", state.replay().totalSteps());
        out.put("warmupSteps", state.replay().warmupSteps());
        out.put("day", day);
        out.put("totalDays", session.tradingDays());
        out.put("finished", isFinished(state));
        out.put("playing", state.player().playing() && !isFinished(state));
        out.put("speed", state.player().speed());
        return out;
    }

    /** Общие поля результата шага ({@code StepResultBase}). */
    static Map<String, Object> stepBase(ArenaState state, int day) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("step", state.step());
        out.put("day", day);
        out.put("finished", isFinished(state));
        return out;
    }

    /** Заявки, закрытые после шага {@code fromStep} (исключительно) и не дальше курсора: исполнены, отменены, истекли. */
    static List<OrderState> changedOrders(ArenaState state, long fromStep) {
        return state.replay().orders().stream()
                .filter(o -> o.closedStep() > fromStep && o.closedStep() <= state.step())
                .toList();
    }

    static boolean isFinished(ArenaState state) {
        return !"running".equals(state.session().status()) || state.replay().state() != RunState.RUNNING;
    }

    static List<String> idStrings(List<UUID> ids) {
        return ids.stream().map(UUID::toString).toList();
    }

    /**
     * Торговый день и номер свечи в дне по шагу общей шкалы сессии: свечи дня нумеруются с 1 по различным шагам. Шаги
     * прогрева отрицательные, у них день {@code <= 0}.
     */
    public static final class StepIndex {

        private final TreeMap<Long, int[]> byStep = new TreeMap<>();

        private final TreeMap<Long, Integer> days = new TreeMap<>();

        /** Индекс по готовым позициям шагов: {@code шаг -> [день, свеча в дне]} (шаги пачки плеера). */
        static StepIndex of(Map<Long, int[]> positions) {
            StepIndex index = new StepIndex();
            index.byStep.putAll(positions);
            return index;
        }

        /** Позиции шагов {@code шаг -> [день, свеча в дне]} для {@link #of}. */
        Map<Long, int[]> positions() {
            return java.util.Collections.unmodifiableMap(byStep);
        }

        /** Добавляет шаг с бара; порядок добавления не важен, нумерацию считает {@link #build()}. */
        public void add(long step, int tradingDay) {
            days.put(step, tradingDay);
        }

        public StepIndex build() {
            Map<Integer, Integer> counters = new HashMap<>();
            for (Map.Entry<Long, Integer> e : days.entrySet()) {
                int candle = counters.merge(e.getValue(), 1, Integer::sum);
                byStep.put(e.getKey(), new int[] {e.getValue(), candle});
            }
            return this;
        }

        /** {@code [день, свеча в дне]} шага или ближайшего предыдущего; нет баров — {@code [0, 0]}. */
        public int[] at(long step) {
            Map.Entry<Long, int[]> entry = byStep.floorEntry(step);
            return entry == null ? new int[] {0, 0} : entry.getValue();
        }
    }
}
