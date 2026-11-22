package ru.tinvest.trainer.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static ru.tinvest.trainer.PostgresIntegrationTest.registeredSnapshot;

import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.tinvest.trainer.PostgresIntegrationTest;
import ru.tinvest.trainer.analysis.ReportBuilder;
import ru.tinvest.trainer.marketdata.LaunchPlan;
import ru.tinvest.trainer.marketdata.Papers;
import ru.tinvest.trainer.marketdata.PlannedPaper;
import ru.tinvest.trainer.marketdata.Regime;
import ru.tinvest.trainer.marketdata.Snapshot;
import ru.tinvest.trainer.marketdata.StartMode;
import ru.tinvest.trainer.marketdata.Timeframe;
import ru.tinvest.trainer.worker.WorkerTypes.AlgorithmInput;
import ru.tinvest.trainer.workshop.StrategyCatalog;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Постфактум-сравнение завершённой сессии с 1–5 алгоритмами (UC-9 п. 1а/1б): настоящие PostgreSQL, Spring Security и
 * миграции, воркер — {@link FakeWorker}. Проверяет 2 и 5 алгоритмов (INV-3), неизменяемость исходной сессии и отчёта
 * (INV-5), передачу {@code size_pct} только при наличии параметра в {@code param_decls}, отказы 400/404/409/503 и
 * защиту слепых данных (INV-7).
 */
