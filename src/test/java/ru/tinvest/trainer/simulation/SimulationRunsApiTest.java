package ru.tinvest.trainer.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ru.tinvest.trainer.PostgresIntegrationTest.registeredSnapshot;

import jakarta.servlet.http.Cookie;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Sheet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.tinvest.trainer.PostgresIntegrationTest;
import ru.tinvest.trainer.api.DependencyUnavailableException;
import ru.tinvest.trainer.marketdata.LaunchPlan;
import ru.tinvest.trainer.marketdata.LaunchPlanner;
import ru.tinvest.trainer.marketdata.LaunchRejectReason;
import ru.tinvest.trainer.marketdata.LaunchRejection;
import ru.tinvest.trainer.marketdata.LaunchRequest;
import ru.tinvest.trainer.marketdata.LaunchResult;
import ru.tinvest.trainer.marketdata.Papers;
import ru.tinvest.trainer.marketdata.PlannedPaper;
import ru.tinvest.trainer.marketdata.Snapshot;
import ru.tinvest.trainer.marketdata.StartMode;
import ru.tinvest.trainer.marketdata.Timeframe;
import ru.tinvest.trainer.worker.PeriodClassification;
import ru.tinvest.trainer.worker.SimulationWorkerClient;
import ru.tinvest.trainer.worker.WorkerTypes.BarData;
import ru.tinvest.trainer.worker.WorkerTypes.CheckLevel;
import ru.tinvest.trainer.worker.WorkerTypes.CostTotalsData;
import ru.tinvest.trainer.worker.WorkerTypes.EquityPointData;
import ru.tinvest.trainer.worker.WorkerTypes.EstimateResult;
import ru.tinvest.trainer.worker.WorkerTypes.FillState;
import ru.tinvest.trainer.worker.WorkerTypes.JournalRecord;
import ru.tinvest.trainer.worker.WorkerTypes.Market;
import ru.tinvest.trainer.worker.WorkerTypes.MetricsData;
import ru.tinvest.trainer.worker.WorkerTypes.OrderKind;
import ru.tinvest.trainer.worker.WorkerTypes.OrderParams;
import ru.tinvest.trainer.worker.WorkerTypes.OrderPhase;
import ru.tinvest.trainer.worker.WorkerTypes.OrderState;
import ru.tinvest.trainer.worker.WorkerTypes.ReplayResult;
import ru.tinvest.trainer.worker.WorkerTypes.ReportData;
import ru.tinvest.trainer.worker.WorkerTypes.RoundTripData;
import ru.tinvest.trainer.worker.WorkerTypes.RunProgressData;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyCheck;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyDiagnostic;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyFinding;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyRunOutput;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyRunStatus;
import ru.tinvest.trainer.worker.WorkerTypes.Tif;
import ru.tinvest.trainer.worker.WorkerTypes.TradeSide;
import ru.tinvest.trainer.worker.WorkerTypes.UnderwaterPointData;
import ru.tinvest.trainer.workshop.StrategyCatalog;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Сквозные интеграционные тесты очереди прогонов алгоритмов, повтора (UC-18, INV-4), слепого режима (INV-7), SSE
 * событий с {@code Last-Event-ID}, отчётов, выгрузки и истории ({@code /api/v1/runs/...}, {@code /api/v1/history}).
 */
