package ru.tinvest.trainer.sessions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import ru.tinvest.trainer.api.ErrorCode;
import ru.tinvest.trainer.marketdata.LaunchRejectReason;
import ru.tinvest.trainer.marketdata.LaunchRejection;
import ru.tinvest.trainer.marketdata.SecurityFundamental;
import ru.tinvest.trainer.worker.WorkerTypes.BlindBarData;
import ru.tinvest.trainer.worker.WorkerTypes.BlindFillData;
import ru.tinvest.trainer.worker.WorkerTypes.BlindOrderData;
import ru.tinvest.trainer.worker.WorkerTypes.BlindPaperData;
import ru.tinvest.trainer.worker.WorkerTypes.BlindPositionData;
import ru.tinvest.trainer.worker.WorkerTypes.BlindViewData;
import ru.tinvest.trainer.worker.WorkerTypes.OrderState;
import ru.tinvest.trainer.worker.WorkerTypes.StepBars;

/**
 * Единственная точка маскирования ответов сессии (INV-1, INV-7; {@code architecture.md}). Всё, что уходит клиенту о
 * запуске и об отказе, строится здесь: слепая сессия не получает тикеров, названий, дат, рублёвых сумм, σ, MDD и
 * порогов; об отказе — только машинный код, поле и числа лимита, текст {@code detail} — константа без данных. Данные
 * бумаг и периода сессии вне этого класса наружу не передаются. Следующие операции сессии (события, отчёт)
 * добавляют сюда свои виды ответа, а не маскируют сами.
 *
 * <p>Снимок арены, результат команды заявки и оценка заявки выбирают вид по режиму сессии здесь: слепой строится из
 * {@code blind_view} воркера (индексы бумаг, проценты капитала, шаги вместо времени), из рублёвого состояния берёт
 * только то, что не раскрывает цену: сторону, тип, количество, статус, шаги, причину. Раскрытый вид — {@link OpenViews}.
 */
@Component
class BlindMasking {

    /** Ответ запуска ({@code CreatedSession}): только идентификатор, одинаково для слепой и неслепой сессии. */
    Map<String, Object> createdSession(UUID sessionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        return body;
    }

