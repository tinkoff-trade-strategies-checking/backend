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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pac.simulation.v1.Decimal;
import pac.simulation.v1.EstimateOrderRequest;
import pac.simulation.v1.EstimateOrderResponse;
import pac.simulation.v1.Reason;
import pac.simulation.v1.ReplaySessionRequest;
import pac.simulation.v1.ReplaySessionResponse;
import pac.simulation.v1.SimulationWorkerGrpc;
import ru.tinvest.trainer.api.DependencyUnavailableException;
import ru.tinvest.trainer.worker.WorkerTypes.BarInterval;
import ru.tinvest.trainer.worker.WorkerTypes.Command;
import ru.tinvest.trainer.worker.WorkerTypes.Costs;
import ru.tinvest.trainer.worker.WorkerTypes.JournalRecord;
import ru.tinvest.trainer.worker.WorkerTypes.Market;
import ru.tinvest.trainer.worker.WorkerTypes.OrderKind;
import ru.tinvest.trainer.worker.WorkerTypes.OrderParams;
import ru.tinvest.trainer.worker.WorkerTypes.OrderPhase;
import ru.tinvest.trainer.worker.WorkerTypes.Paper;
import ru.tinvest.trainer.worker.WorkerTypes.RejectCode;
import ru.tinvest.trainer.worker.WorkerTypes.RunState;
import ru.tinvest.trainer.worker.WorkerTypes.Tif;
import ru.tinvest.trainer.worker.WorkerTypes.TradeSide;

/** Перевод {@code ReplaySession} и {@code EstimateOrder} между Java-записями и protobuf: деньги, журнал, ошибки. */
class GrpcArenaMappingTest {

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

    private SimulationWorkerClient start(SimulationWorkerGrpc.SimulationWorkerImplBase impl) throws Exception {
        String name = UUID.randomUUID().toString();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(impl)
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        return new GrpcSimulationWorkerClient(channel, Duration.ofSeconds(5));
    }

    private static Market market() {
        return new Market(
                "snapshot-1",
                List.of(new Paper("SBER", "SBER", 10, new BigDecimal("0.01"))),
                BarInterval.H1,
                LocalDate.of(2022, 3, 14),
                LocalDate.of(2022, 4, 8),
                new Costs(
                        new BigDecimal("0.003"),
                        new BigDecimal("0.0001"),
                        new BigDecimal("0.001"),
                        new BigDecimal("0.1"),
                        new BigDecimal("0.1")),
                new BigDecimal("500000.00"),
                "IMOEX",
                100);
    }

    private static OrderParams limit(String price, boolean blind) {
        return new OrderParams(
                "SBER", TradeSide.BUY, OrderKind.LIMIT, 3, new BigDecimal(price), null, null, Tif.GTC, blind);
    }

    private static Decimal dec(long units, int nano) {
        return Decimal.newBuilder().setUnits(units).setNano(nano).build();
    }

    @Test
    void unsetOptionalDerivedValuesMapToNull() {
        var response = ReplaySessionResponse.newBuilder()
                .setStatus(pac.simulation.v1.SessionStatus.RUNNING)
                .setCurrentDay(3)
                .addInstrumentChanges(pac.simulation.v1.InstrumentChange.newBuilder()
                        .setInstrumentKey("SBER")
                        .setPeriodPct(dec(4, 560_000_000)))
                .addInstrumentChanges(
                        pac.simulation.v1.InstrumentChange.newBuilder().setInstrumentKey("GAZP"))
                .setBlindView(pac.simulation.v1.BlindSessionView.newBuilder()
                        .addInstruments(pac.simulation.v1.BlindInstrument.newBuilder()
                                .setInstrumentIndex(0)
                                .setChangeStepPct(dec(1, 230_000_000)))
                        .addInstruments(
                                pac.simulation.v1.BlindInstrument.newBuilder().setInstrumentIndex(1)))
                .build();

        var result = WorkerMapper.replay(response);

        assertThat(result.currentDay()).isEqualTo(3);
        var changes = result.instrumentChanges();
        assertThat(changes.get(0).stepPct()).isNull();
        assertThat(changes.get(0).periodPct()).isEqualByComparingTo("4.56");
        assertThat(changes.get(1).stepPct()).isNull();
        assertThat(changes.get(1).periodPct()).isNull();
        var papers = result.blindView().papers();
        assertThat(papers.get(0).changeStepPct()).isEqualByComparingTo("1.23");
        assertThat(papers.get(0).changePeriodPct()).isNull();
        assertThat(papers.get(0).lotValueAtStart()).isNull();
        assertThat(papers.get(1).changeStepPct()).isNull();
    }

