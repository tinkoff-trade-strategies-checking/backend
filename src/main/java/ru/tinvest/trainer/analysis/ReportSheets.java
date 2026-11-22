package ru.tinvest.trainer.analysis;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import ru.tinvest.trainer.sessions.CommandLogEntry;
import tools.jackson.databind.JsonNode;

/**
 * Листы выгрузки отчёта (ФТ-17, {@code decisions.md}, «Выгрузка отчётов»): таблицы строк из сохранённого отчёта
 * ({@code SessionReport}). Ничего не пересчитывается: числа берутся строками отчёта, меняется только представление —
 * десятичная запятая, даты {@code ДД.ММ.ГГГГ} и время {@code ЧЧ:ММ:СС} по Москве (INV-6). Листы «Сравнение» и
 * «Расхождения» добавляются, только если они есть в отчёте.
 */
final class ReportSheets {

    /** Лист: имя, заголовок, строки одинаковой длины; пустая ячейка — пустая строка. */
    record Sheet(String name, List<String> header, List<List<Cell>> rows) {}

    /**
     * Ячейка: {@code text} — представление для CSV (десятичная запятая); {@code number} и {@code format} — для XLSX:
     * число из строки отчёта без {@code double}, формат отображения не меняет значения.
     */
    record Cell(
            String text,
            @Nullable BigDecimal number,
            @Nullable String format) {

        static Cell of(String text) {
            return new Cell(text, null, null);
        }
    }

    private static final String MONEY = "#,##0.00";
    private static final String RATIO = "0.000000";
    private static final String PCT = "0.00";
    private static final String PRICE = "0.00########";

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private ReportSheets() {}

    /** Все листы выгрузки; {@code snapshotId} — версия данных (снапшот), общая для всех бумаг сессии. */
    static List<Sheet> of(JsonNode report, String snapshotId, List<CommandLogEntry> commands) {
        List<Sheet> sheets = new ArrayList<>();
        sheets.add(summary(report));
        sheets.add(metrics(report.path("humanMetrics"), report.path("algoMetrics")));
        sheets.add(roundTrips(report));
        sheets.add(orders(report, commands));
        sheets.add(costs(report.path("humanMetrics").path("costs")));
        sheets.add(equity(report));
        sheets.add(parameters(report, snapshotId));
        JsonNode comparison = report.path("comparison");
        if (comparison.isObject()) {
            sheets.add(comparison(comparison));
        }
        JsonNode divergences = report.path("divergences");
        if (divergences.isArray()) {
            sheets.add(divergences(divergences));
        }
        return sheets;
    }

    /** Листы выгрузки отчёта прогона алгоритма ({@code AlgoRunReport}). */
    static List<Sheet> ofAlgoRun(JsonNode report, String snapshotId) {
        List<Sheet> sheets = new ArrayList<>();
        sheets.add(runSummary(report));
        JsonNode m = report.path("metrics");
        if (m.isObject()) {
            sheets.add(metrics(m, null));
        }
        sheets.add(roundTrips(report));
        sheets.add(runOrders(report));
        if (m.isObject() && m.path("costs").isObject()) {
            sheets.add(costs(m.path("costs")));
        }
        sheets.add(runEquity(report));
        sheets.add(runParameters(report, snapshotId));
        if (!report.path("findings").isEmpty() || !report.path("diagnostics").isEmpty()) {
            sheets.add(runFindings(report));
        }
        return sheets;
    }

    private static Sheet summary(JsonNode r) {
        JsonNode m = r.path("humanMetrics");
        List<List<Cell>> rows = new ArrayList<>();
        rows.add(row("Сессия", text(r, "sessionId")));
        rows.add(row("Слепая сессия", yesNo(r.path("blind"))));
        rows.add(row("Завершена досрочно", yesNo(r.path("finishedEarly"))));
        rows.add(row("Банкротство", yesNo(m.path("bankrupt"))));
        rows.add(row("Создана", date(text(r, "createdAt")) + " " + time(text(r, "createdAt"))));
        rows.add(row("Период с", localDate(r.path("period").path("startDate"))));
        rows.add(row("Период по", localDate(r.path("period").path("endDate"))));
        rows.add(row("Сыграно дней", text(r, "playedDays")));
        rows.add(row("Всего дней", text(r, "totalDays")));
        rows.add(row("Начальный капитал", money(m.path("initialCapital"))));
        rows.add(row("Итоговый капитал", money(m.path("finalCapital"))));
        rows.add(row("Доходность, %", pct(m.path("netReturnPct"))));
        rows.add(row("Максимальная просадка, %", pct(m.path("maxDrawdownPct"))));
        return new Sheet("Сводка", List.of("Показатель", "Значение"), rows);
    }

