package ru.tinvest.trainer.sessions;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import ru.tinvest.trainer.worker.WorkerTypes.EstimateResult;
import ru.tinvest.trainer.worker.WorkerTypes.OrderKind;
import ru.tinvest.trainer.worker.WorkerTypes.RejectCode;
import ru.tinvest.trainer.worker.WorkerTypes.ReplayResult;

/**
 * Состояние сессии на курсоре — результат проигрывания журнала (INV-8), никакого другого изменяемого состояния нет.
 * Рублёвые данные ({@code replay}) и слепой вид ({@code replay.blindView()}) наружу отдаёт только {@link BlindMasking}.
 *
 * @param orderIds идентификаторы заявок наружу (UUID v7) по номеру в ядре
 * @param seenSteps {@code seen_step} из журнала по номеру команды {@code seq}
 * @param player состояние плеера: играет ли время и скорость
 */
record ArenaState(
        SessionRecord session,
        ReplayResult replay,
        Map<Long, UUID> orderIds,
        Map<Long, Long> seenSteps,
        PlayerStates.Row player,
        boolean killSwitchTriggered) {

    ArenaState(
            SessionRecord session,
            ReplayResult replay,
            Map<Long, UUID> orderIds,
            Map<Long, Long> seenSteps,
            PlayerStates.Row player) {
        this(session, replay, orderIds, seenSteps, player, session.killSwitchTriggered());
    }

    /** Номер команды, создавшей заявку: {@code id = seq * 2 + нога}. */
    static long seqOf(long coreOrderId) {
        return coreOrderId / 2;
    }

    long step() {
        return session.currentStep();
    }

    /**
     * Итог команды заявки для ответа.
     *
     * @param orderIds созданные заявки (в ядре) — поле {@code orderIds} ответа
     * @param shownIds заявки, которые показываются в {@code order} и {@code orders} (для отмены — отменённые)
     */
    record CommandReply(
            boolean accepted,
            @Nullable RejectCode reason,
            @Nullable String field,
            List<Long> orderIds,
            List<Long> shownIds) {}

    /** Оценка одной ноги заявки (у OCO — стоп и тейк-профит). */
    record EstimateLeg(
            OrderKind kind,
            boolean canPlace,
            @Nullable RejectCode reason,
            @Nullable String field,
            EstimateResult amounts) {}

    /** Быстрые пресеты лотов от доступного капитала (для покупки) или позиции (для продажи) (backend#87, INV-6/INV-7). */
    record PresetLots(long pct25, long pct50, long pct100) {}

    /**
     * Итог оценки заявки: {@code amounts} — суммы первой ноги, {@code canPlace} — по всем ногам; {@code legs} — оценка
     * каждой ноги, пусто, если оценки нет (отказ до воркера, сессия не активна — суммы нулевые).
     */
    record EstimateView(
            boolean canPlace,
            @Nullable RejectCode reason,
            @Nullable String field,
            EstimateResult amounts,
            List<EstimateLeg> legs,
            @Nullable Long maxLots,
            @Nullable PresetLots presetLots) {

        EstimateView(
                boolean canPlace,
                @Nullable RejectCode reason,
                @Nullable String field,
                EstimateResult amounts,
                List<EstimateLeg> legs) {
            this(canPlace, reason, field, amounts, legs, null, null);
        }

        static EstimateResult zeros() {
            BigDecimal zero = BigDecimal.ZERO;
            return new EstimateResult(false, null, "", zero, zero, zero, zero, zero, zero, zero, zero, zero, null);
        }
    }
}