    @Test
    void decimalKeepsAllNineDigitsAndSign() {
        assertThat(WorkerMapper.decimal(new BigDecimal("-12.000000001"))).isEqualTo(dec(-12, -1));
        assertThat(WorkerMapper.decimal(dec(-12, -1))).isEqualByComparingTo("-12.000000001");
        assertThat(WorkerMapper.decimal(new BigDecimal("500000.00"))).isEqualTo(dec(500000, 0));
        assertThat(WorkerMapper.decimal(dec(0, 10_000_000))).isEqualByComparingTo("0.01");
        assertThatThrownBy(() -> WorkerMapper.decimal(new BigDecimal("0.0000000001")))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void replayStepsAsksForEventsAfterFromStepWithoutHistoryAndMapsTheirBars() throws Exception {
        AtomicReference<ReplaySessionRequest> seen = new AtomicReference<>();
        var client = start(new SimulationWorkerGrpc.SimulationWorkerImplBase() {
            @Override
            public void replaySession(ReplaySessionRequest request, StreamObserver<ReplaySessionResponse> observer) {
                seen.set(request);
                observer.onNext(ReplaySessionResponse.newBuilder()
                        .setTotalSteps(100)
                        .setStatus(pac.simulation.v1.SessionStatus.RUNNING)
                        .setLastStep(7)
                        .addEvents(events(6))
                        .addEvents(events(7))
                        .setPortfolio(pac.simulation.v1.Portfolio.newBuilder()
                                .setCash(dec(500_000, 0))
                                .setEquity(dec(500_000, 0)))
                        .build());
                observer.onCompleted();
            }
        });

        var result = client.replaySteps(market(), List.of(), 7, 5, false);

        // интервал событий ядра (events_from_step; up_to_step]: левая граница не входит, поэтому передаём сам fromStep
        assertThat(seen.get().getEventsFromStep()).isEqualTo(5);
        assertThat(seen.get().getUpToStep()).isEqualTo(7);
        assertThat(seen.get().getIncludeHistory()).isFalse();
        assertThat(result.steps().bars()).extracting(b -> b.step()).containsExactly(6L, 7L);
        assertThat(result.steps().bars().get(0).tradingDay()).isEqualTo(2);
        assertThat(result.replay().history()).isEmpty();
    }

    private static pac.simulation.v1.StepEvents events(long step) {
        return pac.simulation.v1.StepEvents.newBuilder()
                .setStep(step)
                .addBars(pac.simulation.v1.Bar.newBuilder()
                        .setStep(step)
                        .setInstrumentKey("SBER")
                        .setTimeUnixMs(1_647_241_200_000L + step)
                        .setTradingDay(2)
                        .setOpen(dec(250, 0))
                        .setHigh(dec(251, 0))
                        .setLow(dec(249, 0))
                        .setClose(dec(250, 0))
                        .setVolumeLots(10))
                .build();
    }

    @Test
    void replaySessionMapsJournalAndState() throws Exception {
        AtomicReference<ReplaySessionRequest> seen = new AtomicReference<>();
        var client = start(new SimulationWorkerGrpc.SimulationWorkerImplBase() {
            @Override
            public void replaySession(ReplaySessionRequest request, StreamObserver<ReplaySessionResponse> observer) {
                seen.set(request);
                observer.onNext(ReplaySessionResponse.newBuilder()
                        .setTotalSteps(100)
                        .setStatus(pac.simulation.v1.SessionStatus.RUNNING)
                        .setLastStep(5)
                        .setWarmupSteps(2)
                        .addHistory(pac.simulation.v1.Bar.newBuilder()
                                .setStep(-1)
                                .setInstrumentKey("SBER")
                                .setTimeUnixMs(1_647_241_200_000L)
                                .setTradingDay(0)
                                .setOpen(dec(250, 500_000_000))
                                .setHigh(dec(251, 0))
                                .setLow(dec(249, 0))
                                .setClose(dec(250, 0))
                                .setVolumeLots(1234)
                                .setAfterHalt(true))
                        .setPortfolio(pac.simulation.v1.Portfolio.newBuilder()
                                .setCash(dec(499_000, 0))
                                .setEquity(dec(500_000, 0))
                                .addPositions(pac.simulation.v1.Position.newBuilder()
                                        .setInstrumentKey("SBER")
                                        .setLots(3)
                                        .setAvgPrice(dec(250, 0))
                                        .setMarketValue(dec(7500, 0))))
                        .addOrders(pac.simulation.v1.Order.newBuilder()
                                .setId(4)
                                .setSpec(WorkerMapper.spec(limit("249.95", false)))
                                .setPlacedStep(2)
                                .setStatus(pac.simulation.v1.OrderStatus.PARTIALLY_FILLED)
                                .setReason(Reason.REASON_UNSPECIFIED)
                                .setFilledLots(1)
                                .setOcoSiblingId(-1)
                                .setTrailingStopLevel(dec(240, 0))
                                .setClosedStep(-1))
                        .addFills(pac.simulation.v1.Fill.newBuilder()
                                .setOrderId(4)
                                .setStep(3)
                                .setInstrumentKey("SBER")
                                .setSide(pac.simulation.v1.Side.BUY)
                                .setLots(1)
                                .setPrice(dec(249, 950_000_000))
                                .setValue(dec(2499, 500_000_000))
                                .setCommission(dec(7, 500_000_000))
                                .setExchangeFee(dec(0, 250_000_000))
                                .setSpreadCost(dec(1, 0))
                                .setSlippageCost(dec(0, 500_000_000)))
                        .addCommandResults(pac.simulation.v1.CommandResult.newBuilder()
                                .setSeq(1)
                                .setAccepted(false)
                                .setReason(Reason.INSUFFICIENT_CASH)
                                .setInvalidField("lots")
                                .addOrderIds(8))
                        .setBlindView(pac.simulation.v1.BlindSessionView.newBuilder()
                                .addInstruments(pac.simulation.v1.BlindInstrument.newBuilder()
                                        .setInstrumentIndex(0)
                                        .setLotValueAtStart(dec(5, 0)))
                                .addInstruments(pac.simulation.v1.BlindInstrument.newBuilder()
                                        .setInstrumentIndex(1))
                                .addOrders(pac.simulation.v1.BlindOrder.newBuilder()
                                        .setId(4)
                                        .setInstrumentIndex(0)
                                        .setLimitPrice(dec(99, 900_000_000)))
                                .setPortfolio(pac.simulation.v1.BlindPortfolio.newBuilder()
                                        .setCashPct(dec(99, 800_000_000))
                                        .setEquityPct(dec(100, 0))
                                        .setPnlPct(dec(0, 0))))
                        .build());
                observer.onCompleted();
            }
        });
        List<JournalRecord> journal = List.of(
                new JournalRecord(0, 0, new Command.Place(limit("249.95", false), null)),
                new JournalRecord(1, 1, new Command.Cancel(0)),
                new JournalRecord(
                        2,
                        1,
                        new Command.Replace(
                                0,
                                new Command.Place(
                                        new OrderParams(
                                                "SBER",
                                                TradeSide.SELL,
                                                OrderKind.STOP,
                                                1,
                                                null,
                                                new BigDecimal("240.00"),
                                                null,
                                                Tif.DAY,
                                                false),
                                        new OrderParams(
                                                "SBER",
                                                TradeSide.SELL,
                                                OrderKind.TAKE_PROFIT,
                                                1,
                                                new BigDecimal("260.00"),
                                                null,
                                                null,
                                                Tif.DAY,
                                                false)))));

        var result = client.replaySession(market(), journal, 5, true, true);

        ReplaySessionRequest request = seen.get();
        assertThat(request.getMarket().getSnapshotId()).isEqualTo("snapshot-1");
        assertThat(request.getMarket().getTimeframe()).isEqualTo(pac.simulation.v1.Timeframe.H1);
        assertThat(request.getMarket().getFromDate()).isEqualTo("2022-03-14");
        assertThat(request.getMarket().getToDate()).isEqualTo("2022-04-08");
        assertThat(request.getMarket().getInitialCash()).isEqualTo(dec(500_000, 0));
        assertThat(request.getMarket().getWarmupBars()).isEqualTo(100);
        assertThat(request.getMarket().getBenchmarkTicker()).isEqualTo("IMOEX");
        assertThat(request.getMarket().getCosts().getCommissionRate()).isEqualTo(dec(0, 3_000_000));
        assertThat(request.getMarket().getInstruments(0).getKey()).isEqualTo("SBER");
        assertThat(request.getMarket().getInstruments(0).getLotSize()).isEqualTo(10);
        assertThat(request.getMarket().getInstruments(0).getPriceStep()).isEqualTo(dec(0, 10_000_000));
        assertThat(request.getUpToStep()).isEqualTo(5);
        assertThat(request.getEventsFromStep()).isNegative();
        assertThat(request.getIncludeHistory()).isTrue();
        assertThat(request.getIncludeReport()).isFalse();
        assertThat(request.getBlind()).isTrue();
        assertThat(request.getJournalCount()).isEqualTo(3);
        var place = request.getJournal(0);
        assertThat(place.getSeq()).isZero();
        assertThat(place.getPlace().getOrder().getType()).isEqualTo(pac.simulation.v1.OrderType.LIMIT);
        assertThat(place.getPlace().getOrder().getLimitPrice()).isEqualTo(dec(249, 950_000_000));
        assertThat(place.getPlace().getOrder().getIsBlindPrice()).isFalse();
        assertThat(place.getPlace().hasOcoWith()).isFalse();
        assertThat(request.getJournal(1).getCancel().getOrderId()).isZero();
        var replace = request.getJournal(2).getReplace();
        assertThat(replace.getOrderId()).isZero();
        assertThat(replace.getReplacement().getOrder().getStopPrice()).isEqualTo(dec(240, 0));
        assertThat(replace.getReplacement().getOcoWith().getType()).isEqualTo(pac.simulation.v1.OrderType.TAKE_PROFIT);
        assertThat(replace.getReplacement().getOcoWith().getLimitPrice()).isEqualTo(dec(260, 0));

        assertThat(result.totalSteps()).isEqualTo(100);
        assertThat(result.state()).isEqualTo(RunState.RUNNING);
        assertThat(result.warmupSteps()).isEqualTo(2);
        var bar = result.history().getFirst();
        assertThat(bar.step()).isEqualTo(-1);
        assertThat(bar.open()).isEqualByComparingTo("250.5");
        assertThat(bar.volumeLots()).isEqualTo(1234);
        assertThat(bar.afterHalt()).isTrue();
        assertThat(result.portfolio().cash()).isEqualByComparingTo("499000");
        assertThat(result.portfolio().positions().getFirst().marketValue()).isEqualByComparingTo("7500");
        var order = result.orders().getFirst();
        assertThat(order.id()).isEqualTo(4);
        assertThat(order.phase()).isEqualTo(OrderPhase.PARTIALLY_FILLED);
        assertThat(order.reason()).isNull();
        assertThat(order.spec().limitPrice()).isEqualByComparingTo("249.95");
        assertThat(order.spec().stopPrice()).isNull();
        assertThat(order.trailingStopLevel()).isEqualByComparingTo("240");
        assertThat(order.ocoSiblingId()).isEqualTo(-1);
        assertThat(order.closedStep()).isEqualTo(-1);
        var fill = result.fills().getFirst();
        assertThat(fill.price()).isEqualByComparingTo("249.95");
        assertThat(fill.commission()).isEqualByComparingTo("7.5");
        assertThat(fill.exchangeFee()).isEqualByComparingTo("0.25");
        var outcome = result.commandResults().getFirst();
        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.reason()).isEqualTo(RejectCode.INSUFFICIENT_CASH);
        assertThat(outcome.invalidField()).isEqualTo("lots");
        assertThat(outcome.orderIds()).containsExactly(8L);
        var blind = result.blindView();
        assertThat(blind.papers()).hasSize(2);
        assertThat(blind.papers().get(0).lotValueAtStart()).isEqualByComparingTo("5");
        assertThat(blind.papers().get(1).lotValueAtStart()).isNull();
        assertThat(blind.orders().getFirst().limitPrice()).isEqualByComparingTo("99.9");
        assertThat(blind.orders().getFirst().stopPrice()).isNull();
        assertThat(blind.portfolio().cashPct()).isEqualByComparingTo("99.8");
    }