@AutoConfigureMockMvc
@Import(SimulationRunsApiTest.TestWiring.class)
@TestPropertySource(properties = {"lab.simulation.threads=4", "lab.simulation.progress-interval=0ms"})
class SimulationRunsApiTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct horse";
    private static final LocalDate START = LocalDate.of(2024, 1, 10);
    private static final LocalDate END = LocalDate.of(2024, 2, 9);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private FakeRunWorker worker;

    @Autowired
    private StubRunPlanner planner;

    @Autowired
    private RunEvents runEvents;

    @Autowired
    private RunQueue runQueue;

    @Autowired
    private Runs runs;

    @Autowired
    private RunProperties runProperties;

    @Autowired
    private Environment environment;

    private UserSession userA;
    private UserSession userB;

    @BeforeEach
    void setUp() throws Exception {
        worker.reset();
        planner.reset();
        userA = loginNewUser("run-a-" + UUID.randomUUID() + "@example.com");
        userB = loginNewUser("run-b-" + UUID.randomUUID() + "@example.com");
    }

    @Test
    void createRunExecutesInBackgroundAndProducesReportAndExports() throws Exception {
        UUID runId = createRun(userA, false, "custom");
        JsonNode info = awaitFinished(userA, runId);

        assertThat(info.get("status").asString()).isEqualTo("completed");
        assertThat(info.get("algoName").asString()).isEqualTo("Эталон платформы");
        assertThat(info.get("doneSteps").asLong()).isEqualTo(4L);
        assertThat(info.get("totalSteps").asLong()).isEqualTo(4L);
        assertThat(info.get("period").get("startDate").asString()).isEqualTo(START.toString());
        assertThat(info.get("papers")).hasSize(1);
        assertThat(info.get("papers").get(0).get("ticker").asString()).isEqualTo("SBER");

        MvcResult reportResult = mockMvc.perform(withAuth(userA, get("/api/v1/runs/" + runId + "/report")))
                .andReturn();
        assertThat(reportResult.getResponse().getStatus()).isEqualTo(200);
        JsonNode reportRoot = json.readTree(reportResult.getResponse().getContentAsString());
        assertThat(reportRoot.get("status").asString()).isEqualTo("READY");
        JsonNode report = reportRoot.get("report");
        assertThat(report.get("level").asString()).isEqualTo("WARNING");
        assertThat(report.get("findings").get(0).get("code").asString()).isEqualTo("W-RT01");
        assertThat(report.get("orders").get(0).get("ruleName").asString()).isEqualTo("вход");

        MvcResult xlsxResult = mockMvc.perform(
                        withAuth(userA, get("/api/v1/runs/" + runId + "/report/export?format=xlsx")))
                .andExpect(status().isOk())
                .andExpect(header().string(
                                "Content-Disposition", org.hamcrest.Matchers.containsString("run-" + runId + ".xlsx")))
                .andReturn();
        try (ReadableWorkbook wb = new ReadableWorkbook(
                new ByteArrayInputStream(xlsxResult.getResponse().getContentAsByteArray()))) {
            assertThat(wb.getSheets().map(Sheet::getName).toList())
                    .contains("Сводка", "Метрики", "Заявки", "Диагностика");
        }

        MvcResult csvResult = mockMvc.perform(
                        withAuth(userA, get("/api/v1/runs/" + runId + "/report/export?format=csv")))
                .andExpect(status().isOk())
                .andExpect(header().string(
                                "Content-Disposition", org.hamcrest.Matchers.containsString("run-" + runId + ".zip")))
                .andReturn();
        List<String> zipNames = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(
                new ByteArrayInputStream(csvResult.getResponse().getContentAsByteArray()), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                zipNames.add(entry.getName());
            }
        }
        assertThat(zipNames).contains("Сводка.csv", "Заявки.csv");
    }

    @Test
    void createRunPersistsDailyLossLimitPctAndRejectsOutOfRangeValues() throws Exception {
        String validBody = """
                {
                  "strategyVersionId": "%s",
                  "blind": false,
                  "durationDays": 20,
                  "timeframe": "1d",
                  "regime": "calm",
                  "startMode": "custom",
                  "customStartDate": "2024-01-10",
                  "paperCount": null,
                  "paperIds": ["%s"],
                  "initialCapital": "1000000.00",
                  "dailyLossLimitPct": 7.25
                }
                """.formatted(StrategyCatalog.SYSTEM_VERSION_ID, Papers.id("SBER"));
        MvcResult created = mockMvc.perform(withAuth(userA, post("/api/v1/runs"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isCreated())
                .andReturn();
        UUID runId = UUID.fromString(json.readTree(created.getResponse().getContentAsString())
                .get("runId")
                .asString());

        mockMvc.perform(withAuth(userA, get("/api/v1/runs/" + runId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyLossLimitPct").value(7.25))
                .andExpect(jsonPath("$.killSwitchTriggered").isBoolean());

        for (String invalid : List.of("0", "-2.5", "100.01", "1.234")) {
            String badBody = """
                    {
                      "strategyVersionId": "%s",
                      "blind": false,
                      "durationDays": 20,
                      "timeframe": "1d",
                      "regime": "calm",
                      "startMode": "custom",
                      "customStartDate": "2024-01-10",
                      "paperCount": null,
                      "paperIds": ["%s"],
                      "initialCapital": "1000000.00",
                      "dailyLossLimitPct": %s
                    }
                    """.formatted(StrategyCatalog.SYSTEM_VERSION_ID, Papers.id("SBER"), invalid);
            mockMvc.perform(withAuth(userA, post("/api/v1/runs"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(badBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }
    }

    @Test
    void createRunRejectsInvalidStrategyWith422AndLaunchRejection() throws Exception {
        worker.checkLevel = CheckLevel.ERROR;
        mockMvc.perform(withAuth(userA, post("/api/v1/runs"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRunBody("custom", false)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("STRATEGY_INVALID"))
                .andExpect(jsonPath("$.lookaheadLevel").value("ERROR"));

        worker.checkLevel = CheckLevel.OK;
        worker.checkDiagnostics = List.of(
                new StrategyDiagnostic("E-TYPE", CheckLevel.ERROR, 1, 1, 1, 5, "Bad param override", List.of()));
        mockMvc.perform(withAuth(userA, post("/api/v1/runs"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRunBody("custom", false)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("STRATEGY_INVALID"))
                .andExpect(jsonPath("$.lookaheadLevel").value("OK"))
                .andExpect(jsonPath("$.diagnostics[0].code").value("E-TYPE"));

        worker.checkDiagnostics = List.of();
        planner.rejectWith = new LaunchRejection(LaunchRejectReason.TOO_MANY_BARS, "durationDays", 6000, 5000);
        mockMvc.perform(withAuth(userA, post("/api/v1/runs"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRunBody("custom", false)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("TOO_MANY_BARS"))
                .andExpect(jsonPath("$.maxBars").value(5000));
    }

    @Test
    void blindRunHidesPeriodPapersReportAndHistoryUntilFinished() throws Exception {
        worker.entered = new CountDownLatch(1);
        worker.gate = new CountDownLatch(1);
        UUID runId = createRun(userA, true, "random");

        assertThat(worker.entered.await(5, TimeUnit.SECONDS)).isTrue();

        MvcResult infoRes = mockMvc.perform(withAuth(userA, get("/api/v1/runs/" + runId)))
                .andExpect(status().isOk())
                .andReturn();
        String rawInfo = infoRes.getResponse().getContentAsString();
        assertThat(rawInfo).doesNotContain("SBER").doesNotContain(START.toString());
        JsonNode runningInfo = json.readTree(rawInfo);
        assertThat(runningInfo.get("blind").asBoolean()).isTrue();
        assertThat(runningInfo.get("period").isNull()).isTrue();
        assertThat(runningInfo.get("papers").isNull()).isTrue();

        mockMvc.perform(withAuth(userA, get("/api/v1/runs/" + runId + "/report")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_FINISHED"));

        mockMvc.perform(withAuth(userA, get("/api/v1/runs/" + runId + "/report/export?format=xlsx")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_FINISHED"));

        mockMvc.perform(withAuth(userA, post("/api/v1/runs/" + runId + "/repeat")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        MvcResult histRunning = mockMvc.perform(withAuth(userA, get("/api/v1/history?kind=algo_run&blindOnly=true")))
                .andExpect(status().isOk())
                .andReturn();
        String rawHist = histRunning.getResponse().getContentAsString();
        assertThat(rawHist).doesNotContain("SBER").doesNotContain(START.toString());
        JsonNode histItem = json.readTree(rawHist).get("items").get(0);
        assertThat(histItem.get("view").asString()).isEqualTo("BLIND_UNFINISHED");
        assertThat(histItem.get("status").asString()).isEqualTo("running");

        worker.gate.countDown();
        JsonNode finished = awaitFinished(userA, runId);
        assertThat(finished.get("period").get("startDate").asString()).isEqualTo(START.toString());
        assertThat(finished.get("papers").get(0).get("ticker").asString()).isEqualTo("SBER");

        mockMvc.perform(withAuth(userA, get("/api/v1/history?kind=algo_run")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].view").value("REVEALED"))
                .andExpect(jsonPath("$.items[0].netReturnPct").isString());
    }

    @Test
    void repeatRunVerifiesDeterminismForMatchingAndMismatchingDigest() throws Exception {
        UUID originalId = createRun(userA, false, "custom");
        awaitFinished(userA, originalId);

        MvcResult repeat1 = mockMvc.perform(withAuth(userA, post("/api/v1/runs/" + originalId + "/repeat")))
                .andExpect(status().isCreated())
                .andReturn();
        UUID repeatedId1 = UUID.fromString(json.readTree(repeat1.getResponse().getContentAsString())
                .get("runId")
                .asString());
        JsonNode info1 = awaitFinished(userA, repeatedId1);
        assertThat(info1.get("repeatOfRunId").asString()).isEqualTo(originalId.toString());
        assertThat(info1.get("repeatMatch").asBoolean()).isTrue();

        worker.nextDigest = 999_999L;
        MvcResult repeat2 = mockMvc.perform(withAuth(userA, post("/api/v1/runs/" + originalId + "/repeat")))
                .andExpect(status().isCreated())
                .andReturn();
        UUID repeatedId2 = UUID.fromString(json.readTree(repeat2.getResponse().getContentAsString())
                .get("runId")
                .asString());
        JsonNode info2 = awaitFinished(userA, repeatedId2);
        assertThat(info2.get("repeatMatch").asBoolean()).isFalse();
    }

    @Test
    void invalidAndFailedRunsAreExposedInStatusReportEventsAndHistory() throws Exception {
        worker.runStatus = StrategyRunStatus.INVALID;
        UUID invalidId = createRun(userA, false, "custom");
        JsonNode invalidInfo = awaitFinished(userA, invalidId);
        assertThat(invalidInfo.get("status").asString()).isEqualTo("invalid");
        assertThat(invalidInfo.get("diagnostics").get(0).get("code").asString()).isEqualTo("E-RT01");

        mockMvc.perform(withAuth(userA, get("/api/v1/runs/" + invalidId + "/report")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.report.status").value("invalid"))
                .andExpect(jsonPath("$.report.level").value("ERROR"))
                .andExpect(jsonPath("$.report.findings[0].code").value("E-RT01"));

        mockMvc.perform(withAuth(userA, get("/api/v1/history?kind=algo_run&status=invalid")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(invalidId.toString()))
                .andExpect(jsonPath("$.items[0].status").value("invalid"));

        worker.runStatus = StrategyRunStatus.COMPLETED;
        worker.failRunWithUnavailable = true;
        UUID failedId = createRun(userA, false, "custom");
        JsonNode failedInfo = awaitFinished(userA, failedId);
        assertThat(failedInfo.get("status").asString()).isEqualTo("failed");
        assertThat(failedInfo.get("errorCode").asString()).isEqualTo("SERVICE_UNAVAILABLE");

        mockMvc.perform(withAuth(userA, get("/api/v1/history?kind=algo_run&status=failed")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(failedId.toString()));

        mockMvc.perform(withAuth(userA, get("/api/v1/runs/" + failedId + "/report")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.report.status").value("failed"));

        mockMvc.perform(withAuth(userA, get("/api/v1/runs/" + failedId + "/report/export?format=xlsx")))
                .andExpect(status().isOk());
    }

    @Test
    void sseStreamsEventsWithLastEventIdAndIsolatesOwners() throws Exception {
        UUID runId = createRun(userA, false, "custom");
        awaitFinished(userA, runId);

        List<String> typesAll = new CopyOnWriteArrayList<>();
        List<Long> idsAll = new CopyOnWriteArrayList<>();
        CountDownLatch allLatch = new CountDownLatch(3);
        RunEvents.Subscription subAll = runEvents.subscribe(runId, null, new RunEvents.Sink() {
            @Override
            public void send(long eventId, String type, String data) {
                idsAll.add(eventId);
                typesAll.add(type);
                allLatch.countDown();
            }

            @Override
            public void ping() {}

            @Override
            public void abort() {}
        });
        assertThat(allLatch.await(5, TimeUnit.SECONDS)).isTrue();
        subAll.close();
        assertThat(typesAll).contains("run_progress", "run_finished", "report_ready");

        long afterFirst = idsAll.get(0);
        List<Long> caughtUpIds = new CopyOnWriteArrayList<>();
        CountDownLatch catchupLatch = new CountDownLatch(idsAll.size() - 1);
        RunEvents.Subscription subCatchup = runEvents.subscribe(runId, afterFirst, new RunEvents.Sink() {
            @Override
            public void send(long eventId, String type, String data) {
                caughtUpIds.add(eventId);
                catchupLatch.countDown();
            }

            @Override
            public void ping() {}

            @Override
            public void abort() {}
        });
        assertThat(catchupLatch.await(5, TimeUnit.SECONDS)).isTrue();
        subCatchup.close();
        assertThat(caughtUpIds).allMatch(id -> id > afterFirst);

        mockMvc.perform(withAuth(userA, get("/api/v1/runs/" + runId + "/events"))
                        .header("Last-Event-ID", Long.toString(afterFirst)))
                .andExpect(status().isOk());

        // Чужой пользователь B не видит прогон
        mockMvc.perform(withAuth(userB, get("/api/v1/runs/" + runId))).andExpect(status().isNotFound());
        mockMvc.perform(withAuth(userB, post("/api/v1/runs/" + runId + "/repeat")))
                .andExpect(status().isNotFound());
        mockMvc.perform(withAuth(userB, get("/api/v1/runs/" + runId + "/events")))
                .andExpect(status().isNotFound());
        mockMvc.perform(withAuth(userB, get("/api/v1/runs/" + runId + "/report")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_FOUND"));
        mockMvc.perform(withAuth(userB, get("/api/v1/runs/" + runId + "/report/export?format=xlsx")))
                .andExpect(status().isNotFound());
    }

    @Test
    void historyCombinesSessionsAndAlgoRunsInNewestOrder() throws Exception {
        String sessionBody = """
                {
                  "traderKind": "human",
                  "durationDays": 20,
                  "timeframe": "1d",
                  "regime": "calm",
                  "startMode": "custom",
                  "customStartDate": "%s",
                  "paperCount": null,
                  "paperIds": ["%s"],
                  "initialCapital": "1000000.00"
                }
                """.formatted(START, Papers.id("SBER"));
        mockMvc.perform(withAuth(userA, post("/api/v1/sessions"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionBody))
                .andExpect(status().isCreated());

        UUID runId = createRun(userA, false, "custom");
        awaitFinished(userA, runId);

        MvcResult combined = mockMvc.perform(withAuth(userA, get("/api/v1/history")))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode root = json.readTree(combined.getResponse().getContentAsString());
        assertThat(root.get("total").asLong()).isEqualTo(2L);
        List<String> kinds = new ArrayList<>();
        for (JsonNode item : root.get("items")) {
            kinds.add(item.get("kind").asString());
        }
        assertThat(kinds).containsExactlyInAnyOrder("session", "algo_run");
    }

    @Test
    void concurrentSubscribeAndUnsubscribeDoNotDetachNewSubscriber() throws Exception {
        UUID runId = createRun(userA, false, "custom");
        awaitFinished(userA, runId);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 50; round++) {
                RunEvents.Sink noopSink = new RunEvents.Sink() {
                    @Override
                    public void send(long eventId, String type, String data) {}

                    @Override
                    public void ping() {}

                    @Override
                    public void abort() {}
                };
                RunEvents.Subscription existing = runEvents.subscribe(runId, null, noopSink);

                CountDownLatch delivered = new CountDownLatch(1);
                List<String> receivedTypes = new CopyOnWriteArrayList<>();
                RunEvents.Sink survivorSink = new RunEvents.Sink() {
                    @Override
                    public void send(long eventId, String type, String data) {
                        receivedTypes.add(type);
                        delivered.countDown();
                    }

                    @Override
                    public void ping() {}

                    @Override
                    public void abort() {}
                };

                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<?> unsubFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    existing.close();
                    return null;
                });
                Future<RunEvents.Subscription> subFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return runEvents.subscribe(runId, 0L, survivorSink);
                });

                unsubFuture.get(5, TimeUnit.SECONDS);
                RunEvents.Subscription survivor = subFuture.get(5, TimeUnit.SECONDS);
                try {
                    assertThat(runEvents.subscribers(runId)).isEqualTo(1);
                    runEvents.notifySubscribers(runId);
                    assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(receivedTypes).isNotEmpty();
                } finally {
                    survivor.close();
                }
                assertThat(runEvents.subscribers(runId)).isZero();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void restartFailsAbandonedQueuedAndRunningRuns() throws Exception {
        LaunchPlan plan = ((LaunchPlan) planner.plan(new LaunchRequest(
                20,
                Timeframe.D1,
                ru.tinvest.trainer.marketdata.Regime.CALM,
                StartMode.CUSTOM,
                START,
                null,
                List.of(Papers.id("SBER")),
                new BigDecimal("1000000.00"))));

        UUID queuedRunId = runs.createPlanned(
                userA.id(), StrategyCatalog.SYSTEM_VERSION_ID, StartMode.CUSTOM, 20, false, plan, Map.of());
        UUID runningRunId = runs.createPlanned(
                userA.id(), StrategyCatalog.SYSTEM_VERSION_ID, StartMode.CUSTOM, 20, false, plan, Map.of());
        assertThat(runs.markRunning(runningRunId)).isTrue();

        UUID completedRunId = createRun(userA, false, "custom");
        awaitFinished(userA, completedRunId);

        runQueue.failAbandonedAfterRestart();

        for (UUID abandonedId : List.of(queuedRunId, runningRunId)) {
            mockMvc.perform(withAuth(userA, get("/api/v1/runs/" + abandonedId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("failed"))
                    .andExpect(jsonPath("$.errorCode").value("SERVICE_UNAVAILABLE"))
                    .andExpect(jsonPath("$.finishedAt").isNotEmpty());

            List<String> eventTypes = jdbc.sql(
                            "SELECT type FROM simulation.run_event WHERE run_id = :id ORDER BY event_id")
                    .param("id", abandonedId)
                    .query(String.class)
                    .list();
            assertThat(eventTypes).contains("run_failed");
        }

        mockMvc.perform(withAuth(userA, get("/api/v1/runs/" + completedRunId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"));
    }

    @Test
    void defaultLimitsAndTenMinuteRunDeadlineAreConfigured() {
        assertThat(runProperties.maxRunningPerUser()).isEqualTo(3);
        assertThat(runProperties.maxQueuedPerUser()).isEqualTo(20);
        assertThat(environment.getProperty("worker.run-deadline", Duration.class))
                .isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void maxRunningPerUserCapsConcurrentRunsAndStartsNextQueuedAfterFinish() throws Exception {
        worker.entered = new CountDownLatch(4);
        worker.gate = new CountDownLatch(1);

        UUID r1 = createRun(userA, false, "custom");
        UUID r2 = createRun(userA, false, "custom");
        UUID r3 = createRun(userA, false, "custom");
        UUID r4 = createRun(userA, false, "custom");

        // Ждём, пока ровно 3 прогона userA войдут в воркер, затем запускаем userB на 4-м потоке:
        // если бы лимит 3 не работал, r4 занял бы 4-й поток, а с лимитом 4-й поток сразу подхватывает userB.
        long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (worker.inFlight.get() < 3 && System.nanoTime() < waitUntil) {
            Thread.sleep(10);
        }
        assertThat(worker.inFlight.get()).isEqualTo(3);

        UUID b1 = createRun(userB, false, "custom");
        assertThat(worker.entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(worker.inFlight.get()).isEqualTo(4);

        Integer runningA = jdbc.sql("SELECT count(*) FROM simulation.run WHERE user_id = :u AND status = 'running'")
                .param("u", userA.id())
                .query(Integer.class)
                .single();
        Integer queuedA = jdbc.sql("SELECT count(*) FROM simulation.run WHERE user_id = :u AND status = 'queued'")
                .param("u", userA.id())
                .query(Integer.class)
                .single();
        assertThat(runningA).isEqualTo(3);
        assertThat(queuedA).isEqualTo(1);

        worker.gate.countDown();

        for (UUID id : List.of(r1, r2, r3, r4)) {
            assertThat(awaitFinished(userA, id).get("status").asString()).isEqualTo("completed");
        }
        assertThat(awaitFinished(userB, b1).get("status").asString()).isEqualTo("completed");
    }

    @Test
    void maxQueuedPerUserRejectsRunsAndRepeatsWith429AtomicallyUnderConcurrency() throws Exception {
        UUID finishedId = createRun(userA, false, "custom");
        assertThat(awaitFinished(userA, finishedId).get("status").asString()).isEqualTo("completed");

        worker.entered = new CountDownLatch(3);
        worker.gate = new CountDownLatch(1);

        List<UUID> created = new CopyOnWriteArrayList<>();
        // 3 прогона займут все слоты running пользователя userA
        for (int i = 0; i < 3; i++) {
            created.add(createRun(userA, false, "custom"));
        }
        assertThat(worker.entered.await(5, TimeUnit.SECONDS)).isTrue();

        // Ещё 15 в очередь последовательно, затем 10 конкурентных запросов — суммарно queued не должен превысить 20
        for (int i = 0; i < 15; i++) {
            created.add(createRun(userA, false, "custom"));
        }

        int parallelCount = 10;
        List<Integer> statuses = new CopyOnWriteArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(parallelCount)) {
            CyclicBarrier barrier = new CyclicBarrier(parallelCount);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < parallelCount; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    MvcResult res = mockMvc.perform(withAuth(userA, post("/api/v1/runs"))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(createRunBody("custom", false)))
                            .andReturn();
                    int code = res.getResponse().getStatus();
                    statuses.add(code);
                    if (code == 201) {
                        created.add(
                                UUID.fromString(json.readTree(res.getResponse().getContentAsString())
                                        .get("runId")
                                        .asString()));
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        }

        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(5);
        assertThat(statuses.stream().filter(s -> s == 429).count()).isEqualTo(5);

        Integer queuedA = jdbc.sql("SELECT count(*) FROM simulation.run WHERE user_id = :u AND status = 'queued'")
                .param("u", userA.id())
                .query(Integer.class)
                .single();
        assertThat(queuedA).isEqualTo(20);

        mockMvc.perform(withAuth(userA, post("/api/v1/runs"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRunBody("custom", false)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));

        mockMvc.perform(withAuth(userA, post("/api/v1/runs/" + finishedId + "/repeat")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));

        // Другой пользователь не затронут лимитом userA
        UUID userBRun = createRun(userB, false, "custom");

        worker.gate.countDown();
        for (UUID id : created) {
            assertThat(awaitFinished(userA, id).get("status").asString()).isEqualTo("completed");
        }
        assertThat(awaitFinished(userB, userBRun).get("status").asString()).isEqualTo("completed");
    }

    @Test
    void compareRunsCustomWindowCreatesIdenticalRunsAndSupportsClosedWithoutLeakingSource() throws Exception {
        UUID publicAlgo = insertStrategy(userB.id(), "public", "Публичная");
        insertVersion(publicAlgo, 1, "rule \"v1\" when true then buy 1 lots");
        UUID publicLatestVer = insertVersion(publicAlgo, 2, "rule \"v2\" when true then buy 2 lots");

        String closedSecret = "SECRET_CLOSED_DSL_SOURCE_XYZ";
        UUID closedAlgo = insertStrategy(userB.id(), "closed", "Закрытая");
        UUID closedVer = insertVersion(closedAlgo, 1, "rule \"" + closedSecret + "\" when true then buy 1 lots");

        UUID ownHiddenAlgo = insertStrategy(userA.id(), "hidden", "СвояСкрытая");
        UUID ownHiddenVer = insertVersion(ownHiddenAlgo, 1, "rule \"h\" when true then buy 1 lots");

        planner.customPapers = List.of(
                new PlannedPaper(Papers.id("SBER"), "SBER", "Финансы", 10L, new BigDecimal("0.01"), "Сбербанк"),
                new PlannedPaper(Papers.id("GAZP"), "GAZP", "Энергетика", 10L, new BigDecimal("0.01"), "Газпром"),
                new PlannedPaper(Papers.id("LKOH"), "LKOH", "Энергетика", 1L, new BigDecimal("0.50"), "ЛУКОЙЛ"));

        List<UUID> algoIds = List.of(publicAlgo, closedAlgo, ownHiddenAlgo, StrategyCatalog.SYSTEM_STRATEGY_ID);
        MvcResult res = mockMvc.perform(withAuth(userA, post("/api/v1/runs/compare"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(compareRunsBody(algoIds, "custom", false)))
                .andExpect(status().isCreated())
                .andReturn();

        String raw = res.getResponse().getContentAsString();
        assertThat(raw).doesNotContain(closedSecret);
        assertThat(planner.planCalls.get()).isEqualTo(1);

        JsonNode body = json.readTree(raw);
        assertThat(body.get("startDate").asString()).isEqualTo(START.toString());
        assertThat(body.get("endDate").asString()).isEqualTo(END.toString());
        assertThat(body.get("timeframe").asString()).isEqualTo("1d");
        assertThat(body.get("durationDays").asInt()).isEqualTo(20);
        assertThat(body.get("initialCapital").asString()).isEqualTo("1000000.00");
        assertThat(body.get("blind").asBoolean()).isFalse();
        assertThat(body.get("papers")).hasSize(3);

        List<String> responseTickers = new ArrayList<>();
        for (JsonNode p : body.get("papers")) {
            assertThat(p.get("id").isNull()).isFalse();
            responseTickers.add(p.get("ticker").asString());
            assertThat(p.get("name").asString()).isNotBlank();
        }
        assertThat(responseTickers).containsExactlyInAnyOrder("SBER", "GAZP", "LKOH");

        JsonNode runsNode = body.get("runs");
        assertThat(runsNode).hasSize(4);
        assertThat(runsNode.get(0).get("algorithmId").asString()).isEqualTo(publicAlgo.toString());
        assertThat(runsNode.get(0).get("versionId").asString()).isEqualTo(publicLatestVer.toString());
        assertThat(runsNode.get(1).get("algorithmId").asString()).isEqualTo(closedAlgo.toString());
        assertThat(runsNode.get(1).get("versionId").asString()).isEqualTo(closedVer.toString());
        assertThat(runsNode.get(2).get("algorithmId").asString()).isEqualTo(ownHiddenAlgo.toString());
        assertThat(runsNode.get(2).get("versionId").asString()).isEqualTo(ownHiddenVer.toString());
        assertThat(runsNode.get(3).get("algorithmId").asString())
                .isEqualTo(StrategyCatalog.SYSTEM_STRATEGY_ID.toString());
        assertThat(runsNode.get(3).get("versionId").asString()).isEqualTo(StrategyCatalog.SYSTEM_VERSION_ID.toString());

        List<RunRecord> stored = new ArrayList<>();
        for (JsonNode r : runsNode) {
            assertThat(r.get("status").asString()).isEqualTo("queued");
            UUID runId = UUID.fromString(r.get("runId").asString());
            stored.add(runs.findRecord(runId).orElseThrow());
        }

        RunRecord first = stored.getFirst();
        List<String> firstTickers =
                first.papers().stream().map(RunPaperRecord::secid).toList();
        assertThat(firstTickers).isEqualTo(responseTickers);
        for (RunRecord r : stored) {
            assertThat(r.snapshotId()).isEqualTo(first.snapshotId());
            assertThat(r.periodStart()).isEqualTo(first.periodStart());
            assertThat(r.periodEnd()).isEqualTo(first.periodEnd());
            assertThat(r.timeframe()).isEqualTo(first.timeframe());
            assertThat(r.durationDays()).isEqualTo(first.durationDays());
            assertThat(r.initialCapital()).isEqualByComparingTo(first.initialCapital());
            assertThat(r.warmupBars()).isEqualTo(first.warmupBars());
            assertThat(r.costs()).isEqualTo(first.costs());
            assertThat(r.papers()).isEqualTo(first.papers());
        }

        for (RunRecord r : stored) {
            assertThat(awaitFinished(userA, r.id()).get("status").asString()).isEqualTo("completed");
        }
    }

    @Test
    void compareRunsRandomWindowSharesSinglePlanAndMasksBlindResponsePerInv7() throws Exception {
        UUID publicAlgo = insertStrategy(userB.id(), "public", "СлучайнаяПубличная");
        insertVersion(publicAlgo, 1, "rule \"r\" when true then buy 1 lots");

        planner.varyRandomDates = true;
        planner.customPapers = List.of(
                new PlannedPaper(Papers.id("SBER"), "SBER", "Финансы", 10L, new BigDecimal("0.01"), "Сбербанк"),
                new PlannedPaper(Papers.id("GAZP"), "GAZP", "Энергетика", 10L, new BigDecimal("0.01"), "Газпром"));

        MvcResult res = mockMvc.perform(withAuth(userA, post("/api/v1/runs/compare"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(compareRunsBody(
                                List.of(StrategyCatalog.SYSTEM_STRATEGY_ID, publicAlgo), "random", false)))
                .andExpect(status().isCreated())
                .andReturn();

        String raw = res.getResponse().getContentAsString();
        assertThat(planner.planCalls.get()).isEqualTo(1);
        assertThat(raw).doesNotContain("SBER").doesNotContain("GAZP").doesNotContain(START.toString());

        JsonNode body = json.readTree(raw);
        assertThat(body.get("blind").asBoolean()).isTrue();
        assertThat(body.get("startDate").isNull()).isTrue();
        assertThat(body.get("endDate").isNull()).isTrue();
        assertThat(body.get("papers")).hasSize(2);
        assertThat(body.get("papers").get(0).get("id").isNull()).isTrue();
        assertThat(body.get("papers").get(0).get("ticker").asString()).isEmpty();
        assertThat(body.get("papers").get(0).get("name").asString()).isEqualTo("Бумага A");
        assertThat(body.get("papers").get(1).get("name").asString()).isEqualTo("Бумага B");

        UUID run1Id = UUID.fromString(body.get("runs").get(0).get("runId").asString());
        UUID run2Id = UUID.fromString(body.get("runs").get(1).get("runId").asString());
        RunRecord r1 = runs.findRecord(run1Id).orElseThrow();
        RunRecord r2 = runs.findRecord(run2Id).orElseThrow();
        assertThat(r1.blind()).isTrue();
        assertThat(r2.blind()).isTrue();
        assertThat(r2.snapshotId()).isEqualTo(r1.snapshotId());
        assertThat(r2.periodStart()).isEqualTo(r1.periodStart());
        assertThat(r2.periodEnd()).isEqualTo(r1.periodEnd());
        assertThat(r2.papers()).isEqualTo(r1.papers());

        assertThat(awaitFinished(userA, run1Id).get("status").asString()).isEqualTo("completed");
        assertThat(awaitFinished(userA, run2Id).get("status").asString()).isEqualTo("completed");
    }

    @Test
    void compareRunsRejectsInvalidIdsUnauthorizedAlgorithmsAndEnforcesAtomicQueueLimit() throws Exception {
        UUID publicAlgo = insertStrategy(userB.id(), "public", "ПубличнаяДляВалидации");
        insertVersion(publicAlgo, 1, "rule \"p\" when true then buy 1 lots");

        UUID otherHiddenAlgo = insertStrategy(userB.id(), "hidden", "ЧужаяСкрытая");
        insertVersion(otherHiddenAlgo, 1, "rule \"h\" when true then buy 1 lots");

        UUID unpublishedAlgo = insertStrategy(userA.id(), "public", "ЧерновикБезВерсий");

        // < 2 алгоритмов -> 400
        mockMvc.perform(withAuth(userA, post("/api/v1/runs/compare"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(compareRunsBody(List.of(StrategyCatalog.SYSTEM_STRATEGY_ID), "custom", false)))
                .andExpect(status().isBadRequest());

        // > 5 алгоритмов -> 400
        List<UUID> sixIds = List.of(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID());
        mockMvc.perform(withAuth(userA, post("/api/v1/runs/compare"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(compareRunsBody(sixIds, "custom", false)))
                .andExpect(status().isBadRequest());

        // дубликаты -> 400
        mockMvc.perform(withAuth(userA, post("/api/v1/runs/compare"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(compareRunsBody(
                                List.of(StrategyCatalog.SYSTEM_STRATEGY_ID, StrategyCatalog.SYSTEM_STRATEGY_ID),
                                "custom",
                                false)))
                .andExpect(status().isBadRequest());

        // null элемент -> 400
        String withNull = """
                {
                  "algorithmIds": ["%s", null],
                  "blind": false,
                  "durationDays": 20,
                  "timeframe": "1d",
                  "regime": "calm",
                  "startMode": "custom",
                  "customStartDate": "%s",
                  "paperCount": null,
                  "paperIds": ["%s"],
                  "initialCapital": "1000000.00"
                }
                """.formatted(StrategyCatalog.SYSTEM_STRATEGY_ID, START, Papers.id("SBER"));
        mockMvc.perform(withAuth(userA, post("/api/v1/runs/compare"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withNull))
                .andExpect(status().isBadRequest());

        // чужой hidden алгоритм -> 404
        mockMvc.perform(withAuth(userA, post("/api/v1/runs/compare"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(compareRunsBody(
                                List.of(StrategyCatalog.SYSTEM_STRATEGY_ID, otherHiddenAlgo), "custom", false)))
                .andExpect(status().isNotFound());

        // неопубликованный алгоритм -> 404
        mockMvc.perform(withAuth(userA, post("/api/v1/runs/compare"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(compareRunsBody(
                                List.of(StrategyCatalog.SYSTEM_STRATEGY_ID, unpublishedAlgo), "custom", false)))
                .andExpect(status().isNotFound());

        // несуществующий алгоритм -> 404
        mockMvc.perform(withAuth(userA, post("/api/v1/runs/compare"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(compareRunsBody(
                                List.of(StrategyCatalog.SYSTEM_STRATEGY_ID, UUID.randomUUID()), "custom", false)))
                .andExpect(status().isNotFound());

        // Атомарная проверка квоты очереди: 19 в очереди + пакет из 2 (> 20) -> 429 и 0 частично созданных прогонов
        worker.entered = new CountDownLatch(3);
        worker.gate = new CountDownLatch(1);
        List<UUID> created = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            created.add(createRun(userA, false, "custom"));
        }
        assertThat(worker.entered.await(5, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 19; i++) {
            created.add(createRun(userA, false, "custom"));
        }

        mockMvc.perform(withAuth(userA, post("/api/v1/runs/compare"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(compareRunsBody(
                                List.of(StrategyCatalog.SYSTEM_STRATEGY_ID, publicAlgo), "custom", false)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));

        Integer queuedAfter = jdbc.sql("SELECT count(*) FROM simulation.run WHERE user_id = :u AND status = 'queued'")
                .param("u", userA.id())
                .query(Integer.class)
                .single();
        assertThat(queuedAfter).isEqualTo(19);

        worker.gate.countDown();
        for (UUID id : created) {
            assertThat(awaitFinished(userA, id).get("status").asString()).isEqualTo("completed");
        }
    }

    private UUID insertStrategy(UUID ownerId, String visibility, String name) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO workshop.strategy (id, owner_id, author_name, is_system, name, visibility) "
                        + "VALUES (:id, :owner, 'Author', FALSE, :name, :vis)")
                .param("id", id)
                .param("owner", ownerId)
                .param("name", name + "-" + id)
                .param("vis", visibility)
                .update();
        return id;
    }

    private UUID insertVersion(UUID strategyId, int version, String source) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO workshop.strategy_version (id, strategy_id, version, source, params, param_decls) "
                        + "VALUES (:id, :strategy, :ver, :src, '{}'::jsonb, '[]'::jsonb)")
                .param("id", id)
                .param("strategy", strategyId)
                .param("ver", version)
                .param("src", source)
                .update();
        return id;
    }

    private String compareRunsBody(List<UUID> algorithmIds, String startMode, boolean blind) {
        String idsJson =
                algorithmIds.stream().map(id -> "\"" + id + "\"").collect(java.util.stream.Collectors.joining(", "));
        String customDate = "custom".equals(startMode) ? "\"" + START + "\"" : "null";
        String paperCount = "custom".equals(startMode) ? "null" : "2";
        String paperIds = "custom".equals(startMode) ? "[\"" + Papers.id("SBER") + "\"]" : "null";
        return """
                {
                  "algorithmIds": [%s],
                  "blind": %s,
                  "durationDays": 20,
                  "timeframe": "1d",
                  "regime": "calm",
                  "startMode": "%s",
                  "customStartDate": %s,
                  "paperCount": %s,
                  "paperIds": %s,
                  "initialCapital": "1000000.00"
                }
                """.formatted(idsJson, blind, startMode, customDate, paperCount, paperIds);
    }

    private UUID createRun(UserSession user, boolean blind, String startMode) throws Exception {
        MvcResult result = mockMvc.perform(withAuth(user, post("/api/v1/runs"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRunBody(startMode, blind)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString())
                .get("runId")
                .asString());
    }

    private String createRunBody(String startMode, boolean blind) {
        String customDate = "custom".equals(startMode) ? "\"" + START + "\"" : "null";
        String paperCount = "custom".equals(startMode) ? "null" : "1";
        String paperIds = "custom".equals(startMode) ? "[\"" + Papers.id("SBER") + "\"]" : "null";
        return """
                {
                  "strategyVersionId": "%s",
                  "paramOverrides": {"fast": 15},
                  "blind": %s,
                  "durationDays": 20,
                  "timeframe": "1d",
                  "regime": "calm",
                  "startMode": "%s",
                  "customStartDate": %s,
                  "paperCount": %s,
                  "paperIds": %s,
                  "initialCapital": "1000000.00"
                }
                """.formatted(StrategyCatalog.SYSTEM_VERSION_ID, blind, startMode, customDate, paperCount, paperIds);
    }

    private JsonNode awaitFinished(UserSession user, UUID runId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            MvcResult res = mockMvc.perform(withAuth(user, get("/api/v1/runs/" + runId)))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode node = json.readTree(res.getResponse().getContentAsString());
            String st = node.get("status").asString();
            if (!"queued".equals(st) && !"running".equals(st)) {
                return node;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Таймаут ожидания завершения прогона " + runId);
    }

    private UserSession loginNewUser(String email) throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO identity.user_account (id, email, password_hash) VALUES (:id, :e, :h)")
                .param("id", id)
                .param("e", email)
                .param("h", encoder.encode(PASSWORD))
                .update();
        Cookie anonymous =
                mockMvc.perform(get("/api/v1/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(anonymous)
                        .header("X-XSRF-TOKEN", anonymous.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isNoContent())
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        Cookie xsrf = result.getResponse().getCookie("XSRF-TOKEN");
        return new UserSession(id, session, xsrf.getValue());
    }

    private static MockHttpServletRequestBuilder withAuth(UserSession user, MockHttpServletRequestBuilder builder) {
        return builder.session(user.session())
                .cookie(new Cookie("XSRF-TOKEN", user.xsrf()))
                .header("X-XSRF-TOKEN", user.xsrf());
    }

    private record UserSession(UUID id, MockHttpSession session, String xsrf) {}

    @TestConfiguration
    static class TestWiring {

        @Bean
        @Primary
        FakeRunWorker fakeRunWorker() {
            return new FakeRunWorker();
        }

        @Bean
        @Primary
        StubRunPlanner stubRunPlanner() {
            return new StubRunPlanner();
        }
    }

    static final class StubRunPlanner implements LaunchPlanner {

        volatile LaunchRejection rejectWith;
        volatile List<PlannedPaper> customPapers;
        volatile boolean varyRandomDates;
        final AtomicInteger planCalls = new AtomicInteger();

        void reset() {
            rejectWith = null;
            customPapers = null;
            varyRandomDates = false;
            planCalls.set(0);
        }

        @Override
        public LaunchResult plan(LaunchRequest request) {
            return plan(request, null, 1L);
        }

        @Override
        public LaunchResult plan(LaunchRequest request, UUID snapshotId, long seed) {
            int callIndex = planCalls.incrementAndGet();
            if (rejectWith != null) {
                return rejectWith;
            }
            Snapshot snap = registeredSnapshot();
            LocalDate start =
                    varyRandomDates && request.startMode() == StartMode.RANDOM ? START.plusDays(callIndex) : START;
            LocalDate end = varyRandomDates && request.startMode() == StartMode.RANDOM ? END.plusDays(callIndex) : END;
            List<PlannedPaper> papers = customPapers != null
                    ? customPapers
                    : List.of(new PlannedPaper(
                            Papers.id("SBER"), "SBER", "Финансы", 10L, new BigDecimal("0.01"), "Сбербанк"));
            return new LaunchPlan(
                    snap,
                    request.startMode() == StartMode.RANDOM,
                    start,
                    end,
                    20,
                    request.regime(),
                    0.15,
                    0.05,
                    0.12,
                    0.28,
                    request.timeframe(),
                    20,
                    papers,
                    request.initialCapital());
        }

        @Override
        public List<PlannedPaper> papers(UUID snapshotId) {
            return List.of();
        }

        @Override
        public List<Timeframe> availableTimeframes(UUID snapshotId) {
            return List.of(Timeframe.D1);
        }

        @Override
        public Map<Timeframe, Integer> maxDurations(UUID snapshotId) {
            return Map.of(Timeframe.D1, 250);
        }
    }

    static final class FakeRunWorker implements SimulationWorkerClient {

        volatile CheckLevel checkLevel = CheckLevel.OK;
        volatile List<StrategyDiagnostic> checkDiagnostics = List.of();
        volatile StrategyRunStatus runStatus = StrategyRunStatus.COMPLETED;
        volatile boolean failRunWithUnavailable;
        volatile long nextDigest = 123_456L;
        volatile CountDownLatch entered;
        volatile CountDownLatch gate;
        final AtomicInteger inFlight = new AtomicInteger();

        void reset() {
            checkLevel = CheckLevel.OK;
            checkDiagnostics = List.of();
            runStatus = StrategyRunStatus.COMPLETED;
            failRunWithUnavailable = false;
            nextDigest = 123_456L;
            entered = null;
            gate = null;
            inFlight.set(0);
        }

        @Override
        public String ping() {
            return "fake";
        }

        @Override
        public PeriodClassification classifyPeriods(
                String snapshotKey, int windowLength, LocalDate from, LocalDate to, LocalDate startDate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReplayResult replaySession(
                Market market, List<JournalRecord> journal, long upToStep, boolean includeHistory, boolean blind) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReplayResult replayWithReport(Market market, List<JournalRecord> journal, long upToStep) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int tradingDaysAt(Market market, long upToStep) {
            return 0;
        }

        @Override
        public EstimateResult estimateOrder(
                Market market, List<JournalRecord> journal, long upToStep, OrderParams order) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StrategyCheck checkStrategy(String source, Map<String, Double> paramOverrides) {
            List<StrategyDiagnostic> diags = !checkDiagnostics.isEmpty()
                    ? checkDiagnostics
                    : (checkLevel == CheckLevel.ERROR
                            ? List.of(
                                    new StrategyDiagnostic("E001", CheckLevel.ERROR, 1, 1, 1, 5, "Bad DSL", List.of()))
                            : List.of());
            return new StrategyCheck(checkLevel, diags, List.of());
        }

        @Override
        public StrategyRunOutput runStrategy(
                Market market,
                String source,
                Map<String, Double> paramOverrides,
                boolean includeHistory,
                Consumer<RunProgressData> onProgress) {
            inFlight.incrementAndGet();
            try {
                if (entered != null) {
                    entered.countDown();
                }
                if (gate != null) {
                    try {
                        if (!gate.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("gate timeout");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
                if (failRunWithUnavailable) {
                    throw new DependencyUnavailableException("Worker unavailable", null);
                }
            } finally {
                inFlight.decrementAndGet();
            }
            onProgress.accept(new RunProgressData(2L, 4L));
            onProgress.accept(new RunProgressData(4L, 4L));

            List<BarData> history = List.of(
                    new BarData(
                            0L,
                            "SBER",
                            1_704_870_000_000L,
                            1,
                            new BigDecimal("270.00"),
                            new BigDecimal("275.00"),
                            new BigDecimal("269.00"),
                            new BigDecimal("272.00"),
                            1000L,
                            false),
                    new BarData(
                            3L,
                            "SBER",
                            1_705_129_200_000L,
                            4,
                            new BigDecimal("272.00"),
                            new BigDecimal("285.00"),
                            new BigDecimal("271.00"),
                            new BigDecimal("282.00"),
                            1200L,
                            false));

            if (runStatus == StrategyRunStatus.INVALID) {
                ReportData invalidReport = new ReportData(
                        new MetricsData(
                                0.0, 0.0, null, 0.0, 0L, null, null, null, 0.0, 0L, 0L, null, 0L, 0L, 0.0, 0.0, null, 1,
                                0L, 0L),
                        new CostTotalsData(
                                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                        List.of(),
                        new BigDecimal("1000000.00"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(new StrategyFinding("E-RT01", CheckLevel.ERROR, "Нарушение инварианта в рантайме", 2L)),
                        CheckLevel.ERROR);
                return new StrategyRunOutput(
                        StrategyRunStatus.INVALID,
                        CheckLevel.ERROR,
                        List.of(new StrategyDiagnostic(
                                "E-RT01", CheckLevel.ERROR, 5, 1, 5, 10, "Ошибка рантайма", List.of())),
                        invalidReport,
                        List.of(),
                        List.of(),
                        List.of(),
                        nextDigest,
                        2L,
                        4L,
                        history);
            }

            ReportData report = new ReportData(
                    new MetricsData(
                            0.12, 0.13, 0.45, 0.03, 1L, 0.11, 1.8, 2.5, 0.4, 1L, 1L, 1.0, 0L, 0L, 0.5, 0.5, 0.05, 4, 0L,
                            1L),
                    new CostTotalsData(
                            new BigDecimal("30.00"),
                            new BigDecimal("1.00"),
                            new BigDecimal("10.00"),
                            new BigDecimal("5.00"),
                            new BigDecimal("46.00")),
                    List.of(
                            new EquityPointData(0L, new BigDecimal("1000000.00")),
                            new EquityPointData(3L, new BigDecimal("1120000.00"))),
                    new BigDecimal("1120000.00"),
                    List.of(
                            new EquityPointData(0L, new BigDecimal("1000000.00")),
                            new EquityPointData(3L, new BigDecimal("1050000.00"))),
                    List.of(new RoundTripData(
                            "SBER",
                            0L,
                            3L,
                            10L,
                            new BigDecimal("270.00"),
                            new BigDecimal("282.00"),
                            new BigDecimal("1200.00"),
                            new BigDecimal("46.00"),
                            new BigDecimal("1154.00"),
                            1L)),
                    List.of(
                            new UnderwaterPointData(0L, BigDecimal.ZERO),
                            new UnderwaterPointData(3L, new BigDecimal("1.00"))),
                    List.of(new StrategyFinding("W-RT01", CheckLevel.WARNING, "Предупреждение", 2L)),
                    CheckLevel.WARNING);
            OrderState order = new OrderState(
                    1L,
                    new OrderParams("SBER", TradeSide.BUY, OrderKind.MARKET, 10L, null, null, null, Tif.DAY, false),
                    0L,
                    OrderPhase.FILLED,
                    null,
                    "",
                    10L,
                    -1L,
                    null,
                    0L,
                    "вход",
                    "SMA(15) > SMA(50)");
            FillState fill = new FillState(
                    1L,
                    0L,
                    "SBER",
                    TradeSide.BUY,
                    10L,
                    new BigDecimal("270.00"),
                    new BigDecimal("2700.00"),
                    new BigDecimal("8.10"),
                    new BigDecimal("0.27"),
                    new BigDecimal("2.70"),
                    new BigDecimal("1.35"),
                    false,
                    1L,
                    new BigDecimal("12.42"),
                    new BigDecimal("0.01"),
                    new BigDecimal("0.27"));
            return new StrategyRunOutput(
                    runStatus,
                    CheckLevel.WARNING,
                    List.of(),
                    report,
                    List.of(fill),
                    List.of(order),
                    List.of(),
                    nextDigest,
                    3L,
                    4L,
                    history);
        }
    }
}