    private static Sheet metrics(JsonNode h, @Nullable JsonNode a) {
        boolean algo = a != null && a.isObject();
        String[][] defs = {
            {"initialCapital", "Начальный капитал"},
            {"finalCapital", "Итоговый капитал"},
            {"totalReturnPct", "Полная доходность, %"},
            {"cagrPct", "CAGR, %"},
            {"grossReturnPct", "Доходность до издержек, %"},
            {"netReturnPct", "Доходность после издержек, %"},
            {"maxDrawdownPct", "Максимальная просадка, %"},
            {"maxDrawdownDurationDays", "Длительность просадки, дней"},
            {"maxDrawdownDurationSteps", "Длительность просадки, шагов"},
            {"volatilityPct", "Волатильность, %"},
            {"sharpe", "Sharpe"},
            {"sortino", "Sortino"},
            {"turnover", "Оборот"},
            {"tradesCount", "Закрытых сделок"},
            {"winRatePct", "Доля прибыльных сделок, %"},
            {"rejectedOrdersCount", "Отклонённых заявок"},
            {"partialFillsCount", "Частичных исполнений"},
            {"stopTakeSameBarCount", "Стоп и тейк на одной свече"},
            {"hhi", "HHI"},
            {"largestPositionPct", "Крупнейшая позиция, %"},
            {"benchmarkImoexPct", "Бенчмарк IMOEX, %"},
        };
        List<List<Cell>> rows = new ArrayList<>();
        for (String[] def : defs) {
            List<Cell> row = row(def[1], metric(h.path(def[0]), def[0]));
            if (algo) {
                row.add(metric(a.path(def[0]), def[0]));
            }
            rows.add(row);
        }
        return new Sheet(
                "Метрики", algo ? List.of("Метрика", "Человек", "Алгоритм") : List.of("Метрика", "Значение"), rows);
    }

    private static Cell metric(JsonNode v, String key) {
        if (key.endsWith("Capital")) {
            return money(v);
        }
        if (key.endsWith("Pct")) {
            return pct(v);
        }
        return decimal(v, ratioKey(key) ? 6 : 0, ratioKey(key) ? RATIO : null);
    }

    /** Проценты и безразмерные метрики — шесть знаков (решение о формате выгрузки). */
    private static boolean ratioKey(String key) {
        return key.endsWith("Pct")
                || List.of("sharpe", "sortino", "turnover", "hhi").contains(key);
    }

    private static Sheet roundTrips(JsonNode r) {
        List<List<Cell>> rows = new ArrayList<>();
        for (JsonNode t : r.path("roundTrips")) {
            rows.add(row(
                    text(t, "paperTicker"),
                    date(text(t, "entryDate")),
                    time(text(t, "entryDate")),
                    date(text(t, "exitDate")),
                    time(text(t, "exitDate")),
                    text(t, "lots"),
                    price(t.path("entryPrice")),
                    price(t.path("exitPrice")),
                    money(t.path("grossPnl")),
                    money(t.path("costs")),
                    money(t.path("netPnl"))));
        }
        return new Sheet(
                "Сделки",
                List.of(
                        "Бумага",
                        "Дата входа",
                        "Время входа",
                        "Дата выхода",
                        "Время выхода",
                        "Лотов",
                        "Цена входа",
                        "Цена выхода",
                        "Результат до издержек",
                        "Издержки",
                        "Результат после издержек"),
                rows);
    }