    @Test
    void replaySessionWithoutBlindViewHasNone() throws Exception {
        var client = start(new SimulationWorkerGrpc.SimulationWorkerImplBase() {
            @Override
            public void replaySession(ReplaySessionRequest request, StreamObserver<ReplaySessionResponse> observer) {
                observer.onNext(ReplaySessionResponse.newBuilder()
                        .setStatus(pac.simulation.v1.SessionStatus.BANKRUPT)
                        .setPortfolio(pac.simulation.v1.Portfolio.newBuilder()
                                .setCash(dec(0, 0))
                                .setEquity(dec(0, 0)))
                        .build());
                observer.onCompleted();
            }
        });

        var result = client.replaySession(market(), List.of(), 0, false, false);

        assertThat(result.blindView()).isNull();
        assertThat(result.state()).isEqualTo(RunState.BANKRUPT);
        assertThat(result.history()).isEmpty();
    }

    @Test
    void estimateOrderMapsBlindPriceAndResolvedOrder() throws Exception {
        AtomicReference<EstimateOrderRequest> seen = new AtomicReference<>();
        var client = start(new SimulationWorkerGrpc.SimulationWorkerImplBase() {
            @Override
            public void estimateOrder(EstimateOrderRequest request, StreamObserver<EstimateOrderResponse> observer) {
                seen.set(request);
                observer.onNext(EstimateOrderResponse.newBuilder()
                        .setCanPlace(false)
                        .setReason(Reason.INSUFFICIENT_CASH)
                        .setInvalidField("")
                        .setReferencePrice(dec(250, 0))
                        .setAmount(dec(7500, 0))
                        .setCommission(dec(22, 500_000_000))
                        .setExchangeFee(dec(0, 750_000_000))
                        .setRequiredCash(dec(7523, 250_000_000))
                        .setAmountPct(dec(1, 500_000_000))
                        .setCommissionPct(dec(0, 10_000_000))
                        .setExchangeFeePct(dec(0, 10_000_000))
                        .setRequiredCashPct(dec(1, 520_000_000))
                        .setResolvedOrder(WorkerMapper.spec(limit("249.95", false)))
                        .build());
                observer.onCompleted();
            }
        });

        var result = client.estimateOrder(market(), List.of(), 7, limit("99.90", true));

        assertThat(seen.get().getUpToStep()).isEqualTo(7);
        assertThat(seen.get().getOrder().getIsBlindPrice()).isTrue();
        assertThat(seen.get().getOrder().getLimitPrice()).isEqualTo(dec(99, 900_000_000));
        assertThat(seen.get().getOrder().getTimeInForce()).isEqualTo(pac.simulation.v1.TimeInForce.GTC);
        assertThat(result.canPlace()).isFalse();
        assertThat(result.reason()).isEqualTo(RejectCode.INSUFFICIENT_CASH);
        assertThat(result.referencePrice()).isEqualByComparingTo("250");
        assertThat(result.commission()).isEqualByComparingTo("22.5");
        assertThat(result.requiredCash()).isEqualByComparingTo("7523.25");
        assertThat(result.requiredCashPct()).isEqualByComparingTo("1.52");
        assertThat(result.resolvedOrder().limitPrice()).isEqualByComparingTo("249.95");
        assertThat(result.resolvedOrder().blindPrice()).isFalse();
    }

