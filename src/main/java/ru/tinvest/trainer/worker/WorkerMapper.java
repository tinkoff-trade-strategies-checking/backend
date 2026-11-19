package ru.tinvest.trainer.worker;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import pac.simulation.v1.Decimal;
import ru.tinvest.trainer.worker.WorkerTypes.BarData;
import ru.tinvest.trainer.worker.WorkerTypes.BarInterval;
import ru.tinvest.trainer.worker.WorkerTypes.BlindBarData;
import ru.tinvest.trainer.worker.WorkerTypes.BlindFillData;
import ru.tinvest.trainer.worker.WorkerTypes.BlindOrderData;
import ru.tinvest.trainer.worker.WorkerTypes.BlindPaperData;
import ru.tinvest.trainer.worker.WorkerTypes.BlindPortfolioData;
import ru.tinvest.trainer.worker.WorkerTypes.BlindPositionData;
import ru.tinvest.trainer.worker.WorkerTypes.BlindViewData;
import ru.tinvest.trainer.worker.WorkerTypes.Command;
import ru.tinvest.trainer.worker.WorkerTypes.CommandOutcome;
import ru.tinvest.trainer.worker.WorkerTypes.EstimateResult;
import ru.tinvest.trainer.worker.WorkerTypes.FillState;
import ru.tinvest.trainer.worker.WorkerTypes.JournalRecord;
import ru.tinvest.trainer.worker.WorkerTypes.Market;
import ru.tinvest.trainer.worker.WorkerTypes.OrderKind;
import ru.tinvest.trainer.worker.WorkerTypes.OrderParams;
import ru.tinvest.trainer.worker.WorkerTypes.OrderPhase;
import ru.tinvest.trainer.worker.WorkerTypes.OrderState;
import ru.tinvest.trainer.worker.WorkerTypes.Paper;
import ru.tinvest.trainer.worker.WorkerTypes.PortfolioState;
import ru.tinvest.trainer.worker.WorkerTypes.PositionState;
import ru.tinvest.trainer.worker.WorkerTypes.RejectCode;
import ru.tinvest.trainer.worker.WorkerTypes.ReplayResult;
import ru.tinvest.trainer.worker.WorkerTypes.RunState;
import ru.tinvest.trainer.worker.WorkerTypes.Tif;
import ru.tinvest.trainer.worker.WorkerTypes.TradeSide;

/** Перевод между protobuf-сообщениями воркера и {@link WorkerTypes}. Деньги — {@link BigDecimal} (INV-6). */
final class WorkerMapper {

    private static final BigInteger NANO = BigInteger.valueOf(1_000_000_000L);

    private WorkerMapper() {}

    // ---------- Decimal ----------

    static Decimal decimal(BigDecimal value) {
        // 9 знаков — точность Decimal ядра; больше — ошибка (UNNECESSARY), а не молчаливое округление
        BigInteger[] parts = value.setScale(9).unscaledValue().divideAndRemainder(NANO);
        return Decimal.newBuilder()
                .setUnits(parts[0].longValueExact())
                .setNano(parts[1].intValueExact())
                .build();
    }

    static BigDecimal decimal(Decimal value) {
        return BigDecimal.valueOf(value.getUnits()).add(BigDecimal.valueOf(value.getNano(), 9));
    }

    // ---------- запрос ----------

    static pac.simulation.v1.MarketSpec market(Market market) {
        pac.simulation.v1.MarketSpec.Builder b = pac.simulation.v1.MarketSpec.newBuilder()
                .setSnapshotId(market.snapshotId())
                .setTimeframe(interval(market.interval()))
                .setFromDate(market.fromDate().toString())
                .setToDate(market.toDate().toString())
                .setCosts(pac.simulation.v1.CostConfig.newBuilder()
                        .setCommissionRate(decimal(market.costs().commissionRate()))
                        .setExchangeFeeRate(decimal(market.costs().exchangeFeeRate()))
                        .setSpread(decimal(market.costs().spread()))
                        .setSlippageKappa(decimal(market.costs().slippageKappa()))
                        .setLiquidityShare(decimal(market.costs().liquidityShare())))
                .setInitialCash(decimal(market.initialCash()))
                .setBenchmarkTicker(market.benchmarkTicker())
                .setWarmupBars(market.warmupBars());
        for (Paper p : market.papers()) {
            b.addInstruments(pac.simulation.v1.Instrument.newBuilder()
                    .setKey(p.key())
                    .setTicker(p.ticker())
                    .setLotSize(p.lotSize())
                    .setPriceStep(decimal(p.priceStep())));
        }
        return b.build();
    }