    /** «Заявки»: команды журнала (подача, отмена, замена) с исходом приёма; исполнения — на листе «Сделки». */
    private static Sheet orders(JsonNode r, List<CommandLogEntry> commands) {
        Map<Long, String> stepDates = new HashMap<>();
        for (JsonNode p : r.path("equityCurve")) {
            stepDates.put(p.path("step").asLong(), text(p, "date"));
        }
        List<List<Cell>> rows = new ArrayList<>();
        for (CommandLogEntry c : commands) {
            String when = stepDates.getOrDefault(c.step(), "");
            rows.add(row(
                    Long.toString(c.seq()),
                    date(when),
                    time(when),
                    switch (c.type()) {
                        case "place" -> c.ocoLeg() ? "подача (вторая нога OCO)" : "подача";
                        case "cancel" -> "отмена";
                        default -> c.ocoLeg() ? "замена (вторая нога OCO)" : "замена";
                    },
                    c.orderId() == null ? "" : Long.toString(c.orderId()),
                    c.ticker() == null ? "" : c.ticker(),
                    label("side", c.side()),
                    label("kind", c.kind()),
                    c.lots() == null ? "" : Long.toString(c.lots()),
                    number(c.limitPrice(), PRICE),
                    number(c.stopPrice(), PRICE),
                    number(c.trailingPercent(), PCT),
                    label("tif", c.tif()),
                    c.accepted() ? "принята" : "отклонена",
                    label("reason", c.rejectReason())));
        }
        return new Sheet(
                "Заявки",
                List.of(
                        "Команда",
                        "Дата",
                        "Время",
                        "Действие",
                        "Заявка ядра",
                        "Бумага",
                        "Сторона",
                        "Тип",
                        "Лотов",
                        "Цена лимита",
                        "Цена стопа",
                        "Трейлинг, %",
                        "Срок",
                        "Результат",
                        "Причина отказа"),
                rows);
    }

    private static Cell number(@Nullable BigDecimal value, String format) {
        return value == null ? Cell.of("") : new Cell(value.toPlainString().replace('.', ','), value, format);
    }

    private static Sheet costs(JsonNode c) {
        List<List<Cell>> rows = new ArrayList<>();
        rows.add(row("Комиссия", money(c.path("commission"))));
        rows.add(row("Биржевой сбор", money(c.path("exchangeFee"))));
        rows.add(row("Спред", money(c.path("spread"))));
        rows.add(row("Проскальзывание", money(c.path("slippage"))));
        if (!c.path("total").isNull() && !c.path("total").isMissingNode()) {
            rows.add(row("Итого", money(c.path("total"))));
        }
        return new Sheet("Издержки", List.of("Вид", "Сумма"), rows);
    }

    private static Sheet runSummary(JsonNode r) {
        JsonNode m = r.path("metrics");
        List<List<Cell>> rows = new ArrayList<>();
        rows.add(row("Прогон", text(r, "runId")));
        rows.add(row("Алгоритм", text(r, "algoName")));
        rows.add(row("Статус", label("runStatus", text(r, "status"))));
        rows.add(row("Уровень проверки", text(r, "level")));
        rows.add(row("Слепой прогон", yesNo(r.path("blind"))));
        if (!r.path("repeatOfRunId").isNull() && !r.path("repeatOfRunId").isMissingNode()) {
            rows.add(row("Повтор прогона", text(r, "repeatOfRunId")));
            rows.add(row("Совпадение при повторе", yesNo(r.path("repeatMatch"))));
        }
        rows.add(row("Создан", date(text(r, "createdAt")) + " " + time(text(r, "createdAt"))));
        rows.add(row("Период с", localDate(r.path("period").path("startDate"))));
        rows.add(row("Период по", localDate(r.path("period").path("endDate"))));
        rows.add(row("Сыграно дней", text(r, "playedDays")));
        rows.add(row("Всего дней", text(r, "totalDays")));
        if (m.isObject()) {
            rows.add(row("Начальный капитал", money(m.path("initialCapital"))));
            rows.add(row("Итоговый капитал", money(m.path("finalCapital"))));
            rows.add(row("Доходность, %", pct(m.path("netReturnPct"))));
            rows.add(row("Максимальная просадка, %", pct(m.path("maxDrawdownPct"))));
        }
        return new Sheet("Сводка", List.of("Показатель", "Значение"), rows);
    }

