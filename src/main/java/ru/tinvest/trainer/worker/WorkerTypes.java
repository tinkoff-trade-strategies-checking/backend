package ru.tinvest.trainer.worker;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Типы обмена с Simulation Worker: Java-записи вместо protobuf-сообщений ({@code proto/simulation.proto}). Деньги, цены и
 * проценты — {@link BigDecimal} из {@code Decimal} ядра (INV-6). Слепые (Blind*) данные уже без реальных меток и тикеров
 * (INV-7); их к клиенту отдаёт только {@code sessions.BlindMasking}.
 */
public final class WorkerTypes {

    private WorkerTypes() {}

    public enum TradeSide {
        BUY,
        SELL
    }

    public enum OrderKind {
        MARKET,
        LIMIT,
        STOP,
        STOP_LIMIT,
        TAKE_PROFIT,
        TRAILING_STOP
    }

    public enum Tif {
        DAY,
        GTC
    }

    public enum OrderPhase {
        ACTIVE,
        PARTIALLY_FILLED,
        FILLED,
        CANCELLED,
        EXPIRED,
        REJECTED
    }

    /** Причина отказа или снятия заявки ({@code enum Reason}). */
    public enum RejectCode {
        INVALID_PARAMS,
        INSUFFICIENT_CASH,
        INSUFFICIENT_POSITION,
        SESSION_NOT_ACTIVE,
        ORDER_NOT_ACTIVE,
        LIQUIDITY_LIMIT,
        OCO_SIBLING_FILLED,
        POSITION_CLOSED,
        REPLACED,
        SESSION_END,
        CANCELLED_BY_USER,
        DAY_EXPIRED
    }

    public enum RunState {
        RUNNING,
        FINISHED_END,
        BANKRUPT
    }

    public enum BarInterval {
        M1,
        M5,
        M10,
        M15,
        H1,
        D1
    }

    /** Бумага сессии ({@code Instrument}); {@code key} — ключ в сессии. */
    public record Paper(String key, String ticker, long lotSize, BigDecimal priceStep) {}

    /** Издержки исполнения ({@code CostConfig}). */
    public record Costs(
            BigDecimal commissionRate,
            BigDecimal exchangeFeeRate,
            BigDecimal spread,
            BigDecimal slippageKappa,
            BigDecimal liquidityShare) {}

    /** Условия сессии ({@code MarketSpec}). */
    public record Market(
            String snapshotId,
            List<Paper> papers,
            BarInterval interval,
            LocalDate fromDate,
            LocalDate toDate,
            Costs costs,
            BigDecimal initialCash,
            String benchmarkTicker,
            int warmupBars) {}

    /**
     * Заявка ({@code OrderSpec}). {@code blindPrice} — цены заданы в слепой шкале; допустимо только в оценке, журнал
     * хранит рубли.
     */
    public record OrderParams(
            String paperKey,
            TradeSide side,
            OrderKind kind,
            long lots,
            @Nullable BigDecimal limitPrice,
            @Nullable BigDecimal stopPrice,
            @Nullable BigDecimal trailingPercent,
            Tif tif,
            boolean blindPrice) {}

    /** Команда журнала ({@code JournalEntry.command}). */
    public sealed interface Command {

        /** Подача; {@code ocoWith} — вторая нога OCO или {@code null}. */
        record Place(OrderParams order, @Nullable OrderParams ocoWith) implements Command {}

        record Cancel(long orderId) implements Command {}

        record Replace(long orderId, Place replacement) implements Command {}
    }

    /** Запись журнала: {@code step} — шаг курсора на момент приёма, действует с {@code step + 1} (INV-2). */
    public record JournalRecord(long seq, long step, Command command, String ruleName, String because) {
        public JournalRecord(long seq, long step, Command command) {
            this(seq, step, command, "", "");
        }
    }

    public record OrderState(
            long id,
            OrderParams spec,
            long placedStep,
            OrderPhase phase,
            @Nullable RejectCode reason,
            String invalidField,
            long filledLots,
            long ocoSiblingId,
            @Nullable BigDecimal trailingStopLevel,
            long closedStep,
            String ruleName,
            String because) {
        public OrderState(
                long id,
                OrderParams spec,
                long placedStep,
                OrderPhase phase,
                @Nullable RejectCode reason,
                String invalidField,
                long filledLots,
                long ocoSiblingId,
                @Nullable BigDecimal trailingStopLevel,
                long closedStep) {
            this(
                    id,
                    spec,
                    placedStep,
                    phase,
                    reason,
                    invalidField,
                    filledLots,
                    ocoSiblingId,
                    trailingStopLevel,
                    closedStep,
                    "",
                    "");
        }
    }

