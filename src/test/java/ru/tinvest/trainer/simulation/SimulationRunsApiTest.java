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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
@TestPropertySource(properties = {"lab.simulation.threads=1", "lab.simulation.progress-interval=0ms"})
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
    void createRunRejectsInvalidStrategyWith422AndLaunchRejection() throws Exception {
        worker.checkLevel = CheckLevel.ERROR;
        mockMvc.perform(withAuth(userA, post("/api/v1/runs"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRunBody("custom", false)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("STRATEGY_INVALID"))
                .andExpect(jsonPath("$.lookaheadLevel").value("ERROR"));

        worker.checkLevel = CheckLevel.OK;
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
        return new UserSession(session, xsrf.getValue());
    }

    private static MockHttpServletRequestBuilder withAuth(UserSession user, MockHttpServletRequestBuilder builder) {
        return builder.session(user.session())
                .cookie(new Cookie("XSRF-TOKEN", user.xsrf()))
                .header("X-XSRF-TOKEN", user.xsrf());
    }

    private record UserSession(MockHttpSession session, String xsrf) {}

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

        void reset() {
            rejectWith = null;
        }

        @Override
        public LaunchResult plan(LaunchRequest request) {
            return plan(request, null, 1L);
        }

        @Override
        public LaunchResult plan(LaunchRequest request, UUID snapshotId, long seed) {
            if (rejectWith != null) {
                return rejectWith;
            }
            Snapshot snap = registeredSnapshot();
            return new LaunchPlan(
                    snap,
                    request.startMode() == StartMode.RANDOM,
                    START,
                    END,
                    20,
                    request.regime(),
                    0.15,
                    0.05,
                    0.12,
                    0.28,
                    request.timeframe(),
                    20,
                    List.of(new PlannedPaper(
                            Papers.id("SBER"), "SBER", "Финансы", 10L, new BigDecimal("0.01"), "Сбербанк")),
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
        volatile StrategyRunStatus runStatus = StrategyRunStatus.COMPLETED;
        volatile boolean failRunWithUnavailable;
        volatile long nextDigest = 123_456L;
        volatile CountDownLatch entered;
        volatile CountDownLatch gate;

        void reset() {
            checkLevel = CheckLevel.OK;
            runStatus = StrategyRunStatus.COMPLETED;
            failRunWithUnavailable = false;
            nextDigest = 123_456L;
            entered = null;
            gate = null;
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
            List<StrategyDiagnostic> diags = checkLevel == CheckLevel.ERROR
                    ? List.of(new StrategyDiagnostic("E001", CheckLevel.ERROR, 1, 1, 1, 5, "Bad DSL", List.of()))
                    : List.of();
            return new StrategyCheck(checkLevel, diags, List.of());
        }

        @Override
        public StrategyRunOutput runStrategy(
                Market market,
                String source,
                Map<String, Double> paramOverrides,
                boolean includeHistory,
                Consumer<RunProgressData> onProgress) {
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