    @Test
    void estimateOrderWithoutResolvedOrderHasNone() throws Exception {
        var client = start(new SimulationWorkerGrpc.SimulationWorkerImplBase() {
            @Override
            public void estimateOrder(EstimateOrderRequest request, StreamObserver<EstimateOrderResponse> observer) {
                observer.onNext(
                        EstimateOrderResponse.newBuilder().setCanPlace(true).build());
                observer.onCompleted();
            }
        });

        var result = client.estimateOrder(market(), List.of(), 0, limit("250.00", false));

        assertThat(result.canPlace()).isTrue();
        assertThat(result.reason()).isNull();
        assertThat(result.resolvedOrder()).isNull();
    }

    @Test
    void workerErrorsMapLikeTheOtherCalls() throws Exception {
        for (Status status : new Status[] {Status.UNAVAILABLE, Status.DEADLINE_EXCEEDED, Status.FAILED_PRECONDITION}) {
            var client = start(new SimulationWorkerGrpc.SimulationWorkerImplBase() {
                @Override
                public void replaySession(
                        ReplaySessionRequest request, StreamObserver<ReplaySessionResponse> observer) {
                    observer.onError(status.asRuntimeException());
                }

                @Override
                public void estimateOrder(
                        EstimateOrderRequest request, StreamObserver<EstimateOrderResponse> observer) {
                    observer.onError(status.asRuntimeException());
                }
            });
            assertThatThrownBy(() -> client.replaySession(market(), List.of(), 0, true, false))
                    .isInstanceOf(DependencyUnavailableException.class);
            assertThatThrownBy(() -> client.estimateOrder(market(), List.of(), 0, limit("250.00", false)))
                    .isInstanceOf(DependencyUnavailableException.class);
            tearDown();
        }
    }
}