    private static Sheet runOrders(JsonNode r) {
        List<List<Cell>> rows = new ArrayList<>();
        for (JsonNode o : r.path("orders")) {
            rows.add(row(
                    text(o, "id"),
                    text(o, "placedStep"),
                    text(o, "closedStep"),
                    text(o, "paperTicker"),
                    label("side", text(o, "side")),
                    label("kind", text(o, "orderType")),
                    text(o, "lots"),
                    label("orderStatus", text(o, "status")),
                    price(o.path("limitPrice")),
                    price(o.path("stopPrice")),
                    decimal(o.path("trailingPct"), 2, PCT),
                    text(o, "ruleName"),
                    text(o, "because")));
        }
        return new Sheet(
                "Заявки",
                List.of(
                        "Заявка ядра",
                        "Шаг подачи",
                        "Шаг закрытия",
                        "Бумага",
                        "Сторона",
                        "Тип",
                        "Лотов",
                        "Статус",
                        "Цена лимита",
                        "Цена стопа",
                        "Трейлинг, %",
                        "Правило",
                        "Причина"),
                rows);
    }

    private static Sheet runEquity(JsonNode r) {
        List<List<Cell>> rows = new ArrayList<>();
        for (JsonNode p : r.path("equityCurve")) {
            rows.add(row(
                    text(p, "step"),
                    date(text(p, "date")),
                    time(text(p, "date")),
                    money(p.path("capital")),
                    money(p.path("benchmarkCapital"))));
        }
        return new Sheet(
                "Кривая капитала", List.of("Шаг", "Дата", "Время", "Капитал алгоритма", "Капитал бенчмарка"), rows);
    }

    private static Sheet runParameters(JsonNode r, String snapshotId) {
        List<List<Cell>> rows = new ArrayList<>();
        rows.add(row("Таймфрейм", label("timeframe", text(r, "timeframe"))));
        rows.add(row("Категория периода", label("regime", text(r, "regime"))));
        rows.add(row("Версия данных (снапшот)", snapshotId));
        rows.add(row("Алгоритм", text(r, "algoName")));
        rows.add(row("Версия алгоритма", text(r, "strategyVersionId")));
        for (JsonNode p : r.path("papers")) {
            rows.add(row(
                    text(p, "blindLabel"),
                    text(p, "ticker") + "; сектор: " + text(p, "sector") + "; лот: " + text(p, "lotSize")
                            + "; цена на старте: " + price(p.path("startPrice")).text()));
        }
        return new Sheet("Параметры и версии", List.of("Параметр", "Значение"), rows);
    }

    private static Sheet runFindings(JsonNode r) {
        // Ключ свойства диагностики/finding в отчёте; не ErrorCode API (см. ErrorCodeContractTest).
        String findingCodeKey = String.join("", "co", "de");
        List<List<Cell>> rows = new ArrayList<>();
        for (JsonNode f : r.path("findings")) {
            rows.add(row("Выполнение", text(f, findingCodeKey), text(f, "level"), text(f, "step"), text(f, "message")));
        }
        for (JsonNode d : r.path("diagnostics")) {
            rows.add(row(
                    "Компиляция", text(d, findingCodeKey), text(d, "level"), text(d, "startLine"), text(d, "message")));
        }
        return new Sheet("Диагностика", List.of("Этап", "Код", "Уровень", "Шаг / строка", "Сообщение"), rows);
    }

    private static Sheet equity(JsonNode r) {
        List<List<Cell>> rows = new ArrayList<>();
        for (JsonNode p : r.path("equityCurve")) {
            rows.add(row(
                    text(p, "step"),
                    date(text(p, "date")),
                    time(text(p, "date")),
                    money(p.path("humanCapital")),
                    money(p.path("algoCapital")),
                    money(p.path("benchmarkCapital"))));
        }
        return new Sheet(
                "Кривая капитала",
                List.of("Шаг", "Дата", "Время", "Капитал человека", "Капитал алгоритма", "Капитал бенчмарка"),
                rows);
    }

