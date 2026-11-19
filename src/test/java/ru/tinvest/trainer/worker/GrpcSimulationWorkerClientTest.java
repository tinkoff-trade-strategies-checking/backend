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
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pac.simulation.v1.ClassifyPeriodsRequest;
import pac.simulation.v1.ClassifyPeriodsResponse;
import pac.simulation.v1.PeriodCategory;
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

    @Test
    void classifyPeriodsOtherErrorsAreNotMasked() throws Exception {
        var client = start(
                new SimulationWorkerGrpc.SimulationWorkerImplBase() {
                    @Override
                    public void classifyPeriods(
                            ClassifyPeriodsRequest request, StreamObserver<ClassifyPeriodsResponse> observer) {
                        observer.onError(Status.FAILED_PRECONDITION.asRuntimeException());
                    }
                },
                Duration.ofSeconds(5));

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
}
