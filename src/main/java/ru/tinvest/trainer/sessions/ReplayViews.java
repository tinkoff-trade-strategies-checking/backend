package ru.tinvest.trainer.sessions;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import ru.tinvest.trainer.worker.WorkerTypes.BarData;
import ru.tinvest.trainer.worker.WorkerTypes.FillState;
import ru.tinvest.trainer.worker.WorkerTypes.OrderState;
import ru.tinvest.trainer.worker.WorkerTypes.ReplayResult;

/**
 * Приращение воспроизведения: {@code OpenStepResult} за {@code (fromStep; step]} (контракт, {@code getReplay}). Снимок
 * на шаге — {@link OpenViews#snapshot}, как у арены. Воспроизведение — только завершённой, раскрытой сессии.
 */
final class ReplayViews {

    private ReplayViews() {}

    /**
     * Результат шага {@code state.step()}: свечи и статистика бумаг — по последнему бару шага, исполнения — шагов
     * {@code (fromStep; step]}, заявки — те, что подавались, закрывались или исполнялись в этом интервале;
     * портфель — на шаге.
     */
    static Map<String, Object> increment(ArenaState state, long fromStep, boolean finished) {
        SessionRecord session = state.session();
        ReplayResult replay = state.replay();
        ViewFormat.StepIndex index = new ViewFormat.StepIndex();
        replay.history().forEach(b -> index.add(b.step(), b.tradingDay()));
        index.build();
        long step = state.step();

        Map<String, Object> bars = new LinkedHashMap<>();
        Map<String, BigDecimal> lastClose = new HashMap<>();
        for (BarData bar : replay.history()) {
            lastClose.put(bar.paperKey(), bar.close());
            if (bar.step() == step) {
                bars.put(paperId(session, bar.paperKey()).toString(), candle(bar, index));
            }
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        for (PaperRecord paper : session.papers()) {
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("changePct", null);
            stat.put("lastPrice", ViewFormat.numberOrNull(lastClose.get(paper.secid())));
            stats.put(paper.paperId().toString(), stat);
        }

        List<Object> fills = new ArrayList<>();
        Set<Long> touched = new HashSet<>();
        for (FillState fill : replay.fills()) {
            if (fill.step() > fromStep && fill.step() <= step) {
                fills.add(OpenViews.fill(state, fill, index, replay));
                touched.add(fill.orderId());
            }
        }
        List<Object> orders = new ArrayList<>();
        for (OrderState order : replay.orders()) {
            boolean placed = order.placedStep() > fromStep && order.placedStep() <= step;
            boolean closed = order.closedStep() > fromStep && order.closedStep() <= step;
            if (placed || closed || touched.contains(order.id())) {
                orders.add(OpenViews.order(state, order));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("step", step);
        out.put("day", index.at(step)[0]);
        out.put("finished", finished);
        out.put("mode", "OPEN");
        out.put("bars", bars);
        out.put("paperStats", stats);
        out.put("portfolio", OpenViews.portfolio(state));
        out.put("fills", fills);
        out.put("orders", orders);
        return out;
    }

    private static Map<String, Object> candle(BarData bar, ViewFormat.StepIndex index) {
        Map<String, Object> candle = new LinkedHashMap<>();
        candle.put("step", bar.step());
        candle.put("day", bar.tradingDay());
        candle.put("candleInDay", index.at(bar.step())[1]);
        candle.put("openedAt", ViewFormat.instant(bar.timeUnixMs()));
        candle.put("open", ViewFormat.number(bar.open()));
        candle.put("high", ViewFormat.number(bar.high()));
        candle.put("low", ViewFormat.number(bar.low()));
        candle.put("close", ViewFormat.number(bar.close()));
        candle.put("volume", bar.volumeLots());
        candle.put("afterHalt", bar.afterHalt());
        return candle;
    }

    private static UUID paperId(SessionRecord session, String paperKey) {
        return session.papers().stream()
                .filter(p -> p.secid().equals(paperKey))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Бумага ответа воркера не входит в сессию"))
                .paperId();
    }
}