@AutoConfigureMockMvc
@Import(ReportHistoryReplayApiTest.TestWiring.class)
class SessionCompareApiTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct horse";
    private static final List<String> SECIDS = List.of("SBER", "GAZP", "LKOH");
    private static final long LAST_STEP = 6;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private Sessions sessions;

    @Autowired
    private FakeWorker worker;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ReportBuilder builder;

    private UUID userId;
    private MockHttpSession httpSession;
    private String xsrf;

    @BeforeEach
    void setUp() throws Exception {
        worker.reset();
        userId = createUser("u" + UUID.randomUUID() + "@example.com");
        login(emailOf(userId));
    }

    @Test
    void comparesFinishedSessionWithTwoAndFiveAlgorithmsPreservingOrderAndInvariants() throws Exception {
        UUID sessionId = finishedSessionWithOrder(false);
        assertThat(builder.buildAndStore(sessionId)).isTrue();
        String storedReportBefore = storedReportJson(sessionId);
        UUID backgroundVersionBefore = backgroundAlgoVersionId(sessionId);
        String backgroundParamsBefore = backgroundAlgoParamsJson(sessionId);
        worker.reset();

        UUID noParamAlgo = insertStrategy(userId, "public", "БезПараметров");
        UUID noParamVer = insertVersion(noParamAlgo, 1, "rule \"a\" when true then buy 1 lots", "[]");

        UUID sizedAlgo = insertStrategy(userId, "public", "СПараметром");
        insertVersion(sizedAlgo, 1, "param size_pct = 95\nrule \"v1\" when true then buy size_pct % cash", "[]");
        UUID sizedLatestVer = insertVersion(
                sizedAlgo,
                2,
                "param size_pct = 95\nrule \"v2\" when true then buy size_pct % cash",
                "[{\"name\":\"size_pct\",\"defaultValue\":95.0,\"rangeMin\":null,\"rangeMax\":null,\"step\":null,\"integer\":false}]");

        UUID publicAlgo =
                insertStrategy(createUser("other" + UUID.randomUUID() + "@example.com"), "public", "Публичная");
        insertVersion(publicAlgo, 1, "rule \"p\" when true then buy 1 lots", "[]");

        UUID ownHiddenAlgo = insertStrategy(userId, "hidden", "СкрытаяСвоя");
        insertVersion(ownHiddenAlgo, 1, "rule \"h\" when true then buy 1 lots", "[]");

        // 1. Сравнение с 2 алгоритмами
        MvcResult twoResult = compare(sessionId, List.of(noParamAlgo, StrategyCatalog.SYSTEM_STRATEGY_ID));
        assertThat(twoResult.getResponse().getStatus()).isEqualTo(200);
        JsonNode twoBody = json.readTree(twoResult.getResponse().getContentAsString());

        assertThat(twoBody.get("sessionId").asString()).isEqualTo(sessionId.toString());
        assertThat(twoBody.get("humanMetrics").get("initialCapital").asString()).isEqualTo("500000.00");
        assertThat(twoBody.get("humanEquityCurve")).hasSize((int) LAST_STEP + 1);
        assertThat(twoBody.get("humanEquityCurve").get(0).get("humanCapital").asString())
                .isEqualTo("500000.00");

        JsonNode twoAlgos = twoBody.get("algorithms");
        assertThat(twoAlgos).hasSize(2);
        assertThat(twoAlgos.get(0).get("algorithmId").asString()).isEqualTo(noParamAlgo.toString());
        assertThat(twoAlgos.get(0).get("strategyVersionId").asString()).isEqualTo(noParamVer.toString());
        assertThat(twoAlgos.get(0).get("algorithmName").asString()).isEqualTo("БезПараметров-" + noParamAlgo);
        assertThat(twoAlgos.get(0).get("version").asInt()).isEqualTo(1);
        assertThat(twoAlgos.get(1).get("algorithmId").asString())
                .isEqualTo(StrategyCatalog.SYSTEM_STRATEGY_ID.toString());
        assertThat(twoAlgos.get(1).get("strategyVersionId").asString())
                .isEqualTo(StrategyCatalog.SYSTEM_VERSION_ID.toString());
        assertThat(twoAlgos.get(1).get("divergences")).hasSize(2);
        assertThat(twoAlgos.get(1).get("reverseCheckTrades")).hasSize(1);
        assertThat(twoAlgos.get(1).get("equityCurve")).hasSize((int) LAST_STEP + 1);
        assertThat(twoAlgos.get(1).get("underwaterCurve")).hasSize((int) LAST_STEP + 1);

        JsonNode twoConsensus = twoBody.get("consensus");
        assertThat(twoConsensus).isNotEmpty();
        assertThat(twoConsensus.get(0).get("total").asInt()).isEqualTo(2);
        assertThat(twoConsensus.get(0).get("day").asInt()).isPositive();
        assertThat(twoConsensus.get(0).get("date").asString()).endsWith("Z");

        // size_pct передаётся только стратегии с объявленным параметром size_pct
        assertThat(worker.divergenceRequests).hasSize(1);
        List<AlgorithmInput> twoInputs = worker.divergenceRequests.getFirst().algorithms();
        assertThat(twoInputs).hasSize(2);
        assertThat(twoInputs.get(0).paramOverrides()).isEmpty();
        assertThat(twoInputs.get(1).paramOverrides()).containsEntry("size_pct", 31.0);

        // 2. Сравнение сразу с 5 алгоритмами (INV-3, порядок запроса сохранён, своя hidden доступна)
        MvcResult fiveResult = compare(
                sessionId,
                List.of(noParamAlgo, sizedAlgo, publicAlgo, ownHiddenAlgo, StrategyCatalog.SYSTEM_STRATEGY_ID));
        assertThat(fiveResult.getResponse().getStatus()).isEqualTo(200);
        JsonNode fiveBody = json.readTree(fiveResult.getResponse().getContentAsString());

        JsonNode fiveAlgos = fiveBody.get("algorithms");
        assertThat(fiveAlgos).hasSize(5);
        assertThat(fiveAlgos.get(0).get("algorithmId").asString()).isEqualTo(noParamAlgo.toString());
        assertThat(fiveAlgos.get(1).get("algorithmId").asString()).isEqualTo(sizedAlgo.toString());
        assertThat(fiveAlgos.get(1).get("strategyVersionId").asString()).isEqualTo(sizedLatestVer.toString());
        assertThat(fiveAlgos.get(1).get("version").asInt()).isEqualTo(2);
        assertThat(fiveAlgos.get(2).get("algorithmId").asString()).isEqualTo(publicAlgo.toString());
        assertThat(fiveAlgos.get(3).get("algorithmId").asString()).isEqualTo(ownHiddenAlgo.toString());
        assertThat(fiveAlgos.get(4).get("algorithmId").asString())
                .isEqualTo(StrategyCatalog.SYSTEM_STRATEGY_ID.toString());

        for (JsonNode algNode : fiveAlgos) {
            assertThat(algNode.get("divergences")).hasSize(2);
            assertThat(algNode.get("reverseCheckTrades")).hasSize(1);
            assertThat(algNode.get("divergenceSummary").get("totalErrorCost").asString())
                    .isEqualTo("100.00");
        }
        assertThat(fiveBody.get("consensus").get(0).get("total").asInt()).isEqualTo(5);

        List<AlgorithmInput> fiveInputs = worker.divergenceRequests.getLast().algorithms();
        assertThat(fiveInputs).hasSize(5);
        assertThat(fiveInputs.get(0).paramOverrides()).isEmpty();
        assertThat(fiveInputs.get(1).paramOverrides()).containsEntry("size_pct", 31.0);
        assertThat(fiveInputs.get(2).paramOverrides()).isEmpty();
        assertThat(fiveInputs.get(3).paramOverrides()).isEmpty();
        assertThat(fiveInputs.get(4).paramOverrides()).containsEntry("size_pct", 31.0);
        assertThat(worker.divergenceRequests.getLast().lastStep()).isEqualTo(LAST_STEP);

        // INV-5: исходная сессия и её сохранённый отчёт не мутируются
        assertThat(storedReportJson(sessionId)).isEqualTo(storedReportBefore);
        assertThat(backgroundAlgoVersionId(sessionId)).isEqualTo(backgroundVersionBefore);
        assertThat(backgroundAlgoParamsJson(sessionId)).isEqualTo(backgroundParamsBefore);
    }

    @Test
    void rejectsInvalidRequestsWithBadRequest() throws Exception {
        UUID sessionId = finishedSessionWithOrder(false);
        UUID a = StrategyCatalog.SYSTEM_STRATEGY_ID;
        UUID b = insertStrategy(userId, "public", "B");
        insertVersion(b, 1, "rule \"b\" when true then buy 1 lots", "[]");
        UUID c = insertStrategy(userId, "public", "C");
        insertVersion(c, 1, "rule \"c\" when true then buy 1 lots", "[]");
        UUID d = insertStrategy(userId, "public", "D");
        insertVersion(d, 1, "rule \"d\" when true then buy 1 lots", "[]");
        UUID e = insertStrategy(userId, "public", "E");
        insertVersion(e, 1, "rule \"e\" when true then buy 1 lots", "[]");
        UUID f = insertStrategy(userId, "public", "F");
        insertVersion(f, 1, "rule \"f\" when true then buy 1 lots", "[]");

        // 0 алгоритмов -> 400
        MvcResult empty = compare(sessionId, List.of());
        assertProblem(empty, 400, "BAD_REQUEST");

        // отсутствует поле algorithmIds -> 400
        MvcResult missing = compareRaw(sessionId, "{}");
        assertProblem(missing, 400, "BAD_REQUEST");

        // 6 алгоритмов (> 5) -> 400
        MvcResult tooMany = compare(sessionId, List.of(a, b, c, d, e, f));
        assertProblem(tooMany, 400, "BAD_REQUEST");

        // дубликаты -> 400
        MvcResult dup = compare(sessionId, List.of(a, a));
        assertProblem(dup, 400, "BAD_REQUEST");

        assertThat(worker.divergenceRequests).isEmpty();
    }

    @Test
    void returnsNotFoundForMissingOrInaccessibleSessionOrAlgorithm() throws Exception {
        UUID sessionId = finishedSessionWithOrder(false);
        UUID otherUser = createUser("stranger" + UUID.randomUUID() + "@example.com");

        UUID foreignHiddenAlgo = insertStrategy(otherUser, "hidden", "ЧужаяСкрытая");
        insertVersion(foreignHiddenAlgo, 1, "rule \"x\" when true then buy 1 lots", "[]");

        UUID draftOnlyAlgo = insertStrategy(userId, "public", "Черновик");
        jdbc.sql(
                        "INSERT INTO workshop.strategy_draft (strategy_id, source) VALUES (:id, 'rule \"d\" when true then buy 1 lots')")
                .param("id", draftOnlyAlgo)
                .update();

        assertProblem(compare(sessionId, List.of(foreignHiddenAlgo)), 404, "NOT_FOUND");
        assertProblem(compare(sessionId, List.of(draftOnlyAlgo)), 404, "NOT_FOUND");
        assertProblem(compare(sessionId, List.of(UUID.randomUUID())), 404, "NOT_FOUND");

        // чужая завершённая сессия и несуществующая сессия -> 404
        UUID foreignSession = sessions.create(
                otherUser, StartMode.CUSTOM, 20, plan(false, Regime.CALM, SECIDS, LocalDate.of(2022, 3, 14)));
        finish(foreignSession, "completed");
        assertProblem(compare(foreignSession, List.of(StrategyCatalog.SYSTEM_STRATEGY_ID)), 404, "NOT_FOUND");
        assertProblem(compare(UUID.randomUUID(), List.of(StrategyCatalog.SYSTEM_STRATEGY_ID)), 404, "NOT_FOUND");
    }

    @Test
    void returnsNotFinishedForActiveSessionWithoutLeakingBlindData() throws Exception {
        UUID blindRunning = newSession(true);
        MvcResult res = compare(blindRunning, List.of(StrategyCatalog.SYSTEM_STRATEGY_ID));
        assertProblem(res, 409, "NOT_FINISHED");
        String body = res.getResponse().getContentAsString();
        assertThat(body).doesNotContain("SBER", "GAZP", "LKOH", "2022-03-14", "Сектор");
    }

    @Test
    void returnsServiceUnavailableWhenWorkerIsDown() throws Exception {
        UUID sessionId = finishedSessionWithOrder(false);
        worker.down = true;
        MvcResult res = compare(sessionId, List.of(StrategyCatalog.SYSTEM_STRATEGY_ID));
        assertProblem(res, 503, "SERVICE_UNAVAILABLE");
    }

    private MvcResult compare(UUID sessionId, List<UUID> algorithmIds) throws Exception {
        return compareRaw(sessionId, json.writeValueAsString(Map.of("algorithmIds", algorithmIds)));
    }

    private MvcResult compareRaw(UUID sessionId, String body) throws Exception {
        return mockMvc.perform(withAuth(post("/api/v1/sessions/" + sessionId + "/report/compare"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private void assertProblem(MvcResult result, int expectedStatus, String expectedCode) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(expectedStatus);
        JsonNode node = json.readTree(result.getResponse().getContentAsString());
        assertThat(node.get("code").asString()).isEqualTo(expectedCode);
    }

    private UUID finishedSessionWithOrder(boolean blind) throws Exception {
        UUID id = newSession(blind);
        MvcResult placed = mockMvc.perform(withAuth(post("/api/v1/sessions/" + id + "/orders"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seenStep\":0,\"paperId\":\"" + Papers.id("SBER")
                                + "\",\"side\":\"buy\",\"type\":\"market\",\"lots\":2,\"tif\":\"gtc\"}"))
                .andReturn();
        assertThat(placed.getResponse().getStatus()).isEqualTo(200);
        setStep(id, LAST_STEP);
        finish(id, "completed");
        return id;
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

    private UUID insertVersion(UUID strategyId, int version, String source, String paramDeclsJson) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO workshop.strategy_version (id, strategy_id, version, source, params, param_decls) "
                        + "VALUES (:id, :strategy, :ver, :src, '{}'::jsonb, CAST(:decls AS jsonb))")
                .param("id", id)
                .param("strategy", strategyId)
                .param("ver", version)
                .param("src", source)
                .param("decls", paramDeclsJson)
                .update();
        return id;
    }

    private String storedReportJson(UUID sessionId) {
        return jdbc.sql("SELECT report::text FROM analysis.session_report WHERE session_id = :id")
                .param("id", sessionId)
                .query(String.class)
                .single();
    }

    private UUID backgroundAlgoVersionId(UUID sessionId) {
        return jdbc.sql("SELECT algo_version_id FROM sessions.session WHERE id = :id")
                .param("id", sessionId)
                .query(UUID.class)
                .single();
    }

    private String backgroundAlgoParamsJson(UUID sessionId) {
        return jdbc.sql("SELECT algo_params::text FROM sessions.session WHERE id = :id")
                .param("id", sessionId)
                .query(String.class)
                .single();
    }

    private void finish(UUID id, String status) {
        jdbc.sql("UPDATE sessions.session SET status = :s WHERE id = :id")
                .param("s", status)
                .param("id", id)
                .update();
    }

    private void setStep(UUID id, long step) {
        jdbc.sql("UPDATE sessions.session SET current_step = :s WHERE id = :id")
                .param("s", step)
                .param("id", id)
                .update();
    }

    private UUID newSession(boolean blind) {
        return sessions.create(
                userId,
                blind ? StartMode.RANDOM : StartMode.CUSTOM,
                20,
                plan(blind, Regime.CALM, SECIDS, LocalDate.of(2022, 3, 14)));
    }

    private static LaunchPlan plan(boolean blind, Regime regime, List<String> secids, LocalDate start) {
        Snapshot snapshot = registeredSnapshot();
        List<PlannedPaper> list = new ArrayList<>();
        for (int i = 0; i < secids.size(); i++) {
            list.add(new PlannedPaper(
                    Papers.id(secids.get(i)), secids.get(i), "Сектор" + i, 10 + i, new BigDecimal("0.01")));
        }
        return new LaunchPlan(
                snapshot,
                blind,
                start,
                start.plusDays(26),
                20,
                regime,
                0.123456,
                0.0777,
                0.15,
                0.35,
                Timeframe.D1,
                20,
                list,
                new BigDecimal("500000.00"));
    }

    private UUID createUser(String address) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO identity.user_account (id, email, password_hash) VALUES (:id, :e, :h)")
                .param("id", id)
                .param("e", address)
                .param("h", encoder.encode(PASSWORD))
                .update();
        return id;
    }

    private String emailOf(UUID id) {
        return jdbc.sql("SELECT email FROM identity.user_account WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private void login(String address) throws Exception {
        Cookie anonymous =
                mockMvc.perform(get("/api/v1/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(anonymous)
                        .header("X-XSRF-TOKEN", anonymous.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + address + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        httpSession = (MockHttpSession) result.getRequest().getSession(false);
        xsrf = result.getResponse().getCookie("XSRF-TOKEN").getValue();
    }

    private MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder builder) {
        return builder.session(httpSession)
                .cookie(new Cookie("XSRF-TOKEN", xsrf))
                .header("X-XSRF-TOKEN", xsrf);
    }
}