    private static pac.simulation.v1.Timeframe interval(BarInterval interval) {
        return switch (interval) {
            case M1 -> pac.simulation.v1.Timeframe.M1;
            case M5 -> pac.simulation.v1.Timeframe.M5;
            case M10 -> pac.simulation.v1.Timeframe.M10;
            case M15 -> pac.simulation.v1.Timeframe.M15;
            case H1 -> pac.simulation.v1.Timeframe.H1;
            case D1 -> pac.simulation.v1.Timeframe.D1;
        };
    }

    static pac.simulation.v1.OrderSpec spec(OrderParams p) {
        pac.simulation.v1.OrderSpec.Builder b = pac.simulation.v1.OrderSpec.newBuilder()
                .setInstrumentKey(p.paperKey())
                .setSide(p.side() == TradeSide.BUY ? pac.simulation.v1.Side.BUY : pac.simulation.v1.Side.SELL)
                .setType(kind(p.kind()))
                .setLots(p.lots())
                .setTimeInForce(
                        p.tif() == Tif.DAY ? pac.simulation.v1.TimeInForce.DAY : pac.simulation.v1.TimeInForce.GTC)
                .setIsBlindPrice(p.blindPrice());
        if (p.limitPrice() != null) {
            b.setLimitPrice(decimal(p.limitPrice()));
        }
        if (p.stopPrice() != null) {
            b.setStopPrice(decimal(p.stopPrice()));
        }
        if (p.trailingPercent() != null) {
            b.setTrailingPercent(decimal(p.trailingPercent()));
        }
        return b.build();
    }

    private static pac.simulation.v1.OrderType kind(OrderKind kind) {
        return switch (kind) {
            case MARKET -> pac.simulation.v1.OrderType.MARKET;
            case LIMIT -> pac.simulation.v1.OrderType.LIMIT;
            case STOP -> pac.simulation.v1.OrderType.STOP;
            case STOP_LIMIT -> pac.simulation.v1.OrderType.STOP_LIMIT;
            case TAKE_PROFIT -> pac.simulation.v1.OrderType.TAKE_PROFIT;
            case TRAILING_STOP -> pac.simulation.v1.OrderType.TRAILING_STOP;
        };
    }

    private static pac.simulation.v1.PlaceOrder place(Command.Place place) {
        pac.simulation.v1.PlaceOrder.Builder b =
                pac.simulation.v1.PlaceOrder.newBuilder().setOrder(spec(place.order()));
        if (place.ocoWith() != null) {
            b.setOcoWith(spec(place.ocoWith()));
        }
        return b.build();
    }

    static pac.simulation.v1.JournalEntry entry(JournalRecord record) {
        pac.simulation.v1.JournalEntry.Builder b =
                pac.simulation.v1.JournalEntry.newBuilder().setSeq(record.seq()).setStep(record.step());
        switch (record.command()) {
            case Command.Place p -> b.setPlace(place(p));
            case Command.Cancel c ->
                b.setCancel(pac.simulation.v1.CancelOrder.newBuilder().setOrderId(c.orderId()));
            case Command.Replace r ->
                b.setReplace(pac.simulation.v1.ReplaceOrder.newBuilder()
                        .setOrderId(r.orderId())
                        .setReplacement(place(r.replacement())));
        }
        return b.build();
    }

    static List<pac.simulation.v1.JournalEntry> journal(List<JournalRecord> records) {
        List<pac.simulation.v1.JournalEntry> out = new ArrayList<>(records.size());
        for (JournalRecord r : records) {
            out.add(entry(r));
        }
        return out;
    }

    // ---------- ответ ----------

    static ReplayResult replay(pac.simulation.v1.ReplaySessionResponse r) {
        List<BarData> history = new ArrayList<>(r.getHistoryCount());
        for (pac.simulation.v1.Bar b : r.getHistoryList()) {
            history.add(new BarData(
                    b.getStep(),
                    b.getInstrumentKey(),
                    b.getTimeUnixMs(),
                    b.getTradingDay(),
                    decimal(b.getOpen()),
                    decimal(b.getHigh()),
                    decimal(b.getLow()),
                    decimal(b.getClose()),
                    b.getVolumeLots(),
                    b.getAfterHalt()));
        }
        List<OrderState> orders = new ArrayList<>(r.getOrdersCount());
        for (pac.simulation.v1.Order o : r.getOrdersList()) {
            orders.add(order(o));
        }
        List<FillState> fills = new ArrayList<>(r.getFillsCount());
        for (pac.simulation.v1.Fill f : r.getFillsList()) {
            fills.add(new FillState(
                    f.getOrderId(),
                    f.getStep(),
                    f.getInstrumentKey(),
                    side(f.getSide()),
                    f.getLots(),
                    decimal(f.getPrice()),
                    decimal(f.getValue()),
                    decimal(f.getCommission()),
                    decimal(f.getExchangeFee()),
                    decimal(f.getSpreadCost()),
                    decimal(f.getSlippageCost()),
                    f.getStopAndTakeSameBar()));
        }
        List<CommandOutcome> results = new ArrayList<>(r.getCommandResultsCount());
        for (pac.simulation.v1.CommandResult c : r.getCommandResultsList()) {
            results.add(new CommandOutcome(
                    c.getSeq(), c.getAccepted(), reason(c.getReason()), c.getInvalidField(), c.getOrderIdsList()));
        }
        return new ReplayResult(
                r.getTotalSteps(),
                runState(r.getStatus()),
                r.getLastStep(),
                history,
                portfolio(r.getPortfolio()),
                orders,
                fills,
                results,
                r.getWarmupSteps(),
                r.hasBlindView() ? blindView(r.getBlindView()) : null);
    }