    public record FillState(
            long orderId,
            long step,
            String paperKey,
            TradeSide side,
            long lots,
            BigDecimal price,
            BigDecimal value,
            BigDecimal commission,
            BigDecimal exchangeFee,
            BigDecimal spreadCost,
            BigDecimal slippageCost,
            boolean stopAndTakeSameBar,
            long seq,
            BigDecimal cost,
            BigDecimal costPct,
            BigDecimal amountPct) {}

    /** Позиция ({@code Position}); {@code valuePct} и {@code pnlPct} — % начального капитала, считает core. */
    public record PositionState(
            String paperKey,
            long lots,
            BigDecimal avgPrice,
            BigDecimal marketValue,
            BigDecimal valuePct,
            BigDecimal pnlPct) {}

    /** Портфель ({@code Portfolio}): проценты (уже ×100) и рубли производных величин считает core. */
    public record PortfolioState(
            BigDecimal cash,
            BigDecimal equity,
            List<PositionState> positions,
            BigDecimal investedPct,
            BigDecimal costsPct,
            BigDecimal pnlPct,
            BigDecimal equityPct,
            BigDecimal cashPct,
            BigDecimal invested,
            BigDecimal pnl,
            BigDecimal costs) {}

    /** Изменение цены бумаги на курсоре ({@code InstrumentChange}), %; {@code null} — у бумаги нет бара на курсоре. */
    public record InstrumentChangeData(
            String paperKey,
            @Nullable BigDecimal stepPct,
            @Nullable BigDecimal periodPct) {}

    public record BarData(
            long step,
            String paperKey,
            long timeUnixMs,
            int tradingDay,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            long volumeLots,
            boolean afterHalt) {}

    public record CommandOutcome(
            long seq, boolean accepted, @Nullable RejectCode reason, String invalidField, List<Long> orderIds) {}

    public record BlindPaperData(
            int index,
            @Nullable BigDecimal lotValueAtStart,
            @Nullable BigDecimal changeStepPct,
            @Nullable BigDecimal changePeriodPct) {}

    public record BlindBarData(
            long step,
            int index,
            int tradingDay,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            BigDecimal volumeRel,
            boolean afterHalt) {}

    public record BlindPositionData(
            int index, long lots, BigDecimal avgPrice, BigDecimal marketValuePct, BigDecimal pnlPct) {}

    public record BlindPortfolioData(
            BigDecimal cashPct,
            BigDecimal equityPct,
            BigDecimal pnlPct,
            List<BlindPositionData> positions,
            BigDecimal investedPct,
            BigDecimal costsPct) {}

    public record BlindOrderData(
            long id,
            int index,
            @Nullable BigDecimal limitPrice,
            @Nullable BigDecimal stopPrice,
            @Nullable BigDecimal trailingStopLevel) {}

    public record BlindFillData(
            long orderId,
            long step,
            int index,
            TradeSide side,
            long lots,
            BigDecimal price,
            BigDecimal valuePct,
            BigDecimal commissionPct,
            BigDecimal exchangeFeePct,
            BigDecimal spreadCostPct,
            BigDecimal slippageCostPct,
            boolean stopAndTakeSameBar,
            BigDecimal costPct,
            long seq) {}

    /** Слепой вид сессии ({@code BlindSessionView}): индексы бумаг вместо ключей, проценты вместо рублей. */
    public record BlindViewData(
            List<BlindPaperData> papers,
            List<BlindBarData> history,
            BlindPortfolioData portfolio,
            List<BlindOrderData> orders,
            List<BlindFillData> fills) {}

    /**
     * Свечи шагов пачки {@code (fromStep; upToStep]} из событий шагов ядра: рублёвые и слепые. История до курсора не
     * нужна — по ним строится событие шага плеера.
     */
    public record StepBars(List<BarData> bars, List<BlindBarData> blindBars) {}

    /** Состояние на курсоре без истории и свечи пройденной пачки шагов. */
    public record StepReplay(ReplayResult replay, StepBars steps) {}

    /** Результат воспроизведения журнала ({@code ReplaySessionResponse}). */
    public record ReplayResult(
            long totalSteps,
            RunState state,
            long lastStep,
            List<BarData> history,
            PortfolioState portfolio,
            List<OrderState> orders,
            List<FillState> fills,
            List<CommandOutcome> commandResults,
            long warmupSteps,
            @Nullable BlindViewData blindView,
            @Nullable ReportData report,
            int currentDay,
            List<InstrumentChangeData> instrumentChanges) {}

