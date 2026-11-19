package ru.tinvest.trainer.sessions;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ru.tinvest.trainer.marketdata.LaunchRejectReason;
import ru.tinvest.trainer.marketdata.LaunchRejection;
import ru.tinvest.trainer.worker.WorkerTypes.BlindBarData;
import ru.tinvest.trainer.worker.WorkerTypes.BlindFillData;
import ru.tinvest.trainer.worker.WorkerTypes.BlindOrderData;
import ru.tinvest.trainer.worker.WorkerTypes.BlindPaperData;
import ru.tinvest.trainer.worker.WorkerTypes.BlindPositionData;
import ru.tinvest.trainer.worker.WorkerTypes.BlindViewData;
import ru.tinvest.trainer.worker.WorkerTypes.OrderState;

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
        body.put("code", rejection.reason().name());
        body.put("field", rejection.field());
        body.put("barsCount", rejection.barsCount());
        body.put("maxBars", rejection.maxBars());
        return body;
    }

    // ---------- арена ----------

    /** Снимок сессии на курсоре ({@code Snapshot}). */
    Map<String, Object> snapshot(ArenaState state) {
        if (!state.session().blind()) {
            return OpenViews.snapshot(state);
        }
        BlindViewData view = blindView(state);
        ViewFormat.StepIndex index = new ViewFormat.StepIndex();
        view.history().forEach(b -> index.add(b.step(), b.tradingDay()));
        index.build();

        Map<Integer, List<Object>> candles = new HashMap<>();
        Map<Integer, BigDecimal> lastClose = new HashMap<>();
        for (BlindBarData bar : view.history()) {
            int[] position = index.at(bar.step());
            Map<String, Object> candle = new LinkedHashMap<>();
            candle.put("step", bar.step());
            candle.put("day", bar.tradingDay());
            candle.put("candleInDay", position[1]);
            candle.put("open", ViewFormat.number(bar.open()));
            candle.put("high", ViewFormat.number(bar.high()));
            candle.put("low", ViewFormat.number(bar.low()));
            candle.put("close", ViewFormat.number(bar.close()));
            candle.put("volume", ViewFormat.number(bar.volumeRel()));
            candle.put("afterHalt", bar.afterHalt());
            candles.computeIfAbsent(bar.index(), k -> new ArrayList<>()).add(candle);
            lastClose.put(bar.index(), bar.close());
        }

        // бумага без бара до курсора в слепом виде отсутствует целиком
        List<Object> papers = new ArrayList<>();
        for (BlindPaperData paper : view.papers()) {
            if (paper.lotValueAtStart() == null || !lastClose.containsKey(paper.index())) {
                continue;
            }
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("changePct", null);
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
        if (!session.blind()) {
            return OpenViews.estimate(out, view.amounts());
        }
        out.put("mode", "BLIND");
        return out;
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
        BigDecimal cost = fill.commissionPct()
                .add(fill.exchangeFeePct())
                .add(fill.spreadCostPct())
                .add(fill.slippageCostPct());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", DerivedIds.fill(state.session().id(), fill.orderId(), fill.step()));
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
        out.put("costPct", ViewFormat.number(cost));
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
            position.put("pnlPct", null);
            position.put("valuePct", ViewFormat.number(p.marketValuePct()));
            positions.add(position);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", "BLIND");
        out.put("equityPct", ViewFormat.number(view.portfolio().equityPct()));
        out.put("cashPct", ViewFormat.number(view.portfolio().cashPct()));
        out.put("investedPct", null);
        out.put("pnlPct", ViewFormat.number(view.portfolio().pnlPct()));
        out.put("costsPct", null);
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