    /** Тело 422 ({@code LaunchProblem}); {@code detail} и {@code instance} данных запроса не содержат. */
    Map<String, Object> launchProblem(LaunchRejection rejection) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", "Launch rejected");
        body.put("status", 422);
        body.put("detail", detail(rejection.reason()));
        body.put("instance", null);
        body.put("code", ErrorCode.valueOf(rejection.reason().name()).name());
        body.put("field", rejection.field());
        body.put("barsCount", rejection.barsCount());
        body.put("maxBars", rejection.maxBars());
        return body;
    }

    // ---------- арена ----------

    /** Снимок сессии на курсоре ({@code Snapshot}). */
    Map<String, Object> snapshot(ArenaState state) {
        if (state.session().revealed()) {
            return OpenViews.snapshot(state);
        }
        BlindViewData view = blindView(state);
        ViewFormat.StepIndex index = new ViewFormat.StepIndex();
        view.history().forEach(b -> index.add(b.step(), b.tradingDay()));
        index.build();

        Map<Integer, List<Object>> candles = new HashMap<>();
        Map<Integer, BigDecimal> lastClose = new HashMap<>();
        for (BlindBarData bar : view.history()) {
            candles.computeIfAbsent(bar.index(), k -> new ArrayList<>()).add(blindCandle(bar, index.at(bar.step())[1]));
            lastClose.put(bar.index(), bar.close());
        }

        // бумага без бара до курсора в слепом виде отсутствует целиком
        List<Object> papers = new ArrayList<>();
        for (BlindPaperData paper : view.papers()) {
            if (paper.lotValueAtStart() == null || !lastClose.containsKey(paper.index())) {
                continue;
            }
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("changePct", ViewFormat.numberOrNull(paper.changePeriodPct()));
            stat.put("lastPrice", ViewFormat.number(lastClose.get(paper.index())));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("paperIndex", paper.index());
            out.put("label", ViewFormat.label(paper.index()));
            out.put("sector", state.session().papers().get(paper.index()).sector());
            out.put("lotValueAtStart", ViewFormat.number(paper.lotValueAtStart()));
            out.put("candles", candles.get(paper.index()));
            out.put("stat", stat);
            papers.add(out);
        }

        List<Object> fills = new ArrayList<>();
        for (BlindFillData fill : view.fills()) {
            fills.add(blindFill(state, fill, index));
        }
        List<Object> orders = new ArrayList<>();
        for (OrderState order : state.replay().orders()) {
            orders.add(blindOrder(state, view, order));
        }

        Map<String, Object> out = ViewFormat.snapshotBase(state, index.at(state.step())[0]);
        out.put("mode", "BLIND");
        out.put("papers", papers);
        out.put("portfolio", blindPortfolio(view));
        out.put("fills", fills);
        out.put("orders", orders);
        return out;
    }

    /** Результат подачи, отмены или замены заявки ({@code OrderCommandResult}). */
    Map<String, Object> commandResult(ArenaState state, ArenaState.CommandReply reply) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accepted", reply.accepted());
        out.put("reason", reply.reason() == null ? null : ViewFormat.rejectReason(reply.reason()));
        out.put("field", reply.field());
        out.put(
                "orderIds",
                reply.orderIds().stream().map(id -> state.orderIds().get(id)).toList());
        if (!state.session().blind()) {
            return OpenViews.commandResult(out, state, reply);
        }
        BlindViewData view = blindView(state);
        List<Object> shown = new ArrayList<>();
        for (long id : reply.shownIds()) {
            state.replay().orders().stream()
                    .filter(o -> o.id() == id)
                    .findFirst()
                    .ifPresent(o -> shown.add(blindOrder(state, view, o)));
        }
        out.put("mode", "BLIND");
        out.put("order", shown.isEmpty() ? null : shown.get(0));
        out.put("orders", shown);
        out.put("portfolio", blindPortfolio(view));
        return out;
    }

    /** Оценка заявки ({@code OrderEstimate}): слепая — только проценты капитала. */
    Map<String, Object> estimate(SessionRecord session, ArenaState.EstimateView view) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("canPlace", view.canPlace());
        out.put("reason", view.reason() == null ? null : ViewFormat.rejectReason(view.reason()));
        out.put("field", view.field());
        out.put("amountPct", ViewFormat.number(view.amounts().amountPct()));
        out.put("commissionPct", ViewFormat.number(view.amounts().commissionPct()));
        out.put("exchangeFeePct", ViewFormat.number(view.amounts().exchangeFeePct()));
        out.put("requiredCashPct", ViewFormat.number(view.amounts().requiredCashPct()));
        List<Object> legs = new ArrayList<>();
        for (ArenaState.EstimateLeg leg : view.legs()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", ViewFormat.legType(leg.kind()));
            item.put("canPlace", leg.canPlace());
            item.put("reason", leg.reason() == null ? null : ViewFormat.rejectReason(leg.reason()));
            item.put("field", leg.field());
            item.put("amountPct", ViewFormat.number(leg.amounts().amountPct()));
            item.put("commissionPct", ViewFormat.number(leg.amounts().commissionPct()));
            item.put("exchangeFeePct", ViewFormat.number(leg.amounts().exchangeFeePct()));
            item.put("requiredCashPct", ViewFormat.number(leg.amounts().requiredCashPct()));
            if (!session.blind()) {
                OpenViews.rubles(item, leg.amounts());
            }
            legs.add(item);
        }
        out.put("legs", legs);
        if (!session.blind()) {
            return OpenViews.estimate(out, view.amounts());
        }
        out.put("mode", "BLIND");
        return out;
    }

    // ---------- плеер и события ----------

    /** Результат шага ({@code StepResult}) по режиму сессии; свечи пачки — из событий шагов воркера. */
    Map<String, Object> stepResult(
            ArenaState state, long fromStep, StepBars steps, Map<Long, int[]> positions, int day) {
        return state.session().blind()
                ? stepResult(state, fromStep, steps.blindBars(), positions, day)
                : OpenViews.stepResult(state, fromStep, steps.bars(), positions, day);
    }

    /**
     * Результат шага ({@code StepResult}) на курсоре: свечи всех шагов пачки {@code (fromStep; курсор]} по бумагам
     * (массив на бумагу, шаги по возрастанию), исполнения и заявки, изменившиеся за пачку, портфель на курсоре. Статистика
     * ({@code paperStats}) — по бумагам, у которых в пачке есть свеча. {@code positions} — день и номер свечи в дне шагов
     * пачки, {@code day} — день курсора.
     */
    Map<String, Object> stepResult(
            ArenaState state, long fromStep, List<BlindBarData> bars, Map<Long, int[]> positions, int day) {
        if (!state.session().blind()) {
            throw new IllegalStateException("stepResult слепого вида вызван для раскрытой сессии");
        }
        BlindViewData view = blindView(state);
        ViewFormat.StepIndex index = ViewFormat.StepIndex.of(positions);
        long step = state.step();

        Map<String, List<Object>> candles = new LinkedHashMap<>();
        Map<Integer, BigDecimal> lastClose = new HashMap<>();
        for (BlindBarData bar : bars.stream()
                .filter(b -> b.step() > fromStep && b.step() <= step)
                .sorted(java.util.Comparator.comparingLong(BlindBarData::step))
                .toList()) {
            lastClose.put(bar.index(), bar.close());
            candles.computeIfAbsent(Integer.toString(bar.index()), k -> new ArrayList<>())
                    .add(blindCandle(bar, index.at(bar.step())[1]));
        }
        Map<String, Object> paperStats = new LinkedHashMap<>();
        for (BlindPaperData paper : view.papers()) {
            if (paper.lotValueAtStart() == null || !lastClose.containsKey(paper.index())) {
                continue;
            }
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("changePct", ViewFormat.numberOrNull(paper.changePeriodPct()));
            stat.put("lastPrice", ViewFormat.number(lastClose.get(paper.index())));
            paperStats.put(Integer.toString(paper.index()), stat);
        }
        List<Object> fills = new ArrayList<>();
        for (BlindFillData fill : view.fills()) {
            if (fill.step() > fromStep && fill.step() <= step) {
                fills.add(blindFill(state, fill, index));
            }
        }
        List<Object> orders = new ArrayList<>();
        for (OrderState order : ViewFormat.changedOrders(state, fromStep)) {
            orders.add(blindOrder(state, view, order));
        }
        Map<String, Object> out = ViewFormat.stepBase(state, day);
        out.put("mode", "BLIND");
        out.put("bars", candles);
        out.put("paperStats", paperStats);
        out.put("portfolio", blindPortfolio(view));
        out.put("fills", fills);
        out.put("orders", orders);
        return out;
    }

    /**
     * Состояние плеера ({@code PlayerState}); {@code stepResult} — только у действия {@code step}. Строится из строки
     * сессии и состояния плеера, воркер не нужен.
     */
    Map<String, Object> playerState(
            SessionRecord session, PlayerStates.Row player, int day, @Nullable Map<String, Object> stepResult) {
        boolean finished = !"running".equals(session.status());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", session.id());
        out.put("playing", player.playing() && !finished);
        out.put("speed", player.speed());
        out.put("step", session.currentStep());
        out.put("day", day);
        out.put("finished", finished);
        out.put("mode", session.blind() ? "BLIND" : "OPEN");
        out.put("stepResult", stepResult);
        return out;
    }

    /**
     * Итог сессии ({@code FinishResult}): причина, шаг и день завершения, портфель и заявки. Слепая сессия и после
     * завершения отвечает слепым видом: раскрытие — в отчёте и воспроизведении.
     */
    Map<String, Object> finishResult(ArenaState state, String endReason, int day) {
        List<Object> orders = new ArrayList<>();
        Map<String, Object> portfolio;
        if (state.session().blind()) {
            BlindViewData view = blindView(state);
            portfolio = blindPortfolio(view);
            for (OrderState order : state.replay().orders()) {
                orders.add(blindOrder(state, view, order));
            }
        } else {
            portfolio = OpenViews.portfolio(state);
            for (OrderState order : state.replay().orders()) {
                orders.add(OpenViews.order(state, order));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", state.session().id());
        out.put("endReason", endReason);
        out.put("finishedAtStep", state.step());
        out.put("finishedAtDay", day);
        out.put("mode", state.session().blind() ? "BLIND" : "OPEN");
        out.put("portfolio", portfolio);
        out.put("orders", orders);
        return out;
    }

    /** Тело события {@code step}. */
    Map<String, Object> stepEvent(Map<String, Object> stepResult) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", stepResult);
        return out;
    }

    /** Тело события {@code order_updated} по заявке ядра. */
    Map<String, Object> orderUpdatedEvent(ArenaState state, long coreOrderId) {
        OrderState order = state.replay().orders().stream()
                .filter(o -> o.id() == coreOrderId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Заявки нет в состоянии сессии: " + coreOrderId));
        Map<String, Object> out = new LinkedHashMap<>();
        if (state.session().blind()) {
            BlindViewData view = blindView(state);
            out.put("mode", "BLIND");
            out.put("order", blindOrder(state, view, order));
            out.put("portfolio", blindPortfolio(view));
        } else {
            out.put("mode", "OPEN");
            out.put("order", OpenViews.order(state, order));
            out.put("portfolio", OpenViews.portfolio(state));
        }
        return out;
    }

    /** Тело события {@code player}: смена состояния плеера для других вкладок. */
    Map<String, Object> playerEvent(boolean playing, int speed) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("playing", playing);
        out.put("speed", speed);
        return out;
    }

    /** Тело события {@code paused}; причина — {@code user}, {@code disconnect}, {@code worker_failure}, {@code restart}. */
    Map<String, Object> pausedEvent(String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reason", reason);
        return out;
    }

    /** Тело события {@code finished}. */
    Map<String, Object> finishedEvent(Map<String, Object> finishResult) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", finishResult);
        return out;
    }

    /**
     * Фундаментальные показатели бумаг сессии ({@code SessionFundamentalsResponse}, INV-1, INV-7). В активной слепой
     * сессии ({@code !session.revealed()}) полностью скрыты: {@code hiddenByBlindMode = true}, {@code asOfDate = null},
     * {@code items = []}.
     */
    Map<String, Object> sessionFundamentals(
            SessionRecord session, @Nullable LocalDate asOfDate, Map<String, SecurityFundamental> byTicker) {
        if (!session.revealed()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("hiddenByBlindMode", true);
            out.put("asOfDate", null);
            out.put("items", List.of());
            return out;
        }
        return OpenViews.sessionFundamentals(session, asOfDate, byTicker);
    }

    private static Map<String, Object> blindCandle(BlindBarData bar, int candleInDay) {
        Map<String, Object> candle = new LinkedHashMap<>();
        candle.put("step", bar.step());
        candle.put("day", bar.tradingDay());
        candle.put("candleInDay", candleInDay);
        candle.put("open", ViewFormat.number(bar.open()));
        candle.put("high", ViewFormat.number(bar.high()));
        candle.put("low", ViewFormat.number(bar.low()));
        candle.put("close", ViewFormat.number(bar.close()));
        candle.put("volume", ViewFormat.number(bar.volumeRel()));
        candle.put("afterHalt", bar.afterHalt());
        return candle;
    }

    private static BlindViewData blindView(ArenaState state) {
        BlindViewData view = state.replay().blindView();
        if (view == null) {
            throw new IllegalStateException("Воркер не вернул слепой вид слепой сессии");
        }
        return view;
    }

    private static Map<String, Object> blindOrder(ArenaState state, BlindViewData view, OrderState order) {
        BlindOrderData prices = view.orders().stream()
                .filter(o -> o.id() == order.id())
                .findFirst()
                .orElse(null);
        BigDecimal[] levels = ViewFormat.levels(
                order.spec().kind(),
                prices == null ? null : prices.limitPrice(),
                prices == null ? null : prices.stopPrice());
        Map<String, Object> out =
                ViewFormat.orderBase(state, order, levels, prices == null ? null : prices.trailingStopLevel());
        out.put("mode", "BLIND");
        out.put("paperIndex", indexOf(state, order.spec().paperKey()));
        return out;
    }

    private static Map<String, Object> blindFill(ArenaState state, BlindFillData fill, ViewFormat.StepIndex index) {
        OrderState order = state.replay().orders().stream()
                .filter(o -> o.id() == fill.orderId())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Исполнение без заявки в состоянии сессии"));
        int[] position = index.at(fill.step());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", DerivedIds.fill(state.session().id(), fill.seq()));
        out.put("orderId", state.orderIds().get(fill.orderId()));
        out.put("paperIndex", fill.index());
        out.put("step", fill.step());
        out.put("day", position[0]);
        out.put("candleInDay", position[1]);
        out.put("side", ViewFormat.side(fill.side()));
        out.put("type", ViewFormat.legType(order.spec().kind()));
        out.put("lots", fill.lots());
        out.put("price", ViewFormat.number(fill.price()));
        out.put("amountPct", ViewFormat.number(fill.valuePct()));
        // общие издержки сделки в % считает core (core#54), backend ничего не складывает
        out.put("costPct", ViewFormat.number(fill.costPct()));
        out.put("stopAndTakeSameBar", fill.stopAndTakeSameBar());
        return out;
    }

    private static Map<String, Object> blindPortfolio(BlindViewData view) {
        List<Object> positions = new ArrayList<>();
        for (BlindPositionData p : view.portfolio().positions()) {
            Map<String, Object> position = new LinkedHashMap<>();
            position.put("paperIndex", p.index());
            position.put("lots", p.lots());
            position.put("avgPrice", ViewFormat.number(p.avgPrice()));
            position.put("pnlPct", ViewFormat.number(p.pnlPct()));
            position.put("valuePct", ViewFormat.number(p.marketValuePct()));
            positions.add(position);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", "BLIND");
        out.put("equityPct", ViewFormat.number(view.portfolio().equityPct()));
        out.put("cashPct", ViewFormat.number(view.portfolio().cashPct()));
        out.put("investedPct", ViewFormat.number(view.portfolio().investedPct()));
        out.put("pnlPct", ViewFormat.number(view.portfolio().pnlPct()));
        out.put("costsPct", ViewFormat.number(view.portfolio().costsPct()));
        out.put("positions", positions);
        return out;
    }

    private static int indexOf(ArenaState state, String paperKey) {
        return state.session().papers().stream()
                .filter(p -> p.secid().equals(paperKey))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Бумага ответа воркера не входит в сессию"))
                .paperIndex();
    }

    private static String detail(LaunchRejectReason reason) {
        return switch (reason) {
            case NO_MATCHING_PERIOD -> "No suitable period for the selected parameters";
            case TOO_MANY_BARS -> "Too many candles for a manual session";
            case INVALID_PARAMS -> "Invalid launch parameters";
            case CORPORATE_ACTION_ON_PERIOD -> "A corporate action falls into the period";
        };
    }
}
