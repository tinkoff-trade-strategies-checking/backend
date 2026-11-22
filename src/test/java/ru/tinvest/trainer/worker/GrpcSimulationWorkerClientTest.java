package ru.tinvest.trainer.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pac.simulation.v1.AlgorithmPortfolio;
import pac.simulation.v1.CheckStrategyRequest;
import pac.simulation.v1.CheckStrategyResponse;
import pac.simulation.v1.ClassifyPeriodsRequest;
import pac.simulation.v1.ClassifyPeriodsResponse;
import pac.simulation.v1.ConsensusItem;
import pac.simulation.v1.Decimal;
import pac.simulation.v1.Diagnostic;
import pac.simulation.v1.DiagnosticFix;
import pac.simulation.v1.Divergence;
import pac.simulation.v1.DivergenceRequest;
import pac.simulation.v1.DivergenceResponse;
import pac.simulation.v1.DivergenceType;
import pac.simulation.v1.EquityPoint;
import pac.simulation.v1.Finding;
import pac.simulation.v1.Level;
import pac.simulation.v1.MissedProfitableTrade;
import pac.simulation.v1.OrderType;
import pac.simulation.v1.ParamDecl;
import pac.simulation.v1.PeriodCategory;
import pac.simulation.v1.PingRequest;
import pac.simulation.v1.PingResponse;
import pac.simulation.v1.ReplaySessionResponse;
import pac.simulation.v1.Report;
import pac.simulation.v1.RunProgress;
import pac.simulation.v1.RunResult;
import pac.simulation.v1.RunStatus;
import pac.simulation.v1.RunStrategyRequest;
import pac.simulation.v1.RunStrategyUpdate;
import pac.simulation.v1.SimulationWorkerGrpc;
import pac.simulation.v1.TextEdit;
import pac.simulation.v1.UnderwaterPoint;
import ru.tinvest.trainer.api.DependencyUnavailableException;
import ru.tinvest.trainer.worker.WorkerTypes.AlgorithmInput;
import ru.tinvest.trainer.worker.WorkerTypes.BarInterval;
import ru.tinvest.trainer.worker.WorkerTypes.CheckLevel;
import ru.tinvest.trainer.worker.WorkerTypes.Command;
import ru.tinvest.trainer.worker.WorkerTypes.Costs;
import ru.tinvest.trainer.worker.WorkerTypes.DivergenceKind;
import ru.tinvest.trainer.worker.WorkerTypes.JournalRecord;
import ru.tinvest.trainer.worker.WorkerTypes.Market;
import ru.tinvest.trainer.worker.WorkerTypes.OrderKind;
import ru.tinvest.trainer.worker.WorkerTypes.OrderParams;
import ru.tinvest.trainer.worker.WorkerTypes.Paper;
import ru.tinvest.trainer.worker.WorkerTypes.RunProgressData;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyRunStatus;
import ru.tinvest.trainer.worker.WorkerTypes.Tif;
import ru.tinvest.trainer.worker.WorkerTypes.TradeSide;

class GrpcSimulationWorkerClientTest {

    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (channel != null) {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private SimulationWorkerClient start(SimulationWorkerGrpc.SimulationWorkerImplBase impl, Duration deadline)
            throws Exception {
        return start(impl, deadline, Duration.ofMinutes(10));
    }

    private SimulationWorkerClient start(
            SimulationWorkerGrpc.SimulationWorkerImplBase impl, Duration deadline, Duration runDeadline)
            throws Exception {
        String name = UUID.randomUUID().toString();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(impl)
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        return new GrpcSimulationWorkerClient(channel, deadline, runDeadline);
    }

    @Test
    void pingReturnsCoreVersion() throws Exception {
        var client = start(
                new SimulationWorkerGrpc.SimulationWorkerImplBase() {
                    @Override
                    public void ping(PingRequest request, StreamObserver<PingResponse> observer) {
                        observer.onNext(PingResponse.newBuilder()
                                .setCoreVersion("1.2.3")
                                .build());
                        observer.onCompleted();
                    }
                },
                Duration.ofSeconds(5));

        assertThat(client.ping()).isEqualTo("1.2.3");
    }

    @Test
    void unavailableWorkerMapsToDependencyUnavailable() throws Exception {
        var client = start(
                new SimulationWorkerGrpc.SimulationWorkerImplBase() {
                    @Override
                    public void ping(PingRequest request, StreamObserver<PingResponse> observer) {
                        observer.onError(Status.UNAVAILABLE.asRuntimeException());
                    }
                },
                Duration.ofSeconds(5));

        assertThatThrownBy(client::ping).isInstanceOf(DependencyUnavailableException.class);
    }

    @Test
    void stoppedServerMapsToDependencyUnavailable() throws Exception {
        var client = start(new SimulationWorkerGrpc.SimulationWorkerImplBase() {}, Duration.ofSeconds(5));
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);

        assertThatThrownBy(client::ping).isInstanceOf(DependencyUnavailableException.class);
    }

