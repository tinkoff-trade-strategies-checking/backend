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
import pac.simulation.v1.PingRequest;
import pac.simulation.v1.SimulationWorkerGrpc;
import ru.tinvest.trainer.api.DependencyUnavailableException;

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
        ClassifyPeriodsResponse response = call(() -> stub().classifyPeriods(request.build()));
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
            throw e;
        }
    }
}