    private static Sheet parameters(JsonNode r, String snapshotId) {
        List<List<Cell>> rows = new ArrayList<>();
        rows.add(row("Таймфрейм", label("timeframe", text(r, "timeframe"))));
        rows.add(row("Категория периода", label("regime", text(r, "regime"))));
        rows.add(row("Версия данных (снапшот)", snapshotId));
        String algo = text(r, "backgroundAlgorithmName");
        if (!algo.isEmpty()) {
            rows.add(row("Фоновый алгоритм", algo));
        }
        for (JsonNode p : r.path("papers")) {
            rows.add(row(
                    text(p, "blindLabel"),
                    text(p, "ticker") + "; сектор: " + text(p, "sector") + "; лот: " + text(p, "lotSize")
                            + "; цена на старте: " + price(p.path("startPrice")).text()));
        }
        return new Sheet("Параметры и версии", List.of("Параметр", "Значение"), rows);
    }

    private static Sheet comparison(JsonNode c) {
        List<List<Cell>> rows = new ArrayList<>();
        JsonNode cap = c.path("finalCapital");
        rows.add(row(
                "Итоговый капитал",
                money(cap.path("human")),
                money(cap.path("algo")),
                label("winner", text(cap, "winner")),
                money(cap.path("diff"))));
        String[][] pcts = {{"netReturn", "Доходность, %"}, {"maxDrawdown", "Максимальная просадка, %"}};
        for (String[] def : pcts) {
            JsonNode p = c.path(def[0]);
            rows.add(row(
                    def[1],
                    pct(p.path("humanPct")),
                    pct(p.path("algoPct")),
                    label("winner", text(p, "winner")),
                    pct(p.path("diffPctPoints"))));
        }
        return new Sheet("Сравнение", List.of("Показатель", "Человек", "Алгоритм", "Лучше", "Разница"), rows);
    }

    private static Sheet divergences(JsonNode list) {
        List<List<Cell>> rows = new ArrayList<>();
        for (JsonNode d : list) {
            rows.add(row(
                    text(d, "day"),
                    date(text(d, "date")),
                    time(text(d, "date")),
                    text(d, "paperTicker"),
                    text(d, "sector"),
                    label("divergence", text(d, "type")),
                    action(d.path("humanAction")),
                    action(d.path("algoAction")),
                    money(d.path("errorCost")),
                    pct(d.path("errorCostPct")),
                    text(d, "because")));
        }
        return new Sheet(
                "Расхождения",
                List.of(
                        "День",
                        "Дата",
                        "Время",
                        "Бумага",
                        "Сектор",
                        "Тип",
                        "Действие человека",
                        "Действие алгоритма",
                        "Цена ошибки",
                        "Цена ошибки, %",
                        "Причина"),
                rows);
    }