    @Test
    void deadlineExceededMapsToDependencyUnavailable() throws Exception {
        var client = start(
                new SimulationWorkerGrpc.SimulationWorkerImplBase() {
                    @Override
                    public void ping(PingRequest request, StreamObserver<PingResponse> observer) {
                        // не отвечаем: клиент должен упереться в дедлайн
                    }
                },
                Duration.ofMillis(100));

        assertThatThrownBy(client::ping).isInstanceOf(DependencyUnavailableException.class);
    }

    @Test
    void classifyPeriodsMapsRequestAndResponse() throws Exception {
        var seen = new java.util.concurrent.atomic.AtomicReference<ClassifyPeriodsRequest>();
        var client = start(
                new SimulationWorkerGrpc.SimulationWorkerImplBase() {
                    @Override
                    public void classifyPeriods(
                            ClassifyPeriodsRequest request, StreamObserver<ClassifyPeriodsResponse> observer) {
                        seen.set(request);
                        observer.onNext(ClassifyPeriodsResponse.newBuilder()
                                .addWindows(window("2024-01-09", "2024-02-06", 0.12, 0.03, PeriodCategory.CALM))
                                .addWindows(window("2024-01-10", "2024-02-07", 0.5, 0.25, PeriodCategory.STRESS))
                                .addWindows(window("2024-01-11", "2024-02-08", 0.2, 0.1, PeriodCategory.MEDIUM))
                                .setP33(0.15)
                                .setP80(0.35)
                                .build());
                        observer.onCompleted();
                    }
                },
                Duration.ofSeconds(5));

        var all = client.classifyPeriods("snap", 20, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), null);

        assertThat(seen.get().getSnapshotId()).isEqualTo("snap");
        assertThat(seen.get().getWindowLength()).isEqualTo(20);
        assertThat(seen.get().getFromDate()).isEqualTo("2024-01-01");
        assertThat(seen.get().getToDate()).isEqualTo("2024-12-31");
        assertThat(seen.get().hasStartDate()).isFalse();
        assertThat(all.p33()).isEqualTo(0.15);
        assertThat(all.p80()).isEqualTo(0.35);
        assertThat(all.windows())
                .extracting(w -> w.category())
                .containsExactly(
                        ru.tinvest.trainer.worker.PeriodCategory.CALM,
                        ru.tinvest.trainer.worker.PeriodCategory.STRESS,
                        ru.tinvest.trainer.worker.PeriodCategory.MEDIUM);
        assertThat(all.windows().getFirst().startDate()).isEqualTo(LocalDate.of(2024, 1, 9));
        assertThat(all.windows().getFirst().endDate()).isEqualTo(LocalDate.of(2024, 2, 6));
        assertThat(all.windows().getFirst().sigma()).isEqualTo(0.12);
        assertThat(all.windows().getFirst().mdd()).isEqualTo(0.03);

