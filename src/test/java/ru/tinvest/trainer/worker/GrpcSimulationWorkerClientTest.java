package ru.tinvest.trainer.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pac.simulation.v1.PingRequest;
import pac.simulation.v1.PingResponse;
import pac.simulation.v1.SimulationWorkerGrpc;
import ru.tinvest.trainer.api.DependencyUnavailableException;

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
        String name = UUID.randomUUID().toString();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(impl)
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        return new GrpcSimulationWorkerClient(channel, deadline);
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
}
