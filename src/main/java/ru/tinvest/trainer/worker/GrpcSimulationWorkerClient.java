package ru.tinvest.trainer.worker;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
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
        try {
            return stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
                    .ping(PingRequest.getDefaultInstance())
                    .getCoreVersion();
        } catch (StatusRuntimeException e) {
            Status.Code code = e.getStatus().getCode();
            if (code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED) {
                throw new DependencyUnavailableException("Simulation Worker недоступен: " + code, e);
            }
            throw e;
        }
    }
}