        client.classifyPeriods(
                "snap", 20, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), LocalDate.of(2024, 3, 5));
        assertThat(seen.get().getStartDate()).isEqualTo("2024-03-05");
    }

    @Test
    void classifyPeriodsUnavailableMapsToDependencyUnavailable() throws Exception {
        var client = start(
                new SimulationWorkerGrpc.SimulationWorkerImplBase() {
                    @Override
                    public void classifyPeriods(
                            ClassifyPeriodsRequest request, StreamObserver<ClassifyPeriodsResponse> observer) {
                        observer.onError(Status.UNAVAILABLE.asRuntimeException());
                    }
                },
                Duration.ofSeconds(5));

        assertThatThrownBy(
                        () -> client.classifyPeriods("s", 20, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 2, 1), null))
                .isInstanceOf(DependencyUnavailableException.class);
    }

    private SimulationWorkerClient failingWith(Status status) throws Exception {
        return start(
                new SimulationWorkerGrpc.SimulationWorkerImplBase() {
                    @Override
                    public void classifyPeriods(
                            ClassifyPeriodsRequest request, StreamObserver<ClassifyPeriodsResponse> observer) {
                        observer.onError(status.asRuntimeException());
                    }
                },
                Duration.ofSeconds(5));
    }

    @Test
    void classifyPeriodsWorkerWithoutSnapshotDataIsDependencyUnavailable() throws Exception {
        for (Status status : new Status[] {Status.FAILED_PRECONDITION, Status.NOT_FOUND}) {
            var client = failingWith(status);
            assertThatThrownBy(() ->
                            client.classifyPeriods("s", 20, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 2, 1), null))
                    .isInstanceOf(DependencyUnavailableException.class);
            channel.shutdownNow();
            server.shutdownNow();
        }
    }

    @Test
    void classifyPeriodsInvalidArgumentOnOwnDateMeansNoWindow() throws Exception {
        var client = failingWith(Status.INVALID_ARGUMENT);

        var result = client.classifyPeriods(
                "s", 20, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), LocalDate.of(2024, 12, 30));

        assertThat(result.windows()).isEmpty();
    }

    @Test
    void classifyPeriodsInvalidArgumentWithoutOwnDateIsOurBug() throws Exception {
        var client = failingWith(Status.INVALID_ARGUMENT);

        assertThatThrownBy(
                        () -> client.classifyPeriods("s", 20, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 2, 1), null))
                .isInstanceOf(io.grpc.StatusRuntimeException.class);
    }

    private static pac.simulation.v1.PeriodWindow window(
            String start, String end, double sigma, double mdd, PeriodCategory category) {
        return pac.simulation.v1.PeriodWindow.newBuilder()
                .setStartDate(start)
                .setEndDate(end)
                .setSigma(sigma)
                .setMdd(mdd)
                .setCategory(category)
                .build();
    }

    @Test
    void checkStrategyMapsRequestDiagnosticsFixesAndParams() throws Exception {
        var seen = new AtomicReference<CheckStrategyRequest>();
        var client = start(
                new SimulationWorkerGrpc.SimulationWorkerImplBase() {
                    @Override
                    public void checkStrategy(
                            CheckStrategyRequest request, StreamObserver<CheckStrategyResponse> observer) {
                        seen.set(request);
                        observer.onNext(CheckStrategyResponse.newBuilder()
                                .setLookaheadLevel(Level.WARNING)
                                .addDiagnostics(Diagnostic.newBuilder()
                                        .setCode("W-LA01")
                                        .setLevel(Level.WARNING)
                                        .setStartLine(4)
                                        .setStartColumn(3)
                                        .setEndLine(4)
                                        .setEndColumn(12)
                                        .setMessage("lookahead warning")
                                        .addFix(DiagnosticFix.newBuilder()
                                                .setDescription("shift bar")
                                                .addEdits(TextEdit.newBuilder()
                                                        .setStartLine(4)
                                                        .setStartColumn(3)
                                                        .setEndLine(4)
                                                        .setEndColumn(12)
                                                        .setReplacement("close[1]"))))
                                .addParams(ParamDecl.newBuilder()
                                        .setName("fast")
                                        .setDefaultValue(10.0)
                                        .setRangeMin(2.0)
                                        .setRangeMax(50.0)
                                        .setStep("1")
                                        .setInteger(true))
                                .addParams(ParamDecl.newBuilder()
                                        .setName("weight")
                                        .setDefaultValue(0.5)
                                        .setInteger(false))
                                .build());
                        observer.onCompleted();
                    }
                },
                Duration.ofSeconds(5));

        var result = client.checkStrategy("strategy test {}", Map.of("k", 2.5, "a", 1.0));

        assertThat(seen.get().getSource()).isEqualTo("strategy test {}");
        assertThat(seen.get().getParamOverridesList())
                .extracting(p -> p.getName() + "=" + p.getValue())
                .containsExactly("a=1.0", "k=2.5");
        assertThat(result.lookaheadLevel()).isEqualTo(CheckLevel.WARNING);
        assertThat(result.diagnostics()).hasSize(1);
        var diag = result.diagnostics().getFirst();
        assertThat(diag.code()).isEqualTo("W-LA01");
        assertThat(diag.level()).isEqualTo(CheckLevel.WARNING);
        assertThat(diag.startLine()).isEqualTo(4);
        assertThat(diag.startColumn()).isEqualTo(3);
        assertThat(diag.endLine()).isEqualTo(4);
        assertThat(diag.endColumn()).isEqualTo(12);
        assertThat(diag.message()).isEqualTo("lookahead warning");
        assertThat(diag.fixes()).hasSize(1);
        assertThat(diag.fixes().getFirst().description()).isEqualTo("shift bar");
        assertThat(diag.fixes().getFirst().edits().getFirst().replacement()).isEqualTo("close[1]");
        assertThat(result.params()).hasSize(2);
        assertThat(result.params().get(0).rangeMin()).isEqualTo(2.0);
        assertThat(result.params().get(0).rangeMax()).isEqualTo(50.0);
        assertThat(result.params().get(0).step()).isEqualTo("1");
        assertThat(result.params().get(0).integer()).isTrue();
        assertThat(result.params().get(1).rangeMin()).isNull();
        assertThat(result.params().get(1).rangeMax()).isNull();
        assertThat(result.params().get(1).step()).isNull();
        assertThat(result.params().get(1).integer()).isFalse();
    }

    @Test
    void checkStrategyUnavailableMapsToDependencyUnavailable() throws Exception {
        var client = start(
                new SimulationWorkerGrpc.SimulationWorkerImplBase() {
                    @Override
                    public void checkStrategy(
                            CheckStrategyRequest request, StreamObserver<CheckStrategyResponse> observer) {
                        observer.onError(Status.UNAVAILABLE.asRuntimeException());
                    }
                },
                Duration.ofSeconds(5));

        assertThatThrownBy(() -> client.checkStrategy("strategy x {}", Map.of()))
                .isInstanceOf(DependencyUnavailableException.class);
    }

    @Test
    void runStrategyStreamsProgressAndMapsResultRuleNamesAndFindings() throws Exception {
        var seen = new AtomicReference<RunStrategyRequest>();
        var client = start(
                new SimulationWorkerGrpc.SimulationWorkerImplBase() {
                    @Override
                    public void runStrategy(RunStrategyRequest request, StreamObserver<RunStrategyUpdate> observer) {
                        seen.set(request);
                        observer.onNext(RunStrategyUpdate.newBuilder()
                                .setProgress(RunProgress.newBuilder()
                                        .setDoneSteps(10)
                                        .setTotalSteps(100))
                                .build());
                        observer.onNext(RunStrategyUpdate.newBuilder()
                                .setProgress(RunProgress.newBuilder()
                                        .setDoneSteps(100)
                                        .setTotalSteps(100))
                                .build());
                        observer.onNext(RunStrategyUpdate.newBuilder()
                                .setResult(RunResult.newBuilder()
                                        .setStatus(RunStatus.COMPLETED)
                                        .setLevel(Level.WARNING)
                                        .setDigest(987654321012345L)
                                        .setLastStep(100)
                                        .setTotalSteps(100)
                                        .setReport(pac.simulation.v1.Report.newBuilder()
                                                .setMetrics(pac.simulation.v1.Metrics.newBuilder()
                                                        .setTotalReturn(0.15)
                                                        .setGrossReturn(0.16)
                                                        .setMaxDrawdown(0.05)
                                                        .setMaxDrawdownSteps(3)
                                                        .setTurnover(1.2)
                                                        .setFills(1)
                                                        .setRoundTrips(0)
                                                        .setRejections(0)
                                                        .setPartialFills(0)
                                                        .setHhiAvg(1.0)
                                                        .setMaxPositionShare(1.0)
                                                        .setTradingDays(5)
                                                        .setStopTakeSameBar(0)
                                                        .setMaxDrawdownDays(1))
                                                .setCosts(pac.simulation.v1.CostTotals.newBuilder()
                                                        .setCommission(dec(10, 0))
                                                        .setExchangeFee(dec(1, 0))
                                                        .setSpread(dec(2, 0))
                                                        .setSlippage(dec(0, 0))
                                                        .setTotal(dec(13, 0)))
                                                .setFinalEquity(dec(575_000, 0))
                                                .setLevel(Level.WARNING)
                                                .addFindings(Finding.newBuilder()
                                                        .setCode("W-RT01")
                                                        .setLevel(Level.WARNING)
                                                        .setMessage("high turnover")
                                                        .setStep(42)))
                                        .addOrders(pac.simulation.v1.Order.newBuilder()
                                                .setId(0)
                                                .setSpec(orderSpec())
                                                .setPlacedStep(1)
                                                .setStatus(pac.simulation.v1.OrderStatus.FILLED)
                                                .setOcoSiblingId(-1)
                                                .setClosedStep(2)
                                                .setRuleName("entry_rule")
                                                .setBecause("sma_cross"))
                                        .addJournal(pac.simulation.v1.JournalEntry.newBuilder()
                                                .setSeq(0)
                                                .setStep(1)
                                                .setPlace(pac.simulation.v1.PlaceOrder.newBuilder()
                                                        .setOrder(orderSpec()))
                                                .setRuleName("entry_rule")
                                                .setBecause("sma_cross"))
                                        .addJournal(pac.simulation.v1.JournalEntry.newBuilder()
                                                .setSeq(1)
                                                .setStep(2)
                                                .setCancel(pac.simulation.v1.CancelOrder.newBuilder()
                                                        .setOrderId(0))
                                                .setRuleName("exit_rule")
                                                .setBecause("stop_loss"))
                                        .addHistory(pac.simulation.v1.Bar.newBuilder()
                                                .setStep(0)
                                                .setInstrumentKey("SBER")
                                                .setTimeUnixMs(1_700_000_000_000L)
                                                .setTradingDay(1)
                                                .setOpen(dec(250, 0))
                                                .setHigh(dec(252, 0))
                                                .setLow(dec(249, 0))
                                                .setClose(dec(251, 0))
                                                .setVolumeLots(100)))
                                .build());
                        observer.onCompleted();
                    }
                },
                Duration.ofSeconds(5),
                Duration.ofSeconds(30));

        List<RunProgressData> progress = new ArrayList<>();
        var output = client.runStrategy(market(), "strategy s {}", Map.of("n", 20.0), true, progress::add);

        assertThat(seen.get().getMarket().getSnapshotId()).isEqualTo("snap-1");
        assertThat(seen.get().getStrategy().getSource()).isEqualTo("strategy s {}");
        assertThat(seen.get().getStrategy().getParamOverrides(0).getName()).isEqualTo("n");
        assertThat(seen.get().getIncludeHistory()).isTrue();
        assertThat(progress).containsExactly(new RunProgressData(10, 100), new RunProgressData(100, 100));
        assertThat(output.status()).isEqualTo(StrategyRunStatus.COMPLETED);
        assertThat(output.level()).isEqualTo(CheckLevel.WARNING);
        assertThat(output.digest()).isEqualTo(987654321012345L);
        assertThat(output.lastStep()).isEqualTo(100);
        assertThat(output.totalSteps()).isEqualTo(100);
        assertThat(output.report()).isNotNull();
        assertThat(output.report().level()).isEqualTo(CheckLevel.WARNING);
        assertThat(output.report().findings()).hasSize(1);
        assertThat(output.report().findings().getFirst().code()).isEqualTo("W-RT01");
        assertThat(output.report().findings().getFirst().step()).isEqualTo(42);
        assertThat(output.orders()).hasSize(1);
        assertThat(output.orders().getFirst().ruleName()).isEqualTo("entry_rule");
        assertThat(output.orders().getFirst().because()).isEqualTo("sma_cross");
        assertThat(output.journal()).hasSize(2);
        assertThat(output.journal().get(0).ruleName()).isEqualTo("entry_rule");
        assertThat(output.journal().get(0).because()).isEqualTo("sma_cross");
        assertThat(output.journal().get(1).command()).isInstanceOf(Command.Cancel.class);
        assertThat(output.journal().get(1).ruleName()).isEqualTo("exit_rule");
        assertThat(output.history()).hasSize(1);
    }

    @Test
    void runStrategyInvalidStatusMapsDiagnosticsWithoutReport() throws Exception {
        var client = start(
                new SimulationWorkerGrpc.SimulationWorkerImplBase() {
                    @Override
                    public void runStrategy(RunStrategyRequest request, StreamObserver<RunStrategyUpdate> observer) {
                        observer.onNext(RunStrategyUpdate.newBuilder()
                                .setResult(RunResult.newBuilder()
                                        .setStatus(RunStatus.INVALID)
                                        .setLevel(Level.ERROR)
                                        .addDiagnostics(Diagnostic.newBuilder()
                                                .setCode("E-RT01")
                                                .setLevel(Level.ERROR)
                                                .setStartLine(0)
                                                .setStartColumn(0)
                                                .setEndLine(0)
                                                .setEndColumn(0)
                                                .setMessage("runtime integrity error")))
                                .build());
                        observer.onCompleted();
                    }
                },
                Duration.ofSeconds(5));

        var output = client.runStrategy(market(), "broken", Map.of(), false, p -> {});

        assertThat(output.status()).isEqualTo(StrategyRunStatus.INVALID);
        assertThat(output.level()).isEqualTo(CheckLevel.ERROR);
        assertThat(output.report()).isNull();
        assertThat(output.diagnostics()).hasSize(1);
        assertThat(output.diagnostics().getFirst().code()).isEqualTo("E-RT01");
    }

    @Test
    void runStrategyUsesSeparateRunDeadlineAndMapsTimeout() throws Exception {
        var fastUnaryClient = start(
                new SimulationWorkerGrpc.SimulationWorkerImplBase() {
                    @Override
                    public void runStrategy(RunStrategyRequest request, StreamObserver<RunStrategyUpdate> observer) {
                        // симулируем задержку длиннее unary deadline (20ms), но короче runDeadline (2s)
                        try {
                            Thread.sleep(60);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        observer.onNext(RunStrategyUpdate.newBuilder()
                                .setResult(RunResult.newBuilder()
                                        .setStatus(RunStatus.BANKRUPT_STOP)
                                        .setLevel(Level.OK))
                                .build());
                        observer.onCompleted();
                    }
                },
                Duration.ofMillis(20),
                Duration.ofSeconds(2));

        var output = fastUnaryClient.runStrategy(market(), "strategy s {}", Map.of(), false, p -> {});
        assertThat(output.status()).isEqualTo(StrategyRunStatus.BANKRUPT_STOP);
        tearDown();

        var timingOutClient = start(
                new SimulationWorkerGrpc.SimulationWorkerImplBase() {
                    @Override
                    public void runStrategy(RunStrategyRequest request, StreamObserver<RunStrategyUpdate> observer) {
                        // не отвечаем: должен сработать runDeadline
                    }
                },
                Duration.ofSeconds(5),
                Duration.ofMillis(100));

        assertThatThrownBy(() -> timingOutClient.runStrategy(market(), "strategy s {}", Map.of(), false, p -> {}))
                .isInstanceOf(DependencyUnavailableException.class);
    }

    @Test
    void runStrategyStreamErrorMapsToDependencyUnavailable() throws Exception {
        var client = start(
                new SimulationWorkerGrpc.SimulationWorkerImplBase() {
                    @Override
                    public void runStrategy(RunStrategyRequest request, StreamObserver<RunStrategyUpdate> observer) {
                        observer.onNext(RunStrategyUpdate.newBuilder()
                                .setProgress(
                                        RunProgress.newBuilder().setDoneSteps(1).setTotalSteps(10))
                                .build());
                        observer.onError(Status.UNAVAILABLE.asRuntimeException());
                    }
                },
                Duration.ofSeconds(5));

        assertThatThrownBy(() -> client.runStrategy(market(), "strategy s {}", Map.of(), false, p -> {}))
                .isInstanceOf(DependencyUnavailableException.class);
    }

    @Test
    void replayAndJournalMapperPreserveRuleNameBecauseFindingsAndReportLevel() {
        var outbound = new JournalRecord(
                0,
                1,
                new Command.Place(
                        new OrderParams("SBER", TradeSide.BUY, OrderKind.MARKET, 1, null, null, null, Tif.DAY, false),
                        null),
                "buy_dip",
                "price_below_ma");
        var protoEntry = WorkerMapper.entry(outbound);
        assertThat(protoEntry.getRuleName()).isEqualTo("buy_dip");
        assertThat(protoEntry.getBecause()).isEqualTo("price_below_ma");

        var response = ReplaySessionResponse.newBuilder()
                .setStatus(pac.simulation.v1.SessionStatus.FINISHED_END)
                .setPortfolio(pac.simulation.v1.Portfolio.newBuilder()
                        .setCash(dec(500_000, 0))
                        .setEquity(dec(500_000, 0)))
                .addOrders(pac.simulation.v1.Order.newBuilder()
                        .setId(0)
                        .setSpec(orderSpec())
                        .setStatus(pac.simulation.v1.OrderStatus.FILLED)
                        .setOcoSiblingId(-1)
                        .setClosedStep(1)
                        .setRuleName("buy_dip")
                        .setBecause("price_below_ma"))
                .setReport(pac.simulation.v1.Report.newBuilder()
                        .setMetrics(pac.simulation.v1.Metrics.newBuilder().setTradingDays(1))
                        .setCosts(pac.simulation.v1.CostTotals.newBuilder()
                                .setCommission(dec(0, 0))
                                .setExchangeFee(dec(0, 0))
                                .setSpread(dec(0, 0))
                                .setSlippage(dec(0, 0))
                                .setTotal(dec(0, 0)))
                        .setFinalEquity(dec(500_000, 0))
                        .setLevel(Level.LEVEL_UNSPECIFIED)
                        .addFindings(Finding.newBuilder()
                                .setCode("W-RT02")
                                .setLevel(Level.WARNING)
                                .setMessage("flat run")
                                .setStep(-1)))
                .build();

        var mapped = WorkerMapper.replay(response);
        assertThat(mapped.orders().getFirst().ruleName()).isEqualTo("buy_dip");
        assertThat(mapped.orders().getFirst().because()).isEqualTo("price_below_ma");
        assertThat(mapped.report()).isNotNull();
        assertThat(mapped.report().level()).isEqualTo(CheckLevel.OK);
        assertThat(mapped.report().findings()).hasSize(1);
        assertThat(mapped.report().findings().getFirst().code()).isEqualTo("W-RT02");
        assertThat(mapped.report().findings().getFirst().step()).isEqualTo(-1);
    }

    @Test
    void analyzeDivergencesMapsRequestResponseAndUsesRunDeadline() throws Exception {
        var seen = new AtomicReference<DivergenceRequest>();
        var client = start(
                new SimulationWorkerGrpc.SimulationWorkerImplBase() {
                    @Override
                    public void analyzeDivergences(
                            DivergenceRequest request, StreamObserver<DivergenceResponse> observer) {
                        try {
                            Thread.sleep(60);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        seen.set(request);
                        observer.onNext(DivergenceResponse.newBuilder()
                                .addDivergences(Divergence.newBuilder()
                                        .setAlgorithmId("alg-1")
                                        .setInstrumentKey("SBER")
                                        .setType(DivergenceType.EXCESS)
                                        .setHumanStep(2)
                                        .setAlgoStep(-1)
                                        .setHumanLots(3)
                                        .setAlgoLots(0)
                                        .setHorizonStep(5)
                                        .setCost(dec(150, 0))
                                        .setCostPercent(dec(0, 30_000_000))
                                        .setReason("алгоритм здесь не действовал бы")
                                        .setSeq(0)
                                        .setHumanOrderType(OrderType.MARKET)
                                        .setAlgoOrderType(OrderType.ORDER_TYPE_UNSPECIFIED))
                                .addMissedProfitable(MissedProfitableTrade.newBuilder()
                                        .setInstrumentKey("SBER")
                                        .setEntryStep(2)
                                        .setExitStep(5)
                                        .setLots(3)
                                        .setNetPnl(dec(240, 0))
                                        .addAlgorithmIds("alg-1")
                                        .setEntryPrice(dec(200, 0))
                                        .setExitPrice(dec(210, 0))
                                        .setSeq(0)
                                        .addDivergenceSeqs(0))
                                .addConsensus(ConsensusItem.newBuilder()
                                        .setInstrumentKey("SBER")
                                        .setStep(2)
                                        .setSide(pac.simulation.v1.Side.BUY)
                                        .setHumanAction(true)
                                        .setAgreeing(0)
                                        .setTotal(1)
                                        .setMedianCost(dec(150, 0)))
                                .addAlgorithmPortfolios(AlgorithmPortfolio.newBuilder()
                                        .setAlgorithmId("alg-1")
                                        .setResult(RunResult.newBuilder()
                                                .setStatus(RunStatus.COMPLETED)
                                                .setLevel(Level.OK)
                                                .setLastStep(5)
                                                .setTotalSteps(10)
                                                .setReport(Report.newBuilder()
                                                        .setMetrics(pac.simulation.v1.Metrics.newBuilder()
                                                                .setTotalReturn(0.05)
                                                                .setGrossReturn(0.06)
                                                                .setMaxDrawdown(0.01)
                                                                .setTradingDays(2))
                                                        .setCosts(pac.simulation.v1.CostTotals.newBuilder()
                                                                .setCommission(dec(5, 0))
                                                                .setExchangeFee(dec(1, 0))
                                                                .setSpread(dec(0, 0))
                                                                .setSlippage(dec(0, 0))
                                                                .setTotal(dec(6, 0)))
                                                        .setFinalEquity(dec(525_000, 0))
                                                        .addEquity(EquityPoint.newBuilder()
                                                                .setStep(0)
                                                                .setEquity(dec(500_000, 0)))
                                                        .addUnderwater(UnderwaterPoint.newBuilder()
                                                                .setStep(0)
                                                                .setDrawdownPct(dec(0, 0))))))
                                .build());
                        observer.onCompleted();
                    }
                },
                Duration.ofMillis(20),
                Duration.ofSeconds(2));

        var journal = List.of(new JournalRecord(
                0,
                2,
                new Command.Place(
                        new OrderParams("SBER", TradeSide.BUY, OrderKind.MARKET, 3, null, null, null, Tif.DAY, false),
                        null)));
        var output = client.analyzeDivergences(
                market(), journal, 5, List.of(new AlgorithmInput("alg-1", "strategy s {}", Map.of("size_pct", 95.0))));

        assertThat(seen.get().getLastStep()).isEqualTo(5);
        assertThat(seen.get().getJournalCount()).isEqualTo(1);
        assertThat(seen.get().getAlgorithms(0).getId()).isEqualTo("alg-1");
        assertThat(seen.get()
                        .getAlgorithms(0)
                        .getStrategy()
                        .getParamOverrides(0)
                        .getName())
                .isEqualTo("size_pct");
        assertThat(output.divergences()).hasSize(1);
        var div = output.divergences().getFirst();
        assertThat(div.type()).isEqualTo(DivergenceKind.EXCESS);
        assertThat(div.humanOrderType()).isEqualTo(OrderKind.MARKET);
        assertThat(div.algoOrderType()).isNull();
        assertThat(div.cost()).isEqualByComparingTo("150.00");
        assertThat(div.costPercent()).isEqualByComparingTo("0.03");
        assertThat(output.missedProfitable()).hasSize(1);
        assertThat(output.missedProfitable().getFirst().divergenceSeqs()).containsExactly(0L);
        assertThat(output.consensus()).hasSize(1);
        assertThat(output.algorithmPortfolios()).hasSize(1);
        assertThat(output.algorithmPortfolios().getFirst().result().report().finalEquity())
                .isEqualByComparingTo("525000.00");
    }

    @Test
    void analyzeDivergencesTimeoutMapsToDependencyUnavailable() throws Exception {
        var client = start(
                new SimulationWorkerGrpc.SimulationWorkerImplBase() {
                    @Override
                    public void analyzeDivergences(
                            DivergenceRequest request, StreamObserver<DivergenceResponse> observer) {
                        // не отвечаем: должен сработать runDeadline
                    }
                },
                Duration.ofSeconds(5),
                Duration.ofMillis(100));

        assertThatThrownBy(() -> client.analyzeDivergences(
                        market(), List.of(), 5, List.of(new AlgorithmInput("alg-1", "strategy s {}", Map.of()))))
                .isInstanceOf(DependencyUnavailableException.class);
    }

    private static Market market() {
        return new Market(
                "snap-1",
                List.of(new Paper("SBER", "SBER", 10, new BigDecimal("0.01"))),
                BarInterval.H1,
                LocalDate.of(2024, 1, 10),
                LocalDate.of(2024, 2, 10),
                new Costs(
                        new BigDecimal("0.003"),
                        new BigDecimal("0.0001"),
                        new BigDecimal("0.001"),
                        new BigDecimal("0.1"),
                        new BigDecimal("0.1")),
                new BigDecimal("500000.00"),
                "IMOEX",
                200);
    }

    private static pac.simulation.v1.OrderSpec orderSpec() {
        return pac.simulation.v1.OrderSpec.newBuilder()
                .setInstrumentKey("SBER")
                .setSide(pac.simulation.v1.Side.BUY)
                .setType(pac.simulation.v1.OrderType.MARKET)
                .setLots(1)
                .setTimeInForce(pac.simulation.v1.TimeInForce.DAY)
                .build();
    }

    private static Decimal dec(long units, int nano) {
        return Decimal.newBuilder().setUnits(units).setNano(nano).build();
    }
}
