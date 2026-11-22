package ru.tinvest.trainer.worker;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import pac.simulation.v1.Decimal;
import ru.tinvest.trainer.worker.WorkerTypes.AlgorithmInput;
import ru.tinvest.trainer.worker.WorkerTypes.AlgorithmPortfolioData;
import ru.tinvest.trainer.worker.WorkerTypes.BarData;
import ru.tinvest.trainer.worker.WorkerTypes.BarInterval;
import ru.tinvest.trainer.worker.WorkerTypes.BlindBarData;
import ru.tinvest.trainer.worker.WorkerTypes.BlindFillData;
import ru.tinvest.trainer.worker.WorkerTypes.BlindOrderData;
import ru.tinvest.trainer.worker.WorkerTypes.BlindPaperData;
import ru.tinvest.trainer.worker.WorkerTypes.BlindPortfolioData;
import ru.tinvest.trainer.worker.WorkerTypes.BlindPositionData;
import ru.tinvest.trainer.worker.WorkerTypes.BlindViewData;
import ru.tinvest.trainer.worker.WorkerTypes.CheckLevel;
import ru.tinvest.trainer.worker.WorkerTypes.Command;
import ru.tinvest.trainer.worker.WorkerTypes.CommandOutcome;
import ru.tinvest.trainer.worker.WorkerTypes.ConsensusItemData;
import ru.tinvest.trainer.worker.WorkerTypes.CostTotalsData;
import ru.tinvest.trainer.worker.WorkerTypes.DiagnosticFix;
import ru.tinvest.trainer.worker.WorkerTypes.DivergenceData;
import ru.tinvest.trainer.worker.WorkerTypes.DivergenceKind;
import ru.tinvest.trainer.worker.WorkerTypes.DivergenceOutput;
import ru.tinvest.trainer.worker.WorkerTypes.EquityPointData;
import ru.tinvest.trainer.worker.WorkerTypes.EstimateResult;
import ru.tinvest.trainer.worker.WorkerTypes.FillState;
import ru.tinvest.trainer.worker.WorkerTypes.InstrumentChangeData;
import ru.tinvest.trainer.worker.WorkerTypes.JournalRecord;
import ru.tinvest.trainer.worker.WorkerTypes.Market;
import ru.tinvest.trainer.worker.WorkerTypes.MetricsData;
import ru.tinvest.trainer.worker.WorkerTypes.MissedProfitableTradeData;
import ru.tinvest.trainer.worker.WorkerTypes.OrderKind;
import ru.tinvest.trainer.worker.WorkerTypes.OrderParams;
import ru.tinvest.trainer.worker.WorkerTypes.OrderPhase;
import ru.tinvest.trainer.worker.WorkerTypes.OrderState;
import ru.tinvest.trainer.worker.WorkerTypes.Paper;
import ru.tinvest.trainer.worker.WorkerTypes.PortfolioState;
import ru.tinvest.trainer.worker.WorkerTypes.PositionState;
import ru.tinvest.trainer.worker.WorkerTypes.RejectCode;
import ru.tinvest.trainer.worker.WorkerTypes.ReplayResult;
import ru.tinvest.trainer.worker.WorkerTypes.ReportData;
import ru.tinvest.trainer.worker.WorkerTypes.RoundTripData;
import ru.tinvest.trainer.worker.WorkerTypes.RunState;
import ru.tinvest.trainer.worker.WorkerTypes.StepBars;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyCheck;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyDiagnostic;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyFinding;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyParamDecl;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyRunOutput;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyRunStatus;
import ru.tinvest.trainer.worker.WorkerTypes.TextEdit;
import ru.tinvest.trainer.worker.WorkerTypes.Tif;
import ru.tinvest.trainer.worker.WorkerTypes.TradeSide;
import ru.tinvest.trainer.worker.WorkerTypes.UnderwaterPointData;

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
        pac.simulation.v1.JournalEntry.Builder b = pac.simulation.v1.JournalEntry.newBuilder()
                .setSeq(record.seq())
                .setStep(record.step())
                .setRuleName(record.ruleName())
                .setBecause(record.because());
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

    static pac.simulation.v1.CheckStrategyRequest checkStrategyRequest(
            String source, Map<String, Double> paramOverrides) {
        return pac.simulation.v1.CheckStrategyRequest.newBuilder()
                .setSource(source)
                .addAllParamOverrides(paramValues(paramOverrides))
                .build();
    }

    static pac.simulation.v1.RunStrategyRequest runStrategyRequest(
            Market market, String source, Map<String, Double> paramOverrides, boolean includeHistory) {
        return pac.simulation.v1.RunStrategyRequest.newBuilder()
                .setMarket(market(market))
                .setStrategy(pac.simulation.v1.StrategySource.newBuilder()
                        .setSource(source)
                        .addAllParamOverrides(paramValues(paramOverrides)))
                .setIncludeHistory(includeHistory)
                .build();
    }

    static pac.simulation.v1.DivergenceRequest divergenceRequest(
            Market market, List<JournalRecord> journal, long lastStep, List<AlgorithmInput> algorithms) {
        pac.simulation.v1.DivergenceRequest.Builder b = pac.simulation.v1.DivergenceRequest.newBuilder()
                .setMarket(market(market))
                .addAllJournal(journal(journal))
                .setLastStep(lastStep);
        for (AlgorithmInput alg : algorithms) {
            b.addAlgorithms(pac.simulation.v1.AlgorithmRef.newBuilder()
                    .setId(alg.id())
                    .setStrategy(pac.simulation.v1.StrategySource.newBuilder()
                            .setSource(alg.source())
                            .addAllParamOverrides(paramValues(alg.paramOverrides()))));
        }
        return b.build();
    }

    private static List<pac.simulation.v1.ParamValue> paramValues(Map<String, Double> paramOverrides) {
        return paramOverrides.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> pac.simulation.v1.ParamValue.newBuilder()
                        .setName(e.getKey())
                        .setValue(e.getValue())
                        .build())
                .toList();
    }

    // ---------- ответ ----------

    static ReplayResult replay(pac.simulation.v1.ReplaySessionResponse r) {
        List<BarData> history = bars(r.getHistoryList());
        List<OrderState> orders = orders(r.getOrdersList());
        List<FillState> fills = fills(r.getFillsList());
        List<CommandOutcome> results = new ArrayList<>(r.getCommandResultsCount());
        for (pac.simulation.v1.CommandResult c : r.getCommandResultsList()) {
            results.add(new CommandOutcome(
                    c.getSeq(), c.getAccepted(), reason(c.getReason()), c.getInvalidField(), c.getOrderIdsList()));
        }
        List<InstrumentChangeData> changes = new ArrayList<>(r.getInstrumentChangesCount());
        for (pac.simulation.v1.InstrumentChange c : r.getInstrumentChangesList()) {
            changes.add(new InstrumentChangeData(
                    c.getInstrumentKey(),
                    c.hasStepPct() ? decimal(c.getStepPct()) : null,
                    c.hasPeriodPct() ? decimal(c.getPeriodPct()) : null));
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
                r.hasBlindView() ? blindView(r.getBlindView()) : null,
                r.hasReport() ? report(r.getReport()) : null,
                r.getCurrentDay(),
                changes);
    }

    static StrategyCheck strategyCheck(pac.simulation.v1.CheckStrategyResponse r) {
        List<StrategyParamDecl> params = new ArrayList<>(r.getParamsCount());
        for (pac.simulation.v1.ParamDecl p : r.getParamsList()) {
            params.add(new StrategyParamDecl(
                    p.getName(),
                    p.getDefaultValue(),
                    p.hasRangeMin() ? p.getRangeMin() : null,
                    p.hasRangeMax() ? p.getRangeMax() : null,
                    p.hasStep() ? p.getStep() : null,
                    p.getInteger()));
        }
        return new StrategyCheck(checkLevel(r.getLookaheadLevel()), diagnostics(r.getDiagnosticsList()), params);
    }

    static StrategyRunOutput strategyRunOutput(pac.simulation.v1.RunResult r) {
        return new StrategyRunOutput(
                strategyRunStatus(r.getStatus()),
                reportLevel(r.getLevel()),
                diagnostics(r.getDiagnosticsList()),
                r.hasReport() ? report(r.getReport()) : null,
                fills(r.getFillsList()),
                orders(r.getOrdersList()),
                journalRecords(r.getJournalList()),
                r.getDigest(),
                r.getLastStep(),
                r.getTotalSteps(),
                bars(r.getHistoryList()));
    }

    private static List<BarData> bars(List<pac.simulation.v1.Bar> source) {
        List<BarData> out = new ArrayList<>(source.size());
        for (pac.simulation.v1.Bar b : source) {
            out.add(bar(b));
        }
        return out;
    }

    private static List<OrderState> orders(List<pac.simulation.v1.Order> source) {
        List<OrderState> out = new ArrayList<>(source.size());
        for (pac.simulation.v1.Order o : source) {
            out.add(order(o));
        }
        return out;
    }

    private static List<FillState> fills(List<pac.simulation.v1.Fill> source) {
        List<FillState> out = new ArrayList<>(source.size());
        for (pac.simulation.v1.Fill f : source) {
            out.add(new FillState(
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
                    f.getStopAndTakeSameBar(),
                    f.getSeq(),
                    decimal(f.getCost()),
                    decimal(f.getCostPct()),
                    decimal(f.getAmountPct())));
        }
        return out;
    }

    private static List<StrategyDiagnostic> diagnostics(List<pac.simulation.v1.Diagnostic> source) {
        List<StrategyDiagnostic> out = new ArrayList<>(source.size());
        for (pac.simulation.v1.Diagnostic d : source) {
            List<DiagnosticFix> fixes = new ArrayList<>(d.getFixCount());
            for (pac.simulation.v1.DiagnosticFix fix : d.getFixList()) {
                List<TextEdit> edits = new ArrayList<>(fix.getEditsCount());
                for (pac.simulation.v1.TextEdit e : fix.getEditsList()) {
                    edits.add(new TextEdit(
                            e.getStartLine(),
                            e.getStartColumn(),
                            e.getEndLine(),
                            e.getEndColumn(),
                            e.getReplacement()));
                }
                fixes.add(new DiagnosticFix(fix.getDescription(), edits));
            }
            out.add(new StrategyDiagnostic(
                    d.getCode(),
                    checkLevel(d.getLevel()),
                    d.getStartLine(),
                    d.getStartColumn(),
                    d.getEndLine(),
                    d.getEndColumn(),
                    d.getMessage(),
                    fixes));
        }
        return out;
    }

    private static List<JournalRecord> journalRecords(List<pac.simulation.v1.JournalEntry> source) {
        List<JournalRecord> out = new ArrayList<>(source.size());
        for (pac.simulation.v1.JournalEntry j : source) {
            Command command = switch (j.getCommandCase()) {
                case PLACE -> placeCommand(j.getPlace());
                case CANCEL -> new Command.Cancel(j.getCancel().getOrderId());
                case REPLACE ->
                    new Command.Replace(
                            j.getReplace().getOrderId(),
                            placeCommand(j.getReplace().getReplacement()));
                case COMMAND_NOT_SET ->
                    throw new IllegalStateException("Воркер вернул запись журнала без команды: " + j.getSeq());
            };
            out.add(new JournalRecord(j.getSeq(), j.getStep(), command, j.getRuleName(), j.getBecause()));
        }
        return out;
    }

    private static Command.Place placeCommand(pac.simulation.v1.PlaceOrder place) {
        OrderParams oco = place.hasOcoWith() && place.getOcoWith().getLots() > 0 ? spec(place.getOcoWith()) : null;
        return new Command.Place(spec(place.getOrder()), oco);
    }

    private static ReportData report(pac.simulation.v1.Report r) {
        pac.simulation.v1.Metrics m = r.getMetrics();
        MetricsData metrics = new MetricsData(
                m.getTotalReturn(),
                m.getGrossReturn(),
                m.hasCagr() ? m.getCagr() : null,
                m.getMaxDrawdown(),
                m.getMaxDrawdownSteps(),
                m.hasVolatility() ? m.getVolatility() : null,
                m.hasSharpe() ? m.getSharpe() : null,
                m.hasSortino() ? m.getSortino() : null,
                m.getTurnover(),
                m.getFills(),
                m.getRoundTrips(),
                m.hasWinRate() ? m.getWinRate() : null,
                m.getRejections(),
                m.getPartialFills(),
                m.getHhiAvg(),
                m.getMaxPositionShare(),
                m.hasBenchmarkReturn() ? m.getBenchmarkReturn() : null,
                m.getTradingDays(),
                m.getStopTakeSameBar(),
                m.getMaxDrawdownDays());
        pac.simulation.v1.CostTotals c = r.getCosts();
        List<RoundTripData> trips = new ArrayList<>(r.getRoundTripsCount());
        for (pac.simulation.v1.RoundTrip t : r.getRoundTripsList()) {
            trips.add(new RoundTripData(
                    t.getInstrumentKey(),
                    t.getEntryStep(),
                    t.getExitStep(),
                    t.getLots(),
                    decimal(t.getEntryPrice()),
                    decimal(t.getExitPrice()),
                    decimal(t.getGrossPnl()),
                    decimal(t.getCosts()),
                    decimal(t.getNetPnl()),
                    t.getSeq()));
        }
        List<UnderwaterPointData> underwater = new ArrayList<>(r.getUnderwaterCount());
        for (pac.simulation.v1.UnderwaterPoint u : r.getUnderwaterList()) {
            underwater.add(new UnderwaterPointData(u.getStep(), decimal(u.getDrawdownPct())));
        }
        List<StrategyFinding> findings = new ArrayList<>(r.getFindingsCount());
        for (pac.simulation.v1.Finding f : r.getFindingsList()) {
            findings.add(new StrategyFinding(f.getCode(), checkLevel(f.getLevel()), f.getMessage(), f.getStep()));
        }
        return new ReportData(
                metrics,
                new CostTotalsData(
                        decimal(c.getCommission()),
                        decimal(c.getExchangeFee()),
                        decimal(c.getSpread()),
                        decimal(c.getSlippage()),
                        decimal(c.getTotal())),
                equity(r.getEquityList()),
                decimal(r.getFinalEquity()),
                equity(r.getBenchmarkEquityList()),
                trips,
                underwater,
                findings,
                reportLevel(r.getLevel()));
    }

    private static List<EquityPointData> equity(List<pac.simulation.v1.EquityPoint> points) {
        List<EquityPointData> out = new ArrayList<>(points.size());
        for (pac.simulation.v1.EquityPoint p : points) {
            out.add(new EquityPointData(p.getStep(), decimal(p.getEquity())));
        }
        return out;
    }

    /** Свечи событий шагов после {@code fromStep}: рублёвые и (если запрошен слепой вид) слепые. */
    static StepBars stepBars(pac.simulation.v1.ReplaySessionResponse r, long fromStep) {
        List<BarData> bars = new ArrayList<>();
        for (pac.simulation.v1.StepEvents e : r.getEventsList()) {
            for (pac.simulation.v1.Bar b : e.getBarsList()) {
                if (b.getStep() > fromStep) {
                    bars.add(bar(b));
                }
            }
        }
        List<BlindBarData> blindBars = new ArrayList<>();
        if (r.hasBlindView()) {
            for (pac.simulation.v1.BlindStepEvents e : r.getBlindView().getEventsList()) {
                for (pac.simulation.v1.BlindBar b : e.getBarsList()) {
                    if (b.getStep() > fromStep) {
                        blindBars.add(blindBar(b));
                    }
                }
            }
        }
        return new StepBars(bars, blindBars);
    }

    private static BarData bar(pac.simulation.v1.Bar b) {
        return new BarData(
                b.getStep(),
                b.getInstrumentKey(),
                b.getTimeUnixMs(),
                b.getTradingDay(),
                decimal(b.getOpen()),
                decimal(b.getHigh()),
                decimal(b.getLow()),
                decimal(b.getClose()),
                b.getVolumeLots(),
                b.getAfterHalt());
    }

    private static BlindBarData blindBar(pac.simulation.v1.BlindBar b) {
        return new BlindBarData(
                b.getStep(),
                b.getInstrumentIndex(),
                b.getTradingDay(),
                decimal(b.getOpen()),
                decimal(b.getHigh()),
                decimal(b.getLow()),
                decimal(b.getClose()),
                decimal(b.getVolumeRel()),
                b.getAfterHalt());
    }

    private static PortfolioState portfolio(pac.simulation.v1.Portfolio p) {
        List<PositionState> positions = new ArrayList<>(p.getPositionsCount());
        for (pac.simulation.v1.Position pos : p.getPositionsList()) {
            positions.add(new PositionState(
                    pos.getInstrumentKey(),
                    pos.getLots(),
                    decimal(pos.getAvgPrice()),
                    decimal(pos.getMarketValue()),
                    decimal(pos.getValuePct()),
                    decimal(pos.getPnlPct())));
        }
        return new PortfolioState(
                decimal(p.getCash()),
                decimal(p.getEquity()),
                positions,
                decimal(p.getInvestedPct()),
                decimal(p.getCostsPct()),
                decimal(p.getPnlPct()),
                decimal(p.getEquityPct()),
                decimal(p.getCashPct()),
                decimal(p.getInvested()),
                decimal(p.getPnl()),
                decimal(p.getCosts()));
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
                o.getClosedStep(),
                o.getRuleName(),
                o.getBecause());
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
                    i.getInstrumentIndex(),
                    i.hasLotValueAtStart() ? decimal(i.getLotValueAtStart()) : null,
                    i.hasChangeStepPct() ? decimal(i.getChangeStepPct()) : null,
                    i.hasChangePeriodPct() ? decimal(i.getChangePeriodPct()) : null));
        }
        List<BlindBarData> history = new ArrayList<>(v.getHistoryCount());
        for (pac.simulation.v1.BlindBar b : v.getHistoryList()) {
            history.add(blindBar(b));
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
                    f.getStopAndTakeSameBar(),
                    decimal(f.getCostPct()),
                    f.getSeq()));
        }
        pac.simulation.v1.BlindPortfolio bp = v.getPortfolio();
        List<BlindPositionData> positions = new ArrayList<>(bp.getPositionsCount());
        for (pac.simulation.v1.BlindPosition p : bp.getPositionsList()) {
            positions.add(new BlindPositionData(
                    p.getInstrumentIndex(),
                    p.getLots(),
                    decimal(p.getAvgPrice()),
                    decimal(p.getMarketValuePct()),
                    decimal(p.getPnlPct())));
        }
        BlindPortfolioData portfolio = new BlindPortfolioData(
                decimal(bp.getCashPct()),
                decimal(bp.getEquityPct()),
                decimal(bp.getPnlPct()),
                positions,
                decimal(bp.getInvestedPct()),
                decimal(bp.getCostsPct()));
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
            case POSITION_CLOSED -> RejectCode.POSITION_CLOSED;
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

    private static CheckLevel checkLevel(pac.simulation.v1.Level level) {
        return switch (level) {
            case OK -> CheckLevel.OK;
            case WARNING -> CheckLevel.WARNING;
            case ERROR -> CheckLevel.ERROR;
            default -> throw new IllegalStateException("Воркер вернул неизвестный уровень диагностики: " + level);
        };
    }

    private static CheckLevel reportLevel(pac.simulation.v1.Level level) {
        if (level == pac.simulation.v1.Level.LEVEL_UNSPECIFIED) {
            return CheckLevel.OK;
        }
        return checkLevel(level);
    }

    private static StrategyRunStatus strategyRunStatus(pac.simulation.v1.RunStatus status) {
        return switch (status) {
            case COMPLETED -> StrategyRunStatus.COMPLETED;
            case BANKRUPT_STOP -> StrategyRunStatus.BANKRUPT_STOP;
            case INVALID -> StrategyRunStatus.INVALID;
            default -> throw new IllegalStateException("Воркер вернул неизвестный статус прогона стратегии: " + status);
        };
    }

    static DivergenceOutput divergenceOutput(pac.simulation.v1.DivergenceResponse r) {
        List<DivergenceData> divergences = new ArrayList<>(r.getDivergencesCount());
        for (pac.simulation.v1.Divergence d : r.getDivergencesList()) {
            divergences.add(new DivergenceData(
                    d.getAlgorithmId(),
                    d.getInstrumentKey(),
                    divergenceKind(d.getType()),
                    d.getHumanStep(),
                    d.getAlgoStep(),
                    d.getHumanLots(),
                    d.getAlgoLots(),
                    d.getHorizonStep(),
                    decimal(d.getCost()),
                    decimal(d.getCostPercent()),
                    d.getReason(),
                    d.getSeq(),
                    optionalKind(d.getHumanOrderType()),
                    optionalKind(d.getAlgoOrderType())));
        }
        List<MissedProfitableTradeData> missed = new ArrayList<>(r.getMissedProfitableCount());
        for (pac.simulation.v1.MissedProfitableTrade m : r.getMissedProfitableList()) {
            missed.add(new MissedProfitableTradeData(
                    m.getInstrumentKey(),
                    m.getEntryStep(),
                    m.getExitStep(),
                    m.getLots(),
                    decimal(m.getNetPnl()),
                    m.getAlgorithmIdsList(),
                    decimal(m.getEntryPrice()),
                    decimal(m.getExitPrice()),
                    m.getSeq(),
                    m.getDivergenceSeqsList()));
        }
        List<ConsensusItemData> consensus = new ArrayList<>(r.getConsensusCount());
        for (pac.simulation.v1.ConsensusItem c : r.getConsensusList()) {
            consensus.add(new ConsensusItemData(
                    c.getInstrumentKey(),
                    c.getStep(),
                    side(c.getSide()),
                    c.getHumanAction(),
                    c.getAgreeing(),
                    c.getTotal(),
                    decimal(c.getMedianCost())));
        }
        List<AlgorithmPortfolioData> portfolios = new ArrayList<>(r.getAlgorithmPortfoliosCount());
        for (pac.simulation.v1.AlgorithmPortfolio p : r.getAlgorithmPortfoliosList()) {
            portfolios.add(new AlgorithmPortfolioData(p.getAlgorithmId(), strategyRunOutput(p.getResult())));
        }
        return new DivergenceOutput(divergences, missed, consensus, portfolios);
    }

    private static DivergenceKind divergenceKind(pac.simulation.v1.DivergenceType type) {
        return switch (type) {
            case EXCESS -> DivergenceKind.EXCESS;
            case MISSED -> DivergenceKind.MISSED;
            case SIZE -> DivergenceKind.SIZE;
            case TIMING -> DivergenceKind.TIMING;
            default -> throw new IllegalStateException("Воркер вернул неизвестный тип расхождения: " + type);
        };
    }

    private static @Nullable OrderKind optionalKind(pac.simulation.v1.OrderType type) {
        return type == pac.simulation.v1.OrderType.ORDER_TYPE_UNSPECIFIED ? null : kind(type);
    }
}
