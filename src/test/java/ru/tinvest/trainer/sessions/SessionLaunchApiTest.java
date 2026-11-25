package ru.tinvest.trainer.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ru.tinvest.trainer.PostgresIntegrationTest.registeredSnapshot;

import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import ru.tinvest.trainer.PostgresIntegrationTest;
import ru.tinvest.trainer.api.DependencyUnavailableException;
import ru.tinvest.trainer.identity.CurrentUser;
import ru.tinvest.trainer.marketdata.LaunchPlan;
import ru.tinvest.trainer.marketdata.LaunchPlanner;
import ru.tinvest.trainer.marketdata.LaunchRejectReason;
import ru.tinvest.trainer.marketdata.LaunchRejection;
import ru.tinvest.trainer.marketdata.LaunchRequest;
import ru.tinvest.trainer.marketdata.LaunchResult;
import ru.tinvest.trainer.marketdata.Papers;
import ru.tinvest.trainer.marketdata.PlannedPaper;
import ru.tinvest.trainer.marketdata.Regime;
import ru.tinvest.trainer.marketdata.Snapshot;
import ru.tinvest.trainer.marketdata.Snapshots;
import ru.tinvest.trainer.marketdata.StartMode;
import ru.tinvest.trainer.marketdata.Timeframe;
import ru.tinvest.trainer.workshop.StrategyCatalog;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Запуск сессии через HTTP: настоящие PostgreSQL, Spring Security и миграции; подбор периода — заглушка. */
@AutoConfigureMockMvc
@Import(SessionLaunchApiTest.TestWiring.class)
class SessionLaunchApiTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct horse";
    private static final LocalDate START = LocalDate.of(2022, 3, 14);
    private static final LocalDate END = LocalDate.of(2022, 4, 8);
    private static final List<String> SECIDS = List.of("SBER", "GAZP", "LKOH", "YNDX", "ROSN", "NVTK", "GMKN");

    @Autowired
    private Snapshots snapshots;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private StubPlanner planner;

    @Autowired
    private CurrentUser currentUser;

    @Autowired
    private StrategyCatalog strategyCatalog;

    @Autowired
    private ObjectMapper json;

    private String email;
    private UUID userId;
    private MockHttpSession session;
    private String xsrf;

    @BeforeEach
    void setUp() throws Exception {
        planner.reset();
        email = "u" + UUID.randomUUID() + "@example.com";
        userId = createUser(email);
        login(email);
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

    private void login(String address) throws Exception {
        Cookie anonymous =
                mockMvc.perform(get("/api/v1/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(anonymous)
                        .header("X-XSRF-TOKEN", anonymous.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + address + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isNoContent())
                .andReturn();
        session = (MockHttpSession) result.getRequest().getSession(false);
        xsrf = result.getResponse().getCookie("XSRF-TOKEN").getValue();
    }

    private MvcResult launch(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/sessions")
                        .session(session)
                        .cookie(new Cookie("XSRF-TOKEN", xsrf))
                        .header("X-XSRF-TOKEN", xsrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private static String randomBody() {
        return """
                {"traderKind":"human","durationDays":20,"timeframe":"1d","regime":"calm","startMode":"random",\
                "customStartDate":null,"paperCount":3,"paperIds":null,"initialCapital":"500000.00"}""";
    }

    private static String customBody() {
        return """
                {"traderKind":"human","durationDays":20,"timeframe":"1d","regime":"calm","startMode":"custom",\
                "customStartDate":"2022-03-14","paperCount":null,"paperIds":["%s","%s"],"initialCapital":"500000.00"}""".formatted(Papers.id("SBER"), Papers.id("GAZP"));
    }

    private static LaunchPlan plan(boolean blind, int papers) {
        List<PlannedPaper> list = new ArrayList<>();
        for (int i = 0; i < papers; i++) {
            list.add(new PlannedPaper(
                    Papers.id(SECIDS.get(i)),
                    SECIDS.get(i),
                    "Сектор" + i,
                    10 + i,
                    new BigDecimal("0.01"),
                    i == 1 ? "" : "Название" + i));
        }
        Snapshot snapshot = registeredSnapshot();
        return new LaunchPlan(
                snapshot,
                blind,
                START,
                END,
                20,
                Regime.CALM,
                0.123456,
                0.0777,
                0.15,
                0.35,
                Timeframe.D1,
                20,
                list,
                new BigDecimal("500000.00"));
    }

    private long sessionCount() {
        // таблицы очистить нельзя (TRUNCATE и DELETE запрещены), поэтому считаем сессии пользователя теста
        return jdbc.sql("SELECT count(*) FROM sessions.session WHERE user_id = :u")
                .param("u", userId)
                .query(Long.class)
                .single();
    }

    @Test
    void blindLaunchCreatesSessionAndAnswersOnlyWithItsId() throws Exception {
        planner.result = plan(true, 3);

        MvcResult result = launch(randomBody());

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.propertyNames()).containsExactly("sessionId");
        UUID id = UUID.fromString(body.get("sessionId").asString());

        // слепой ответ: ни тикеров, ни секторов, ни дат, ни рублей, ни метрик периода
        String text = result.getResponse().getContentAsString();
        for (String secid : SECIDS) {
            assertThat(text).doesNotContain(secid);
        }
        assertThat(text)
                .doesNotContain("Сектор")
                .doesNotContain("Название")
                .doesNotContain("2022")
                .doesNotContain("500000")
                .doesNotContain("0.1234")
                .doesNotContain("0.35");

        var row = jdbc.sql("SELECT * FROM sessions.session WHERE id = :id")
                .param("id", id)
                .query()
                .singleRow();
        assertThat(row.get("user_id")).isEqualTo(userId);
        assertThat(row.get("blind")).isEqualTo(true);
        assertThat(row.get("status")).isEqualTo("running");
        assertThat(row.get("timeframe")).isEqualTo("1d");
        assertThat(row.get("regime")).isEqualTo("calm");
        assertThat(row.get("start_mode")).isEqualTo("random");
        assertThat(row.get("initial_capital")).isEqualTo(new BigDecimal("500000.00"));
        assertThat(row.get("period_start").toString()).isEqualTo("2022-03-14");
        assertThat(planner.lastRequest.startMode()).isEqualTo(StartMode.RANDOM);
        assertThat(planner.lastRequest.paperCount()).isEqualTo(3);

        List<Integer> indexes = jdbc.sql("SELECT paper_index FROM sessions.session_paper WHERE session_id = :id "
                        + "ORDER BY paper_index")
                .param("id", id)
                .query(Integer.class)
                .list();
        assertThat(indexes).containsExactly(0, 1, 2);
        Set<String> stored = new HashSet<>(jdbc.sql("SELECT secid FROM sessions.session_paper WHERE session_id = :id")
                .param("id", id)
                .query(String.class)
                .list());
        assertThat(stored).containsExactlyInAnyOrder("SBER", "GAZP", "LKOH");
        assertThat(jdbc.sql("SELECT count(*) FROM sessions.command_journal WHERE session_id = :id")
                        .param("id", id)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void customDateLaunchCreatesOpenSession() throws Exception {
        planner.result = plan(false, 2);

        MvcResult result = launch(customBody());

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        UUID id = UUID.fromString(json.readTree(result.getResponse().getContentAsString())
                .get("sessionId")
                .asString());
        assertThat(planner.lastRequest.startMode()).isEqualTo(StartMode.CUSTOM);
        assertThat(planner.lastRequest.customStartDate()).isEqualTo(START);
        assertThat(planner.lastRequest.paperIds()).containsExactly(Papers.id("SBER"), Papers.id("GAZP"));
        assertThat(planner.lastRequest.initialCapital()).isEqualByComparingTo("500000");
        assertThat(jdbc.sql("SELECT blind FROM sessions.session WHERE id = :id")
                        .param("id", id)
                        .query(Boolean.class)
                        .single())
                .isFalse();
    }

    @Test
    void sessionBelongsToItsOwnerAndForeignOneIs404() throws Exception {
        planner.result = plan(true, 3);
        UUID id =
                UUID.fromString(json.readTree(launch(randomBody()).getResponse().getContentAsString())
                        .get("sessionId")
                        .asString());

        mockMvc.perform(get("/api/v1/test/sessions/" + id).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(id.toString()));

        MockHttpSession ownerSession = session;
        String otherEmail = "o" + UUID.randomUUID() + "@example.com";
        createUser(otherEmail);
        login(otherEmail);
        mockMvc.perform(get("/api/v1/test/sessions/" + id).session(session))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mockMvc.perform(get("/api/v1/test/sessions/" + UUID.randomUUID()).session(session))
                .andExpect(status().isNotFound());
        // владелец по-прежнему видит свою
        mockMvc.perform(get("/api/v1/test/sessions/" + id).session(ownerSession))
                .andExpect(status().isOk());
    }

    @Test
    void rejectionIs422LaunchProblemWithCodeFieldAndLimits() throws Exception {
        planner.result = new LaunchRejection(LaunchRejectReason.TOO_MANY_BARS, "durationDays", 5001, 5000);

        MvcResult result = launch(randomBody());

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("status").asInt()).isEqualTo(422);
        assertThat(body.get("code").asString()).isEqualTo("TOO_MANY_BARS");
        assertThat(body.get("field").asString()).isEqualTo("durationDays");
        assertThat(body.get("barsCount").asInt()).isEqualTo(5001);
        assertThat(body.get("maxBars").asInt()).isEqualTo(5000);
        assertThat(body.get("detail").asString()).doesNotContain("2022");
        assertThat(sessionCount()).isZero();
    }

    @Test
    void ownDateOutOfArchiveIsRejectedWithoutDataInTheAnswer() throws Exception {
        planner.result = LaunchRejection.class.cast(
                new LaunchRejection(LaunchRejectReason.INVALID_PARAMS, "customStartDate", null, null));

        MvcResult result = launch(customBody());

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asString()).isEqualTo("INVALID_PARAMS");
        assertThat(body.get("field").asString()).isEqualTo("customStartDate");
        assertThat(body.get("barsCount").isNull()).isTrue();
        assertThat(body.get("maxBars").isNull()).isTrue();
        String text = result.getResponse().getContentAsString();
        assertThat(text).doesNotContain("2022-03-14").doesNotContain("SBER");
        assertThat(sessionCount()).isZero();
    }

    @Test
    void everyRejectionBodyCarriesOnlyContractFields() throws Exception {
        for (LaunchRejectReason reason : LaunchRejectReason.values()) {
            planner.result = new LaunchRejection(reason, "regime", null, null);
            JsonNode body = json.readTree(launch(randomBody()).getResponse().getContentAsString());
            assertThat(body.propertyNames())
                    .containsExactlyInAnyOrder(
                            "type", "title", "status", "detail", "instance", "code", "field", "barsCount", "maxBars");
            assertThat(body.get("code").asString()).isEqualTo(reason.name());
            assertThat(body.get("instance").isNull()).isTrue();
        }
    }

    @Test
    void unavailableWorkerIs503AndLeavesNoSession() throws Exception {
        planner.failure = new DependencyUnavailableException("worker down", null);

        MvcResult result = launch(randomBody());

        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(sessionCount()).isZero();
    }

    @Test
    void malformedRequestIs400() throws Exception {
        assertThat(launch(randomBody().replace("\"calm\"", "\"wild\""))
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        assertThat(launch(randomBody().replace("\"1d\"", "\"2d\""))
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        assertThat(launch(randomBody().replace("\"random\"", "\"later\""))
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        assertThat(launch(randomBody().replace("\"human\"", "\"robot\""))
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        assertThat(launch(randomBody().replace("\"500000.00\"", "\"lots\""))
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        assertThat(launch(randomBody().replace("\"durationDays\":20,", ""))
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        assertThat(launch("{").getResponse().getStatus()).isEqualTo(400);
        assertThat(sessionCount()).isZero();
        assertThat(planner.lastRequest).isNull();
    }

    @Test
    void launchRequiresLogin() throws Exception {
        Cookie anonymous =
                mockMvc.perform(get("/api/v1/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        mockMvc.perform(post("/api/v1/sessions")
                        .cookie(anonymous)
                        .header("X-XSRF-TOKEN", anonymous.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(randomBody()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/launch/options")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/papers")).andExpect(status().isUnauthorized());
    }

    @Test
    void launchOptionsFollowContract() throws Exception {
        planner.maxDurations.put(Timeframe.H1, 357);
        planner.maxDurations.put(Timeframe.D1, 5000);
        planner.archiveDateRange = new LaunchPlanner.DateRange(LocalDate.of(2024, 1, 3), LocalDate.of(2024, 12, 30));
        planner.papers = plan(false, 7).papers();

        mockMvc.perform(get("/api/v1/launch/options").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.durations[0]").value(20))
                .andExpect(jsonPath("$.durations.length()").value(4))
                .andExpect(jsonPath("$.defaultDuration").value(60))
                .andExpect(jsonPath("$.availableTimeframes[0]").value("1h"))
                .andExpect(jsonPath("$.availableTimeframes[1]").value("1d"))
                .andExpect(jsonPath("$.maxDurationDaysByTimeframe['1h']").value(357))
                .andExpect(jsonPath("$.maxDurationDaysByTimeframe['1d']").value(5000))
                .andExpect(jsonPath("$.defaultTimeframe").value("1d"))
                .andExpect(jsonPath("$.regimes[2]").value("stress"))
                .andExpect(jsonPath("$.capitalPresets[0]").value("100000.00"))
                .andExpect(jsonPath("$.capitalPresets[3]").value("5000000.00"))
                .andExpect(jsonPath("$.defaultCapital").value("1000000.00"))
                .andExpect(jsonPath("$.backgroundAlgorithmName").value("Эталон платформы"))
                .andExpect(jsonPath("$.minPapers").value(1))
                .andExpect(jsonPath("$.maxPapers").value(7))
                .andExpect(jsonPath("$.executionRules.maxBars").value(5000))
                .andExpect(jsonPath("$.executionRules.commissionPct").value("0.3"))
                .andExpect(jsonPath("$.archiveFrom").value("2024-01-03"))
                .andExpect(jsonPath("$.archiveTo").value("2024-12-30"));
    }

    @Test
    void papersCatalogListsPoolForOpenSessions() throws Exception {
        planner.papers = plan(false, 2).papers();

        mockMvc.perform(get("/api/v1/papers").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(Papers.id("SBER").toString()))
                .andExpect(jsonPath("$[0].ticker").value("SBER"))
                .andExpect(jsonPath("$[0].sector").value("Сектор0"))
                .andExpect(jsonPath("$[0].name").value("Название0"))
                // названия нет в снапшоте — тикер
                .andExpect(jsonPath("$[1].name").value("GAZP"));
    }

    @Test
    void planAndParametersAreImmutableAfterCreation() throws Exception {
        planner.result = plan(true, 2);
        UUID id =
                UUID.fromString(json.readTree(launch(randomBody()).getResponse().getContentAsString())
                        .get("sessionId")
                        .asString());

        for (String column : List.of(
                "period_start = '2020-01-01'",
                "initial_capital = 1",
                "regime = 'stress'",
                "blind = false",
                "snapshot_id = gen_random_uuid()",
                "sigma = 0",
                "user_id = gen_random_uuid()",
                "algo_version_id = gen_random_uuid()",
                "algo_params = '{\"size_pct\":10}'::jsonb")) {
            assertThatThrownBy(() -> jdbc.sql("UPDATE sessions.session SET " + column + " WHERE id = :id")
                            .param("id", id)
                            .update())
                    .as(column)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> jdbc.sql("UPDATE sessions.session_paper SET sector = 'x' WHERE session_id = :id")
                        .param("id", id)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM sessions.session_paper WHERE session_id = :id")
                        .param("id", id)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        // допустим только переход статуса из running
        assertThat(jdbc.sql("UPDATE sessions.session SET status = 'finished_early' WHERE id = :id")
                        .param("id", id)
                        .update())
                .isEqualTo(1);
    }

    private UUID launchedSession() throws Exception {
        planner.result = plan(true, 2);
        return UUID.fromString(json.readTree(launch(randomBody()).getResponse().getContentAsString())
                .get("sessionId")
                .asString());
    }

    private void setStatus(UUID id, String status) {
        jdbc.sql("UPDATE sessions.session SET status = :s WHERE id = :id")
                .param("s", status)
                .param("id", id)
                .update();
    }

    @Test
    void statusMovesOnlyFromRunningToAFinalOne() throws Exception {
        for (String target : List.of("completed", "finished_early", "bankrupt")) {
            UUID id = launchedSession();
            setStatus(id, target);
            // из завершённого нельзя ни обратно в running (после раскрытия слепую сессию нельзя продолжить), ни в
            // другой
            for (String other : List.of("running", "completed", "finished_early", "bankrupt")) {
                if (!other.equals(target)) {
                    assertThatThrownBy(() -> setStatus(id, other))
                            .as(target + " -> " + other)
                            .isInstanceOf(DataIntegrityViolationException.class);
                }
            }
            // тот же статус - не переход
            setStatus(id, target);
        }
        assertThatThrownBy(() -> setStatus(launchedSession(), "finished_end"))
                .isInstanceOf(DataIntegrityViolationException.class);
        UUID running = launchedSession();
        setStatus(running, "running");
    }

    @Test
    void sessionsCannotBeDeletedAndTablesCannotBeTruncated() throws Exception {
        UUID id = launchedSession();
        jdbc.sql("INSERT INTO sessions.command_journal (session_id, seq, step, seen_step, command_type, command, "
                        + "accepted) VALUES (:id, 0, 0, 0, 'place', '{}'::jsonb, true)")
                .param("id", id)
                .update();

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM sessions.session WHERE id = :id")
                        .param("id", id)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        for (String table : List.of("sessions.session", "sessions.session_paper", "sessions.command_journal")) {
            // без CASCADE таблицу с внешним ключом отклоняет сама БД, остальные - триггер
            assertThatThrownBy(() -> jdbc.sql("TRUNCATE " + table).update())
                    .as(table)
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(() -> jdbc.sql("TRUNCATE " + table + " CASCADE").update())
                    .as(table + " CASCADE")
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(sessionCount()).isPositive();
        assertThat(jdbc.sql("SELECT count(*) FROM sessions.command_journal WHERE session_id = :id")
                        .param("id", id)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void commandJournalIsAppendOnly() throws Exception {
        planner.result = plan(false, 1);
        UUID id =
                UUID.fromString(json.readTree(launch(customBody()).getResponse().getContentAsString())
                        .get("sessionId")
                        .asString());
        jdbc.sql("INSERT INTO sessions.command_journal (session_id, seq, step, seen_step, command_type, command, "
                        + "accepted) VALUES (:id, 0, 0, 0, 'place', '{}'::jsonb, true)")
                .param("id", id)
                .update();

        assertThatThrownBy(() -> jdbc.sql("UPDATE sessions.command_journal SET accepted = false")
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () -> jdbc.sql("DELETE FROM sessions.command_journal").update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO sessions.command_journal (session_id, seq, step, seen_step, "
                                + "command_type, command, accepted) VALUES (:id, 0, 1, 1, 'cancel', '{}'::jsonb, true)")
                        .param("id", id)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void paperIndexOrderIsRandomNotByTicker() {
        Sessions sessions = new Sessions(jdbc, currentUser, snapshots, strategyCatalog, json, new Random(7));
        List<String> byTicker = SECIDS.stream().sorted().toList();
        boolean different = false;
        for (int i = 0; i < 20; i++) {
            UUID id = sessions.create(userId, StartMode.RANDOM, 20, plan(true, 5));
            List<String> byIndex = jdbc.sql(
                            "SELECT secid FROM sessions.session_paper WHERE session_id = :id " + "ORDER BY paper_index")
                    .param("id", id)
                    .query(String.class)
                    .list();
            assertThat(byIndex).containsExactlyInAnyOrderElementsOf(SECIDS);
            different |= !byIndex.equals(byTicker);
        }
        assertThat(different).isTrue();
    }

    @Test
    void backgroundAlgorithmDefaultsToPlatformBenchmarkAndSupportsCatalogChoice() throws Exception {
        planner.result = plan(true, 3);
        UUID defaultId =
                UUID.fromString(json.readTree(launch(randomBody()).getResponse().getContentAsString())
                        .get("sessionId")
                        .asString());
        UUID defaultVer = jdbc.sql("SELECT algo_version_id FROM sessions.session WHERE id = :id")
                .param("id", defaultId)
                .query(UUID.class)
                .single();
        String defaultParams = jdbc.sql("SELECT algo_params::text FROM sessions.session WHERE id = :id")
                .param("id", defaultId)
                .query(String.class)
                .single();
        assertThat(defaultVer).isEqualTo(StrategyCatalog.SYSTEM_VERSION_ID);
        assertThat(json.readTree(defaultParams).get("size_pct").asInt()).isEqualTo(31);

        // пользовательский опубликованный алгоритм (выбирается последняя версия); чужой hidden -> 404
        UUID customAlgoId = UUID.randomUUID();
        UUID customVer1 = UUID.randomUUID();
        UUID customVer2 = UUID.randomUUID();
        jdbc.sql("INSERT INTO workshop.strategy (id, owner_id, author_name, is_system, name, visibility) "
                        + "VALUES (:id, :owner, 'Author', FALSE, :name, 'public')")
                .param("id", customAlgoId)
                .param("owner", userId)
                .param("name", "Custom-" + customAlgoId)
                .update();
        jdbc.sql("INSERT INTO workshop.strategy_version (id, strategy_id, version, source, params) "
                        + "VALUES (:v1, :s, 1, 'rule \"a\" when true then buy 1 lots', '{}'::jsonb), "
                        + "(:v2, :s, 2, 'rule \"b\" when true then buy 2 lots', '{}'::jsonb)")
                .param("v1", customVer1)
                .param("v2", customVer2)
                .param("s", customAlgoId)
                .update();
        String withCustomAlgo = randomBody().replace("}", ",\"backgroundAlgorithmId\":\"" + customAlgoId + "\"}");
        UUID customSessionId = UUID.fromString(
                json.readTree(launch(withCustomAlgo).getResponse().getContentAsString())
                        .get("sessionId")
                        .asString());
        assertThat(jdbc.sql("SELECT algo_version_id FROM sessions.session WHERE id = :id")
                        .param("id", customSessionId)
                        .query(UUID.class)
                        .single())
                .isEqualTo(customVer2);

        UUID foreignHiddenAlgo = UUID.randomUUID();
        jdbc.sql("INSERT INTO workshop.strategy (id, owner_id, author_name, is_system, name, visibility) "
                        + "VALUES (:id, :owner, 'Stranger', FALSE, :name, 'hidden')")
                .param("id", foreignHiddenAlgo)
                .param("owner", createUser("stranger" + UUID.randomUUID() + "@example.com"))
                .param("name", "Hidden-" + foreignHiddenAlgo)
                .update();
        jdbc.sql("INSERT INTO workshop.strategy_version (id, strategy_id, version, source, params) "
                        + "VALUES (:v, :s, 1, 'rule \"a\" when true then buy 1 lots', '{}'::jsonb)")
                .param("v", UUID.randomUUID())
                .param("s", foreignHiddenAlgo)
                .update();
        String withForeignHidden =
                randomBody().replace("}", ",\"backgroundAlgorithmId\":\"" + foreignHiddenAlgo + "\"}");
        assertThat(launch(withForeignHidden).getResponse().getStatus()).isEqualTo(404);

        // неопубликованный черновик (latestVersion == 0) не может быть фоновым алгоритмом -> 404
        UUID unpublishedDraftId = UUID.randomUUID();
        jdbc.sql("INSERT INTO workshop.strategy (id, owner_id, author_name, is_system, name, visibility) "
                        + "VALUES (:id, :owner, 'Author', FALSE, :name, 'public')")
                .param("id", unpublishedDraftId)
                .param("owner", userId)
                .param("name", "Draft-" + unpublishedDraftId)
                .update();
        jdbc.sql(
                        "INSERT INTO workshop.strategy_draft (strategy_id, source) VALUES (:id, 'rule \"a\" when true then buy 1 lots')")
                .param("id", unpublishedDraftId)
                .update();
        String withDraft = randomBody().replace("}", ",\"backgroundAlgorithmId\":\"" + unpublishedDraftId + "\"}");
        assertThat(launch(withDraft).getResponse().getStatus()).isEqualTo(404);
    }

    /** Чужая и несуществующая сессии для проверки владельца: запросы по ним ещё не открыты контрактом. */
    @RestController
    static class OwnedSessionController {

        private final Sessions sessions;

        OwnedSessionController(Sessions sessions) {
            this.sessions = sessions;
        }

        @GetMapping("/api/v1/test/sessions/{id}")
        String owned(@PathVariable UUID id) {
            return sessions.requireOwned(id).id().toString();
        }
    }

    /** Подбор запуска подменяет заглушка: живой каталог и воркер проверены в тестах marketdata. */
    static class StubPlanner implements LaunchPlanner {
        LaunchResult result;
        RuntimeException failure;
        LaunchRequest lastRequest;
        List<PlannedPaper> papers = List.of();
        List<Timeframe> timeframes = List.of(Timeframe.D1);
        java.util.Map<Timeframe, Integer> maxDurations = new java.util.LinkedHashMap<>();
        LaunchPlanner.DateRange archiveDateRange;

        void reset() {
            result = null;
            failure = null;
            lastRequest = null;
            papers = List.of();
            timeframes = List.of(Timeframe.D1);
            maxDurations = new java.util.LinkedHashMap<>();
            archiveDateRange = null;
        }

        @Override
        public LaunchResult plan(LaunchRequest request) {
            lastRequest = request;
            if (failure != null) {
                throw failure;
            }
            return result;
        }

        @Override
        public LaunchResult plan(LaunchRequest request, UUID snapshotId, long seed) {
            return plan(request);
        }

        @Override
        public List<PlannedPaper> papers(UUID snapshotId) {
            return papers;
        }

        @Override
        public List<Timeframe> availableTimeframes(UUID snapshotId) {
            return timeframes;
        }

        @Override
        public java.util.Map<Timeframe, Integer> maxDurations(UUID snapshotId) {
            return maxDurations;
        }

        @Override
        public LaunchPlanner.DateRange archiveDateRange(UUID snapshotId) {
            return archiveDateRange;
        }
    }

    @TestConfiguration
    static class TestWiring {

        @Bean
        @Primary
        StubPlanner stubPlanner() {
            return new StubPlanner();
        }

        @Bean
        OwnedSessionController ownedSessionController(Sessions sessions) {
            return new OwnedSessionController(sessions);
        }
    }
}