    private static PortfolioState portfolio(pac.simulation.v1.Portfolio p) {
        List<PositionState> positions = new ArrayList<>(p.getPositionsCount());
        for (pac.simulation.v1.Position pos : p.getPositionsList()) {
            positions.add(new PositionState(
                    pos.getInstrumentKey(), pos.getLots(), decimal(pos.getAvgPrice()), decimal(pos.getMarketValue())));
        }
        return new PortfolioState(decimal(p.getCash()), decimal(p.getEquity()), positions);
    }

    private static OrderState order(pac.simulation.v1.Order o) {
        return new OrderState(
                o.getId(),
                spec(o.getSpec()),
                o.getPlacedStep(),
                phase(o.getStatus()),
                reason(o.getReason()),
                o.getInvalidField(),
                o.getFilledLots(),
                o.getOcoSiblingId(),
                o.hasTrailingStopLevel() ? decimal(o.getTrailingStopLevel()) : null,
                o.getClosedStep());
    }

    static OrderParams spec(pac.simulation.v1.OrderSpec s) {
        return new OrderParams(
                s.getInstrumentKey(),
                side(s.getSide()),
                kind(s.getType()),
                s.getLots(),
                s.hasLimitPrice() ? decimal(s.getLimitPrice()) : null,
                s.hasStopPrice() ? decimal(s.getStopPrice()) : null,
                s.hasTrailingPercent() ? decimal(s.getTrailingPercent()) : null,
                tif(s.getTimeInForce()),
                s.getIsBlindPrice());
    }

    static EstimateResult estimate(pac.simulation.v1.EstimateOrderResponse e) {
        return new EstimateResult(
                e.getCanPlace(),
                reason(e.getReason()),
                e.getInvalidField(),
                decimal(e.getReferencePrice()),
                decimal(e.getAmount()),
                decimal(e.getCommission()),
                decimal(e.getExchangeFee()),
                decimal(e.getRequiredCash()),
                decimal(e.getAmountPct()),
                decimal(e.getCommissionPct()),
                decimal(e.getExchangeFeePct()),
                decimal(e.getRequiredCashPct()),
                e.hasResolvedOrder() ? spec(e.getResolvedOrder()) : null);
    }

    private static BlindViewData blindView(pac.simulation.v1.BlindSessionView v) {
        List<BlindPaperData> papers = new ArrayList<>(v.getInstrumentsCount());
        for (pac.simulation.v1.BlindInstrument i : v.getInstrumentsList()) {
            papers.add(new BlindPaperData(
                    i.getInstrumentIndex(), i.hasLotValueAtStart() ? decimal(i.getLotValueAtStart()) : null));
        }
        List<BlindBarData> history = new ArrayList<>(v.getHistoryCount());
        for (pac.simulation.v1.BlindBar b : v.getHistoryList()) {
            history.add(new BlindBarData(
                    b.getStep(),
                    b.getInstrumentIndex(),
                    b.getTradingDay(),
                    decimal(b.getOpen()),
                    decimal(b.getHigh()),
                    decimal(b.getLow()),
                    decimal(b.getClose()),
                    decimal(b.getVolumeRel()),
                    b.getAfterHalt()));
        }
        List<BlindOrderData> orders = new ArrayList<>(v.getOrdersCount());
        for (pac.simulation.v1.BlindOrder o : v.getOrdersList()) {
            orders.add(new BlindOrderData(
                    o.getId(),
                    o.getInstrumentIndex(),
                    o.hasLimitPrice() ? decimal(o.getLimitPrice()) : null,
                    o.hasStopPrice() ? decimal(o.getStopPrice()) : null,
                    o.hasTrailingStopLevel() ? decimal(o.getTrailingStopLevel()) : null));
        }
        List<BlindFillData> fills = new ArrayList<>(v.getFillsCount());
        for (pac.simulation.v1.BlindFill f : v.getFillsList()) {
            fills.add(new BlindFillData(
                    f.getOrderId(),
                    f.getStep(),
                    f.getInstrumentIndex(),
                    side(f.getSide()),
                    f.getLots(),
                    decimal(f.getPrice()),
                    decimal(f.getValuePct()),
                    decimal(f.getCommissionPct()),
                    decimal(f.getExchangeFeePct()),
                    decimal(f.getSpreadCostPct()),
                    decimal(f.getSlippageCostPct()),
                    f.getStopAndTakeSameBar()));
        }
        pac.simulation.v1.BlindPortfolio bp = v.getPortfolio();
        List<BlindPositionData> positions = new ArrayList<>(bp.getPositionsCount());
        for (pac.simulation.v1.BlindPosition p : bp.getPositionsList()) {
            positions.add(new BlindPositionData(
                    p.getInstrumentIndex(), p.getLots(), decimal(p.getAvgPrice()), decimal(p.getMarketValuePct())));
        }
        BlindPortfolioData portfolio = new BlindPortfolioData(
                decimal(bp.getCashPct()), decimal(bp.getEquityPct()), decimal(bp.getPnlPct()), positions);
        return new BlindViewData(papers, history, portfolio, orders, fills);
    }

