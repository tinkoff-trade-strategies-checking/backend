package ru.tinvest.trainer.worker;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import pac.simulation.v1.ClassifyPeriodsRequest;
import pac.simulation.v1.ClassifyPeriodsResponse;
import pac.simulation.v1.EstimateOrderRequest;
import pac.simulation.v1.PingRequest;
import pac.simulation.v1.ReplaySessionRequest;
import pac.simulation.v1.SimulationWorkerGrpc;
import ru.tinvest.trainer.api.DependencyUnavailableException;
import ru.tinvest.trainer.worker.WorkerTypes.EstimateResult;
import ru.tinvest.trainer.worker.WorkerTypes.JournalRecord;
import ru.tinvest.trainer.worker.WorkerTypes.Market;
import ru.tinvest.trainer.worker.WorkerTypes.OrderParams;
import ru.tinvest.trainer.worker.WorkerTypes.ReplayResult;
import ru.tinvest.trainer.worker.WorkerTypes.StepReplay;

/** Реализация {@link SimulationWorkerClient} на {@link ManagedChannel}. */
class GrpcSimulationWorkerClient implements SimulationWorkerClient {

    private final SimulationWorkerGrpc.SimulationWorkerBlockingStub stub;
    private final Duration deadline;

    GrpcSimulationWorkerClient(ManagedChannel channel, Duration deadline) {
        this.stub = SimulationWorkerGrpc.newBlockingStub(channel);
        this.deadline = deadline;
    }

    @Override
    public String ping() {
        return call(() -> stub().ping(PingRequest.getDefaultInstance()).getCoreVersion());
    }

    @Override
    public PeriodClassification classifyPeriods(
            String snapshotKey, int windowLength, LocalDate from, LocalDate to, @Nullable LocalDate startDate) {
        ClassifyPeriodsRequest.Builder request = ClassifyPeriodsRequest.newBuilder()
                .setSnapshotId(snapshotKey)
                .setWindowLength(windowLength)
                .setFromDate(from.toString())
                .setToDate(to.toString());
        if (startDate != null) {
            request.setStartDate(startDate.toString());
        }
        ClassifyPeriodsResponse response;
        try {
            response = call(() -> stub().classifyPeriods(request.build()));
        } catch (StatusRuntimeException e) {
            // «своя дата»: core отвечает INVALID_ARGUMENT, когда окна с таким началом нет (штатный ответ)
            if (startDate != null && e.getStatus().getCode() == Status.Code.INVALID_ARGUMENT) {
                return new PeriodClassification(List.of(), 0, 0);
            }
            throw e;
        }
        List<PeriodWindow> windows = new ArrayList<>(response.getWindowsCount());
        for (pac.simulation.v1.PeriodWindow w : response.getWindowsList()) {
            windows.add(new PeriodWindow(
                    LocalDate.parse(w.getStartDate()),
                    LocalDate.parse(w.getEndDate()),
                    w.getSigma(),
                    w.getMdd(),
                    category(w.getCategory())));
        }
        return new PeriodClassification(windows, response.getP33(), response.getP80());
    }

    @Override
    public ReplayResult replaySession(
            Market market, List<JournalRecord> journal, long upToStep, boolean includeHistory, boolean blind) {
        ReplaySessionRequest request = ReplaySessionRequest.newBuilder()
                .setMarket(WorkerMapper.market(market))
                .addAllJournal(WorkerMapper.journal(journal))
                .setUpToStep(upToStep)
                // события шагов не нужны: снимок собирается из истории и состояния на курсоре
                .setEventsFromStep(-1)
                .setIncludeHistory(includeHistory)
                .setIncludeReport(false)
                .setBlind(blind)
                .build();
        return WorkerMapper.replay(call(() -> stub().replaySession(request)));
    }

    @Override
    public StepReplay replaySteps(
            Market market, List<JournalRecord> journal, long upToStep, long fromStep, boolean blind) {
        ReplaySessionRequest request = ReplaySessionRequest.newBuilder()
                .setMarket(WorkerMapper.market(market))
                .addAllJournal(WorkerMapper.journal(journal))
                .setUpToStep(upToStep)
                // у ядра интервал событий (events_from_step; up_to_step]: левая граница не входит
                .setEventsFromStep(fromStep)
                .setIncludeHistory(false)
                .setIncludeReport(false)
                .setBlind(blind)
                .build();
        pac.simulation.v1.ReplaySessionResponse response = call(() -> stub().replaySession(request));
        return new StepReplay(WorkerMapper.replay(response), WorkerMapper.stepBars(response, fromStep));
    }

    @Override
    public ReplayResult replayWithReport(Market market, List<JournalRecord> journal, long upToStep) {
        ReplaySessionRequest request = ReplaySessionRequest.newBuilder()
                .setMarket(WorkerMapper.market(market))
                .addAllJournal(WorkerMapper.journal(journal))
                .setUpToStep(upToStep)
                .setEventsFromStep(-1)
                .setIncludeHistory(true)
                .setIncludeReport(true)
                .setBlind(false)
                .build();
        return WorkerMapper.replay(call(() -> stub().replaySession(request)));
    }

    @Override
    public int tradingDaysAt(Market market, long upToStep) {
        ReplaySessionRequest request = ReplaySessionRequest.newBuilder()
                .setMarket(WorkerMapper.market(market))
                .setUpToStep(upToStep)
                .setEventsFromStep(-1)
                .setIncludeHistory(false)
                .setIncludeReport(true)
                .setBlind(false)
                .build();
        return call(() -> stub().replaySession(request))
                .getReport()
                .getMetrics()
                .getTradingDays();
    }

    @Override
    public EstimateResult estimateOrder(Market market, List<JournalRecord> journal, long upToStep, OrderParams order) {
        EstimateOrderRequest request = EstimateOrderRequest.newBuilder()
                .setMarket(WorkerMapper.market(market))
                .addAllJournal(WorkerMapper.journal(journal))
                .setUpToStep(upToStep)
                .setOrder(WorkerMapper.spec(order))
                .build();
        return WorkerMapper.estimate(call(() -> stub().estimateOrder(request)));
    }

    private static PeriodCategory category(pac.simulation.v1.PeriodCategory c) {
        return switch (c) {
            case CALM -> PeriodCategory.CALM;
            case MEDIUM -> PeriodCategory.MEDIUM;
            case STRESS -> PeriodCategory.STRESS;
            default -> throw new IllegalStateException("Воркер вернул неизвестную категорию периода: " + c);
        };
    }

    private SimulationWorkerGrpc.SimulationWorkerBlockingStub stub() {
        return stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static <T> T call(Supplier<T> rpc) {
        try {
            return rpc.get();
        } catch (StatusRuntimeException e) {
            Status.Code code = e.getStatus().getCode();
            if (code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED) {
                throw new DependencyUnavailableException("Simulation Worker недоступен: " + code, e);
            }
            if (code == Status.Code.FAILED_PRECONDITION || code == Status.Code.NOT_FOUND) {
                // нет данных снапшота у воркера: для клиента это недоступная зависимость, не ошибка запроса
                throw new DependencyUnavailableException("Simulation Worker не может выполнить запрос: " + code, e);
            }
            throw e;
        }
    }
}
