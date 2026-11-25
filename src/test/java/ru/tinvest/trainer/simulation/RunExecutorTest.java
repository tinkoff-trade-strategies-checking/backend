package ru.tinvest.trainer.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import ru.tinvest.trainer.api.ErrorCode;
import ru.tinvest.trainer.marketdata.Regime;
import ru.tinvest.trainer.marketdata.StartMode;
import ru.tinvest.trainer.marketdata.Timeframe;
import ru.tinvest.trainer.worker.SimulationWorkerClient;
import ru.tinvest.trainer.worker.WorkerTypes.CheckLevel;
import ru.tinvest.trainer.worker.WorkerTypes.Costs;
import ru.tinvest.trainer.workshop.StrategyCatalog;
import ru.tinvest.trainer.workshop.StrategyVersionView;

class RunExecutorTest {

    @Test
    void logsGrpcStatusCodeWithoutDescriptionOnStatusRuntimeException() {
        Runs runs = mock(Runs.class);
        StrategyCatalog catalog = mock(StrategyCatalog.class);
        SimulationWorkerClient worker = mock(SimulationWorkerClient.class);
        RunProperties props = new RunProperties(
                2, 100, 3, 20, Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofHours(24), 256);

        UUID runId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        RunRecord record = new RunRecord(
                runId,
                UUID.randomUUID(),
                versionId,
                UUID.randomUUID(),
                "moex_2025",
                null,
                true,
                "running",
                60,
                Timeframe.M15,
                Regime.CALM,
                StartMode.RANDOM,
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 3, 1),
                60,
                1000,
                0.01,
                0.05,
                0.01,
                0.02,
                new BigDecimal("1000000"),
                0,
                new Costs(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                Map.of(),
                0L,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                OffsetDateTime.now(ZoneOffset.UTC),
                null,
                List.of(new RunPaperRecord(
                        0, UUID.randomUUID(), "SBER", "FIN", 10L, new BigDecimal("0.01"), "Sberbank")));
        when(runs.findRecord(runId)).thenReturn(Optional.of(record));
        when(catalog.findVersion(versionId))
                .thenReturn(Optional.of(new StrategyVersionView(
                        versionId,
                        UUID.randomUUID(),
                        "Test",
                        "Author",
                        null,
                        "public",
                        false,
                        1,
                        "dsl",
                        "",
                        CheckLevel.OK,
                        "[]",
                        "[]",
                        OffsetDateTime.now(ZoneOffset.UTC))));
        when(worker.runStrategy(any(), anyString(), anyMap(), anyBoolean(), any()))
                .thenThrow(new StatusRuntimeException(
                        Status.RESOURCE_EXHAUSTED.withDescription("SBER 2025-01-01 message exceeds 4194304")));

        Logger logger = (Logger) LoggerFactory.getLogger(RunExecutor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            RunExecutor executor = new RunExecutor(runs, catalog, worker, props);
            executor.executeClaimed(runId);
        } finally {
            logger.detachAppender(appender);
        }

        verify(runs).finishFailed(runId, ErrorCode.INTERNAL_ERROR);
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage).anySatisfy(msg -> {
            assertThat(msg).contains("StatusRuntimeException: RESOURCE_EXHAUSTED");
            assertThat(msg).doesNotContain("SBER");
            assertThat(msg).doesNotContain("2025-01-01");
        });
    }

    @Test
    void failureReasonForNonGrpcExceptionReturnsSimpleClassName() {
        assertThat(RunExecutor.failureReason(new IllegalStateException("SBER secret")))
                .isEqualTo("IllegalStateException");
    }
}