    /**
     * Метрики отчёта ({@code Metrics}). Доли — как у ядра (0,12 = 12 %), {@code null} — необязательное значение ядра
     * отсутствует (мало данных).
     */
    public record MetricsData(
            double totalReturn,
            double grossReturn,
            @Nullable Double cagr,
            double maxDrawdown,
            long maxDrawdownSteps,
            @Nullable Double volatility,
            @Nullable Double sharpe,
            @Nullable Double sortino,
            double turnover,
            long fills,
            long roundTrips,
            @Nullable Double winRate,
            long rejections,
            long partialFills,
            double hhiAvg,
            double maxPositionShare,
            @Nullable Double benchmarkReturn,
            int tradingDays,
            long stopTakeSameBar,
            long maxDrawdownDays) {}

    /** Издержки по видам ({@code CostTotals}). */
    public record CostTotalsData(
            BigDecimal commission, BigDecimal exchangeFee, BigDecimal spread, BigDecimal slippage, BigDecimal total) {}

    /** Точка кривой капитала или бенчмарка ({@code EquityPoint}). */
    public record EquityPointData(long step, BigDecimal equity) {}

    /** Закрытая сделка — пара покупка → продажа по FIFO ({@code RoundTrip}). */
    public record RoundTripData(
            String paperKey,
            long entryStep,
            long exitStep,
            long lots,
            BigDecimal entryPrice,
            BigDecimal exitPrice,
            BigDecimal grossPnl,
            BigDecimal costs,
            BigDecimal netPnl,
            long seq) {}

    /** Точка кривой просадки ({@code UnderwaterPoint}): глубина от пика в процентах (уже ×100). */
    public record UnderwaterPointData(long step, BigDecimal drawdownPct) {}

    /** Отчёт ядра ({@code Report}) в рублях: только раскрытая (завершённая) сессия. */
    public record ReportData(
            MetricsData metrics,
            CostTotalsData costs,
            List<EquityPointData> equity,
            BigDecimal finalEquity,
            List<EquityPointData> benchmarkEquity,
            List<RoundTripData> roundTrips,
            List<UnderwaterPointData> underwater,
            List<StrategyFinding> findings,
            CheckLevel level) {
        public ReportData(
                MetricsData metrics,
                CostTotalsData costs,
                List<EquityPointData> equity,
                BigDecimal finalEquity,
                List<EquityPointData> benchmarkEquity,
                List<RoundTripData> roundTrips,
                List<UnderwaterPointData> underwater) {
            this(
                    metrics,
                    costs,
                    equity,
                    finalEquity,
                    benchmarkEquity,
                    roundTrips,
                    underwater,
                    List.of(),
                    CheckLevel.OK);
        }
    }

    /** Уровень статической и рантайм-диагностики стратегии ({@code enum Level}). */
    public enum CheckLevel {
        OK,
        WARNING,
        ERROR
    }

    /** Итог прогона стратегии ({@code enum RunStatus}). */
    public enum StrategyRunStatus {
        COMPLETED,
        BANKRUPT_STOP,
        INVALID
    }

    public record TextEdit(int startLine, int startColumn, int endLine, int endColumn, String replacement) {}

    public record DiagnosticFix(String description, List<TextEdit> edits) {}

    public record StrategyDiagnostic(
            String code,
            CheckLevel level,
            int startLine,
            int startColumn,
            int endLine,
            int endColumn,
            String message,
            List<DiagnosticFix> fixes) {}

    public record StrategyParamDecl(
            String name,
            double defaultValue,
            @Nullable Double rangeMin,
            @Nullable Double rangeMax,
            @Nullable String step,
            boolean integer) {}

    public record StrategyCheck(
            CheckLevel lookaheadLevel, List<StrategyDiagnostic> diagnostics, List<StrategyParamDecl> params) {}

    public record RunProgressData(long doneSteps, long totalSteps) {}

    public record StrategyFinding(String code, CheckLevel level, String message, long step) {}

    public record StrategyRunOutput(
            StrategyRunStatus status,
            CheckLevel level,
            List<StrategyDiagnostic> diagnostics,
            @Nullable ReportData report,
            List<FillState> fills,
            List<OrderState> orders,
            List<JournalRecord> journal,
            long digest,
            long lastStep,
            long totalSteps,
            List<BarData> history) {}

    /** Оценка заявки ({@code EstimateOrderResponse}); {@code resolvedOrder} — рублёвая заявка для журнала. */
    public record EstimateResult(
            boolean canPlace,
            @Nullable RejectCode reason,
            String invalidField,
            BigDecimal referencePrice,
            BigDecimal amount,
            BigDecimal commission,
            BigDecimal exchangeFee,
            BigDecimal requiredCash,
            BigDecimal amountPct,
            BigDecimal commissionPct,
            BigDecimal exchangeFeePct,
            BigDecimal requiredCashPct,
            @Nullable OrderParams resolvedOrder) {}
}