    // ---------- перечисления ----------

    private static TradeSide side(pac.simulation.v1.Side side) {
        return switch (side) {
            case BUY -> TradeSide.BUY;
            case SELL -> TradeSide.SELL;
            default -> throw new IllegalStateException("Воркер вернул неизвестную сторону заявки: " + side);
        };
    }

    private static OrderKind kind(pac.simulation.v1.OrderType type) {
        return switch (type) {
            case MARKET -> OrderKind.MARKET;
            case LIMIT -> OrderKind.LIMIT;
            case STOP -> OrderKind.STOP;
            case STOP_LIMIT -> OrderKind.STOP_LIMIT;
            case TAKE_PROFIT -> OrderKind.TAKE_PROFIT;
            case TRAILING_STOP -> OrderKind.TRAILING_STOP;
            default -> throw new IllegalStateException("Воркер вернул неизвестный тип заявки: " + type);
        };
    }

    private static Tif tif(pac.simulation.v1.TimeInForce tif) {
        return switch (tif) {
            case DAY -> Tif.DAY;
            case GTC -> Tif.GTC;
            default -> throw new IllegalStateException("Воркер вернул неизвестный срок заявки: " + tif);
        };
    }

    private static OrderPhase phase(pac.simulation.v1.OrderStatus status) {
        return switch (status) {
            case ACTIVE -> OrderPhase.ACTIVE;
            case PARTIALLY_FILLED -> OrderPhase.PARTIALLY_FILLED;
            case FILLED -> OrderPhase.FILLED;
            case CANCELLED -> OrderPhase.CANCELLED;
            case EXPIRED -> OrderPhase.EXPIRED;
            case REJECTED -> OrderPhase.REJECTED;
            default -> throw new IllegalStateException("Воркер вернул неизвестный статус заявки: " + status);
        };
    }

    private static @Nullable RejectCode reason(pac.simulation.v1.Reason reason) {
        return switch (reason) {
            case REASON_UNSPECIFIED -> null;
            case INVALID_PARAMS -> RejectCode.INVALID_PARAMS;
            case INSUFFICIENT_CASH -> RejectCode.INSUFFICIENT_CASH;
            case INSUFFICIENT_POSITION -> RejectCode.INSUFFICIENT_POSITION;
            case SESSION_NOT_ACTIVE -> RejectCode.SESSION_NOT_ACTIVE;
            case ORDER_NOT_ACTIVE -> RejectCode.ORDER_NOT_ACTIVE;
            case LIQUIDITY_LIMIT -> RejectCode.LIQUIDITY_LIMIT;
            case OCO_SIBLING_FILLED -> RejectCode.OCO_SIBLING_FILLED;
            case REPLACED -> RejectCode.REPLACED;
            case SESSION_END -> RejectCode.SESSION_END;
            case CANCELLED_BY_USER -> RejectCode.CANCELLED_BY_USER;
            case DAY_EXPIRED -> RejectCode.DAY_EXPIRED;
            default -> throw new IllegalStateException("Воркер вернул неизвестную причину: " + reason);
        };
    }

    private static RunState runState(pac.simulation.v1.SessionStatus status) {
        return switch (status) {
            case RUNNING -> RunState.RUNNING;
            case FINISHED_END -> RunState.FINISHED_END;
            case BANKRUPT -> RunState.BANKRUPT;
            default -> throw new IllegalStateException("Воркер вернул неизвестный статус сессии: " + status);
        };
    }
}