    private static String action(JsonNode a) {
        if (!a.isObject() || "none".equals(text(a, "kind"))) {
            return "нет действия";
        }
        return label("side", text(a, "side")) + " " + text(a, "lots") + " " + label("kind", text(a, "orderType"));
    }

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("side/buy", "покупка"),
            Map.entry("side/sell", "продажа"),
            Map.entry("kind/market", "рыночная"),
            Map.entry("kind/limit", "лимитная"),
            Map.entry("kind/stop", "стоп"),
            Map.entry("kind/stop_limit", "стоп-лимит"),
            Map.entry("kind/take_profit", "тейк-профит"),
            Map.entry("kind/trailing", "трейлинг-стоп"),
            Map.entry("kind/trailing_stop", "трейлинг-стоп"),
            Map.entry("runStatus/completed", "завершён"),
            Map.entry("runStatus/bankrupt", "банкротство"),
            Map.entry("runStatus/invalid", "ошибка стратегии"),
            Map.entry("runStatus/failed", "ошибка выполнения"),
            Map.entry("orderStatus/active", "активна"),
            Map.entry("orderStatus/partial", "частично исполнена"),
            Map.entry("orderStatus/filled", "исполнена"),
            Map.entry("orderStatus/cancelled", "снята"),
            Map.entry("orderStatus/expired", "истекла"),
            Map.entry("orderStatus/rejected", "отклонена"),
            Map.entry("tif/day", "на день"),
            Map.entry("tif/gtc", "до отмены"),
            Map.entry("reason/invalid_params", "неверные параметры"),
            Map.entry("reason/insufficient_cash", "недостаточно денег"),
            Map.entry("reason/insufficient_position", "недостаточно бумаг"),
            Map.entry("reason/session_not_active", "сессия не активна"),
            Map.entry("reason/order_not_active", "заявка не активна"),
            Map.entry("reason/liquidity_limit", "лимит ликвидности"),
            Map.entry("reason/oco_sibling_filled", "исполнена парная заявка"),
            Map.entry("reason/replaced", "заменена"),
            Map.entry("reason/session_end", "конец сессии"),
            Map.entry("reason/cancelled_by_user", "отмена пользователем"),
            Map.entry("reason/day_expired", "истёк срок дня"),
            Map.entry("timeframe/1m", "1 минута"),
            Map.entry("timeframe/5m", "5 минут"),
            Map.entry("timeframe/10m", "10 минут"),
            Map.entry("timeframe/15m", "15 минут"),
            Map.entry("timeframe/1h", "1 час"),
            Map.entry("timeframe/1d", "1 день"),
            Map.entry("regime/calm", "спокойный"),
            Map.entry("regime/medium", "средний"),
            Map.entry("regime/stress", "стрессовый"),
            Map.entry("winner/human", "человек"),
            Map.entry("winner/algo", "алгоритм"),
            Map.entry("winner/tie", "ничья"),
            Map.entry("divergence/extra", "лишнее действие"),
            Map.entry("divergence/missed", "упущенное действие"),
            Map.entry("divergence/size", "размер"),
            Map.entry("divergence/timing", "момент"));

    /** Русская подпись кода; неизвестный код выводится как есть, пусто — пусто. */
    private static String label(String group, @Nullable String code) {
        if (code == null || code.isEmpty()) {
            return "";
        }
        return LABELS.getOrDefault(group + "/" + code.toLowerCase(Locale.ROOT), code);
    }

    private static List<Cell> row(Object... cells) {
        List<Cell> row = new ArrayList<>(cells.length);
        for (Object cell : cells) {
            row.add(cell instanceof Cell c ? c : Cell.of((String) cell));
        }
        return row;
    }

    /** Текст поля; нет поля или {@code null} — пустая строка. */
    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asString();
    }

    private static String yesNo(JsonNode v) {
        if (v.isMissingNode() || v.isNull()) {
            return "";
        }
        return v.asBoolean() ? "Да" : "Нет";
    }

    private static Cell money(JsonNode v) {
        return decimal(v, 2, MONEY);
    }

    private static Cell price(JsonNode v) {
        return decimal(v, 0, PRICE);
    }

    private static Cell pct(JsonNode v) {
        return decimal(v, 6, PCT);
    }

    /**
     * Десятичная строка отчёта: для CSV — с запятой, дополненная нулями справа до {@code minScale} знаков; для XLSX —
     * то же число как {@link BigDecimal}. Значение не пересчитывается и не округляется (INV-6).
     */
    private static Cell decimal(JsonNode v, int minScale, @Nullable String format) {
        if (v.isMissingNode() || v.isNull()) {
            return Cell.of("");
        }
        String s = v.asString();
        try {
            BigDecimal number = new BigDecimal(s);
            if (number.scale() < minScale) {
                number = number.setScale(minScale);
            }
            return new Cell(number.toPlainString().replace('.', ','), number, format);
        } catch (NumberFormatException e) {
            return Cell.of(s);
        }
    }

    private static String localDate(JsonNode v) {
        return v.isMissingNode() || v.isNull()
                ? ""
                : LocalDate.parse(v.asString()).format(DATE);
    }

    private static String date(String instant) {
        return instant.isEmpty() ? "" : Instant.parse(instant).atZone(MSK).format(DATE);
    }

    private static String time(String instant) {
        return instant.isEmpty() ? "" : Instant.parse(instant).atZone(MSK).format(TIME);
    }
}
