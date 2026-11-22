package ru.tinvest.trainer.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static ru.tinvest.trainer.PostgresIntegrationTest.registeredSnapshot;

import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.tinvest.trainer.PostgresIntegrationTest;
import ru.tinvest.trainer.analysis.ReportBuilder;
import ru.tinvest.trainer.marketdata.LaunchPlan;
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

/**
 * Отчёт, история и воспроизведение через HTTP: настоящие PostgreSQL, Spring Security и миграции, воркер —
 * {@link FakeWorker}. Проверяют статусы отчёта (в том числе слепая незавершённая ничего не раскрывает, INV-7),
 * страницы и фильтры истории, воспроизведение без состояния (совпадает с ареной на том же шаге, INV-4; будущего нет,
 * INV-1) и доступ по владельцу.
 */
@AutoConfigureMockMvc
@Import(ReportHistoryReplayApiTest.TestWiring.class)
class ReportHistoryReplayApiTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct horse";
    private static final List<String> SECIDS = List.of("SBER", "GAZP", "LKOH");
    private static final long LAST_STEP = 6;
    private static final Set<String> NOT_FINISHED_KEYS =
            Set.of("status", "sessionId", "blind", "title", "createdAt", "playedDays", "totalDays", "regime");
    private static final Set<String> BLIND_UNFINISHED_KEYS = Set.of(
            "id",
            "kind",
            "owner",
            "createdAt",
            "regime",
            "playedDays",
            "totalDays",
            "algoName",
            "blind",
            "status",
            "view");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private Sessions sessions;

    @Autowired
    private Snapshots snapshots;

    @Autowired
    private FakeWorker worker;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ReportBuilder builder;

    @Autowired
    private PlayerStates players;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID userId;
    private MockHttpSession httpSession;
    private String xsrf;

    @BeforeEach
    void setUp() throws Exception {
        worker.reset();
        userId = createUser("u" + UUID.randomUUID() + "@example.com");
        login(emailOf(userId));
    }

    // ---------- снапшот ----------

    /**
     * Воркер читает данные по ключу снапшота ClickHouse, а не по первичному ключу PostgreSQL: ключ здесь не UUID и не
     * совпадает с {@code id} (сквозной тест backend#4 нашёл, что воркеру уходил PK).
     */
    @Test
    void workerReceivesTheSnapshotKeyNotThePostgresId() throws Exception {
        Snapshot registered = snapshots.register("clickhouse-key-" + UUID.randomUUID(), "ключ не в формате UUID");
        UUID id = sessions.create(
                userId, StartMode.CUSTOM, 20, plan(registered, false, Regime.CALM, SECIDS, LocalDate.of(2022, 3, 14)));
        assertThat(body(httpGet("/api/v1/sessions/" + id)).get("step").asLong()).isZero();
        assertThat(worker.lastMarket.snapshotId()).isEqualTo(registered.snapshotKey());
        assertThat(worker.lastMarket.snapshotId()).isNotEqualTo(registered.id().toString());
    }

    // ---------- признак blind ----------

    @Test
    void snapshotCarriesTheLaunchModeBeforeAndAfterFinish() throws Exception {
        UUID open = newSession(false);
        assertThat(body(httpGet("/api/v1/sessions/" + open)).get("blind").asBoolean())
                .isFalse();

        UUID blind = newSession(true);
        JsonNode running = body(httpGet("/api/v1/sessions/" + blind));
        assertThat(running.get("mode").asString()).isEqualTo("BLIND");
        assertThat(running.get("blind").asBoolean()).isTrue();

        setStep(blind, LAST_STEP);
        finish(blind, "completed");
        // слепая сессия раскрыта (mode OPEN), но запущена как слепая: и арена, и воспроизведение отдают blind = true
        JsonNode revealed = body(httpGet("/api/v1/sessions/" + blind));
        assertThat(revealed.get("mode").asString()).isEqualTo("OPEN");
        assertThat(revealed.get("blind").asBoolean()).isTrue();
        JsonNode replay = body(httpGet("/api/v1/sessions/" + blind + "/replay?step=2"));
        assertThat(replay.get("snapshot").get("blind").asBoolean()).isTrue();
    }

    // ---------- воспроизведение ----------

    @Test
    void replayAtStepEqualsTheArenaSnapshotAtTheSameStep() throws Exception {
        UUID id = newSession(false);
        // арена на каждом шаге; журнал растёт вместе с курсором
        List<Long> steps = List.of(0L, 1L, 2L, 4L, LAST_STEP);
        Map<Long, JsonNode> arena = new HashMap<>();
        for (long step : steps) {
            setStep(id, step);
            if (step == 0) {
                body(httpPost(id, "/orders", openOrder("SBER", 0, "buy", "market", 2, null)));
            }
            if (step == 2) {
                body(httpPost(id, "/orders", openOrder("GAZP", 2, "buy", "limit", 1, "150.00")));
            }
            arena.put(step, body(httpGet("/api/v1/sessions/" + id)));
        }
        finish(id, "completed");

        for (long step : steps) {
            JsonNode replay = body(httpGet("/api/v1/sessions/" + id + "/replay?step=" + step));
            assertThat(replay.get("status").asString()).isEqualTo("READY");
            assertThat(replay.get("kind").asString()).isEqualTo("SNAPSHOT");
            assertThat(replay.has("increment")).isFalse();
            assertThat(withoutPlayerState(replay.get("snapshot")))
                    .as("шаг %d", step)
                    .isEqualTo(withoutPlayerState(arena.get(step)));
            assertThat(replay.get("snapshot").get("playing").isNull()).isTrue();
            assertThat(replay.get("snapshot").get("speed").isNull()).isTrue();
            assertThat(replay.get("snapshot").get("finished").asBoolean()).isEqualTo(step == LAST_STEP);
        }
        // команда журнала с шага 2 на шаге 1 ещё не подана (INV-1): заявок на GAZP нет
        JsonNode early =
                body(httpGet("/api/v1/sessions/" + id + "/replay?step=1")).get("snapshot");
        assertThat(early.get("orders")).hasSize(1);
        assertThat(body(httpGet("/api/v1/sessions/" + id + "/replay?step=" + LAST_STEP))
                        .get("snapshot")
                        .get("orders"))
                .hasSize(2);
    }

    @Test
    void replayBeyondTheFinishedStepIsClampedAndShowsNoLaterBars() throws Exception {
        UUID id = newSession(false);
        setStep(id, 4);
        finish(id, "finished_early");

        JsonNode replay = body(httpGet("/api/v1/sessions/" + id + "/replay?step=999"));

        JsonNode snapshot = replay.get("snapshot");
        assertThat(snapshot.get("step").asLong()).isEqualTo(4);
        assertThat(replay.get("session").get("lastStep").asLong()).isEqualTo(4);
        assertThat(snapshot.get("finished").asBoolean()).isTrue();
        for (JsonNode paper : snapshot.get("papers")) {
            for (JsonNode candle : paper.get("candles")) {
                assertThat(candle.get("step").asLong()).isLessThanOrEqualTo(4);
            }
        }
        assertThat(worker.replayUpToSteps).containsOnly(4L);
    }

    @Test
    void replayOfARunningSessionIsNotFinishedAndRevealsNothing() throws Exception {
        for (boolean blind : List.of(true, false)) {
            UUID id = newSession(blind);
            setStep(id, 3);

            JsonNode replay = body(httpGet("/api/v1/sessions/" + id + "/replay?step=2"));

            assertThat(replay.get("status").asString()).isEqualTo("NOT_FINISHED");
            assertThat(keys(replay)).isEqualTo(NOT_FINISHED_KEYS);
            assertThat(replay.get("playedDays").asInt()).isEqualTo(2);
            assertThat(replay.get("blind").asBoolean()).isEqualTo(blind);
        }
    }

    @Test
    void replayIncrementCoversOnlyTheStepsAfterFromStep() throws Exception {
        UUID id = newSession(false);
        setStep(id, 0);
        body(httpPost(id, "/orders", openOrder("SBER", 0, "buy", "market", 2, null)));
        setStep(id, LAST_STEP);
        finish(id, "completed");

        JsonNode before = body(httpGet("/api/v1/sessions/" + id + "/replay?step=1&fromStep=0"))
                .get("increment");
        JsonNode after = body(httpGet("/api/v1/sessions/" + id + "/replay?step=4&fromStep=1"))
                .get("increment");
        JsonNode empty = body(httpGet("/api/v1/sessions/" + id + "/replay?step=4&fromStep=4"))
                .get("increment");

        // рыночная заявка исполнена на шаге 1: попадает в (0; 1], не в (1; 4]
        assertThat(before.get("fills")).hasSize(1);
        assertThat(after.get("fills")).isEmpty();
        assertThat(before.get("mode").asString()).isEqualTo("OPEN");
        assertThat(before.get("step").asLong()).isEqualTo(1);
        assertThat(after.get("step").asLong()).isEqualTo(4);
        assertThat(after.get("bars")).hasSize(3);
        assertThat(after.get("bars").propertyNames()).allMatch(k -> k.matches("[0-9a-f-]{36}"));
        assertThat(empty.get("fills")).isEmpty();
        assertThat(empty.get("orders")).isEmpty();
        JsonNode full = body(httpGet("/api/v1/sessions/" + id + "/replay?step=4&fromStep=1"));
        assertThat(full.get("kind").asString()).isEqualTo("INCREMENT");
        assertThat(full.has("snapshot")).isFalse();
    }

    @Test
    void replayRejectsBadStepsAndOtherUsers() throws Exception {
        UUID id = newSession(false);
        finish(id, "completed");

        assertThat(httpGet("/api/v1/sessions/" + id + "/replay?step=-1")
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        assertThat(httpGet("/api/v1/sessions/" + id + "/replay?step=2&fromStep=3")
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        assertThat(httpGet("/api/v1/sessions/" + id + "/replay?step=x")
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);

        login(emailOf(createUser("stranger" + UUID.randomUUID() + "@example.com")));
        JsonNode foreign = body(httpGet("/api/v1/sessions/" + id + "/replay?step=1"));
        JsonNode unknown = body(httpGet("/api/v1/sessions/" + UUID.randomUUID() + "/replay"));
        assertThat(foreign.get("status").asString()).isEqualTo("NOT_FOUND");
        assertThat(keys(foreign)).isEqualTo(Set.of("status", "sessionId"));
        assertThat(unknown.get("status").asString()).isEqualTo("NOT_FOUND");
        assertThat(worker.replayUpToSteps).isEmpty();
    }

    // ---------- отчёт ----------

    @Test
    void reportOfAFinishedSessionCarriesTheCoreMetrics() throws Exception {
        UUID id = newSession(false);
        body(httpPost(id, "/orders", openOrder("SBER", 0, "buy", "market", 2, null)));
        setStep(id, LAST_STEP);
        finish(id, "completed");

        // отчёта ещё нет: статус BUILDING, сборка идёт в фоне
        JsonNode building = body(httpGet("/api/v1/sessions/" + id + "/report"));
        assertThat(building.get("status").asString()).isEqualTo("BUILDING");
        assertThat(keys(building)).isEqualTo(Set.of("status", "sessionId"));
        JsonNode result = readyReport(id);

        assertThat(result.get("status").asString()).isEqualTo("READY");
        JsonNode report = result.get("report");
        assertThat(report.get("sessionId").asString()).isEqualTo(id.toString());
        assertThat(report.get("finishedEarly").asBoolean()).isFalse();
        assertThat(report.get("replayAvailable").asBoolean()).isTrue();
        assertThat(report.get("timeframe").asString()).isEqualTo("1d");
        assertThat(report.get("regime").asString()).isEqualTo("calm");
        assertThat(report.get("totalDays").asInt()).isEqualTo(20);
        assertThat(report.get("playedDays").asInt()).isEqualTo(3);
        assertThat(report.get("period").get("startDate").asString()).isEqualTo("2022-03-14");
        assertThat(report.get("papers")).hasSize(3);
        JsonNode sber = paper(report, "SBER");
        assertThat(sber.get("id").asString()).isEqualTo(Papers.id("SBER").toString());
        assertThat(sber.get("sector").asString()).startsWith("Сектор");
        assertThat(sber.get("startPrice").asString()).matches("\\d+\\.\\d{2,}");

        JsonNode metrics = report.get("humanMetrics");
        assertThat(metrics.get("initialCapital").asString()).isEqualTo("500000.00");
        assertThat(metrics.get("finalCapital").asString()).isEqualTo("500600.00");
        // доли ядра — в процентах контракта; необязательные значения ядра — null
        assertThat(metrics.get("totalReturnPct").asString()).isEqualTo("1.23");
        assertThat(metrics.get("netReturnPct").asString()).isEqualTo("1.23");
        assertThat(metrics.get("grossReturnPct").asString()).isEqualTo("1.25");
        assertThat(metrics.get("maxDrawdownPct").asString()).isEqualTo("0.40");
        assertThat(metrics.get("maxDrawdownDurationSteps").asInt()).isEqualTo(3);
        assertThat(metrics.get("maxDrawdownDurationDays").asInt()).isEqualTo(2);
        assertThat(metrics.get("cagrPct").isNull()).isTrue();
        assertThat(metrics.get("sharpe").isNull()).isTrue();
        assertThat(metrics.get("sortino").isNull()).isTrue();
        assertThat(metrics.get("volatilityPct").isNull()).isTrue();
        assertThat(metrics.get("benchmarkImoexPct").isNull()).isTrue();
        assertThat(metrics.get("winRatePct").asString()).isEqualTo("100.00");
        assertThat(metrics.get("tradesCount").asInt()).isEqualTo(1);
        assertThat(metrics.get("largestPositionPct").asString()).isEqualTo("30.00");
        assertThat(metrics.get("bankrupt").asBoolean()).isFalse();
        assertThat(metrics.get("costs").get("commission").asString()).isEqualTo("6.00");
        assertThat(metrics.get("costs").get("total").asString()).isEqualTo("7.70");

        // ФТ-18…20: фоновый алгоритм, сравнение, кривые и разбор расхождений заполнены
        assertThat(report.get("backgroundAlgorithmName").asString()).isEqualTo("Эталон платформы");
        JsonNode algoMetrics = report.get("algoMetrics");
        assertThat(algoMetrics.get("initialCapital").asString()).isEqualTo("500000.00");
        assertThat(algoMetrics.get("finalCapital").asString()).isEqualTo("501200.00");
        assertThat(algoMetrics.get("totalReturnPct").asString()).isEqualTo("2.46");
        assertThat(algoMetrics.get("maxDrawdownPct").asString()).isEqualTo("0.20");
        assertThat(algoMetrics.get("bankrupt").asBoolean()).isFalse();

        JsonNode comparison = report.get("comparison");
        assertThat(comparison.get("finalCapital").get("human").asString()).isEqualTo("500600.00");
        assertThat(comparison.get("finalCapital").get("algo").asString()).isEqualTo("501200.00");
        assertThat(comparison.get("finalCapital").get("winner").asString()).isEqualTo("algo");
        assertThat(comparison.get("finalCapital").get("diff").asString()).isEqualTo("-600.00");
        assertThat(comparison.get("netReturn").get("winner").asString()).isEqualTo("algo");
        assertThat(comparison.get("netReturn").get("diffPctPoints").asString()).isEqualTo("-1.23");
        assertThat(comparison.get("maxDrawdown").get("winner").asString()).isEqualTo("algo");
        assertThat(comparison.get("maxDrawdown").get("diffPctPoints").asString())
                .isEqualTo("0.20");

        JsonNode summary = report.get("divergenceSummary");
        assertThat(summary.get("totalErrorCost").asString()).isEqualTo("100.00");
        assertThat(summary.get("totalErrorCostPct").asString()).isEqualTo("0.02");
        assertThat(summary.get("worstDivergenceId").asString())
                .isEqualTo(DerivedIds.divergence(id, 0).toString());
        assertThat(summary.get("worstType").asString()).isEqualTo("extra");
        assertThat(summary.get("worstDay").asInt()).isEqualTo(1);
        assertThat(summary.get("worstErrorCost").asString()).isEqualTo("150.00");

        JsonNode divergences = report.get("divergences");
        assertThat(divergences).hasSize(2);
        assertThat(divergences.get(0).get("id").asString())
                .isEqualTo(DerivedIds.divergence(id, 0).toString());
        assertThat(divergences.get(0).get("type").asString()).isEqualTo("extra");
        assertThat(divergences.get(0).get("errorCostPct").asString()).isEqualTo("0.03");
        assertThat(divergences.get(0).get("because").asString()).isEqualTo("алгоритм здесь не действовал бы");
        assertThat(divergences.get(0).get("humanAction").get("kind").asString()).isEqualTo("trade");
        assertThat(divergences.get(0).get("algoAction").get("kind").asString()).isEqualTo("none");
        assertThat(divergences.get(1).get("id").asString())
                .isEqualTo(DerivedIds.divergence(id, 1).toString());
        assertThat(divergences.get(1).get("type").asString()).isEqualTo("missed");

        JsonNode reverse = report.get("reverseCheckTrades");
        assertThat(reverse).hasSize(1);
        assertThat(reverse.get(0).get("id").asString())
                .isEqualTo(DerivedIds.reverseCheckTrade(id, 0).toString());
        assertThat(reverse.get(0).get("entryDivergenceId").asString())
                .isEqualTo(DerivedIds.divergence(id, 0).toString());
        assertThat(reverse.get(0).get("netProfit").asString()).isEqualTo("188.00");

        JsonNode underwater = report.get("underwaterCurve");
        assertThat(underwater).hasSize((int) LAST_STEP + 1);
        assertThat(underwater.get(2).get("humanDrawdownPct").asString()).isEqualTo("0.00");
        assertThat(underwater.get(2).get("algoDrawdownPct").asString()).isEqualTo("0.00");
        assertThat(underwater.get(2).get("date").asString()).endsWith("Z");

        JsonNode equity = report.get("equityCurve");
        assertThat(equity).hasSize((int) LAST_STEP + 1);
        assertThat(equity.get(2).get("humanCapital").asString()).isEqualTo("500200.00");
        assertThat(equity.get(2).get("algoCapital").asString()).isEqualTo("500400.00");
        assertThat(equity.get(2).get("benchmarkCapital").asString()).isEqualTo("500010.00");
        assertThat(equity.get(1).get("benchmarkCapital").isNull()).isTrue();
        assertThat(equity.get(0).get("date").asString()).endsWith("Z");
        JsonNode trip = report.get("roundTrips").get(0);
        assertThat(trip.get("netPnl").asString()).isEqualTo("188.00");
        assertThat(trip.get("entryDate").asString()).endsWith("Z");
        JsonNode fill = report.get("fills").get(0);
        assertThat(fill.get("orderType").asString()).isEqualTo("market");
        assertThat(fill.get("side").asString()).isEqualTo("buy");
        assertThat(fill.get("partial").asBoolean()).isFalse();
        assertThat(fill.get("cost").asString()).isEqualTo("7.70");
        assertThat(fill.get("id").asString()).isEqualTo(DerivedIds.fill(id, 0).toString());

        // отчёт строится по журналу целиком до итогового шага, а разбор расхождений передаёт size_pct = floor(95/3) =
        // 31
        assertThat(worker.reportRequests).hasSize(1);
        assertThat(worker.reportRequests.getFirst().upToStep()).isEqualTo(LAST_STEP);
        assertThat(worker.reportRequests.getFirst().journal()).hasSize(1);
        assertThat(worker.divergenceRequests).hasSize(1);
        assertThat(worker.divergenceRequests.getFirst().lastStep()).isEqualTo(LAST_STEP);
        assertThat(worker.divergenceRequests.getFirst().algorithms()).hasSize(1);
        assertThat(worker.divergenceRequests.getFirst().algorithms().getFirst().id())
                .isEqualTo(StrategyCatalog.SYSTEM_VERSION_ID.toString());
        assertThat(worker.divergenceRequests.getFirst().algorithms().getFirst().paramOverrides())
                .containsEntry("size_pct", 31.0);
    }

    @Test
    void reportOfAFinishedBlindSessionIsRevealed() throws Exception {
        UUID id = newSession(true);
        setStep(id, LAST_STEP);
        finish(id, "finished_early");

        JsonNode result = readyReport(id);

        assertThat(result.get("status").asString()).isEqualTo("READY");
        JsonNode report = result.get("report");
        assertThat(report.get("blind").asBoolean()).isTrue();
        assertThat(report.get("finishedEarly").asBoolean()).isTrue();
        Set<String> tickers = new HashSet<>();
        for (JsonNode paper : report.get("papers")) {
            tickers.add(paper.get("ticker").asString());
            assertThat(paper.get("blindLabel").asString()).startsWith("Бумага ");
        }
        assertThat(tickers).containsExactlyInAnyOrderElementsOf(SECIDS);
        assertThat(report.get("period").get("endDate").asString()).isEqualTo("2022-04-09");
    }

    @Test
    void reportOfABankruptSessionMarksBankruptNotEarly() throws Exception {
        UUID id = newSession(false);
        setStep(id, 2);
        finish(id, "bankrupt");

        JsonNode report = readyReport(id).get("report");

        assertThat(report.get("finishedEarly").asBoolean()).isFalse();
        assertThat(report.get("humanMetrics").get("bankrupt").asBoolean()).isTrue();
    }

    @Test
    void reportOfAnUnfinishedBlindSessionRevealsNothing() throws Exception {
        // INV-7: слепая сессия, пока идёт, не раскрывает бумаги, период, итог и рубли — на любом шаге и составе
        Random random = new Random(7);
        for (int run = 0; run < 8; run++) {
            int count = 1 + random.nextInt(3);
            List<String> secids = SECIDS.subList(0, count);
            LocalDate start = LocalDate.of(2022, 3, 14).plusDays(random.nextInt(60));
            UUID id = newSession(true, Regime.values()[run % 3], secids, start);
            long step = random.nextInt((int) LAST_STEP + 1);
            setStep(id, step);
            if (step > 0) {
                body(httpPost(id, "/orders", blindOrder(0, "buy", "market", 1, null)));
            }

            MvcResult raw = httpGet("/api/v1/sessions/" + id + "/report");
            JsonNode result = body(raw);

            assertThat(result.get("status").asString()).isEqualTo("NOT_FINISHED");
            assertThat(keys(result)).isEqualTo(NOT_FINISHED_KEYS);
            assertThat(result.get("title").asString()).startsWith("Слепая сессия от ");
            assertThat(result.get("blind").asBoolean()).isTrue();
            String text = raw.getResponse().getContentAsString();
            for (String secid : secids) {
                assertThat(text).doesNotContain(secid);
            }
            assertThat(text)
                    .doesNotContain(
                            "Сектор", start.toString(), start.plusDays(26).toString(), "500000", "ticker")
                    .doesNotContain("papers", "period", "humanMetrics", "report");
        }
        // отчёт идущей сессии у ядра не запрашивался
        assertThat(worker.reportRequests).isEmpty();
    }

    @Test
    void reportOfAnUnfinishedOpenSessionIsNotFinishedToo() throws Exception {
        UUID id = newSession(false);
        setStep(id, 3);

        JsonNode result = body(httpGet("/api/v1/sessions/" + id + "/report"));

        assertThat(result.get("status").asString()).isEqualTo("NOT_FINISHED");
        assertThat(keys(result)).isEqualTo(NOT_FINISHED_KEYS);
        assertThat(result.get("title").asString()).startsWith("Сессия от ");
        assertThat(worker.reportRequests).isEmpty();
    }

    @Test
    void reportOfOtherUsersOrUnknownSessionIsNotFound() throws Exception {
        UUID id = newSession(false);
        finish(id, "completed");
        login(emailOf(createUser("stranger" + UUID.randomUUID() + "@example.com")));

        JsonNode foreign = body(httpGet("/api/v1/sessions/" + id + "/report"));
        JsonNode unknown = body(httpGet("/api/v1/sessions/" + UUID.randomUUID() + "/report"));

        assertThat(foreign.get("status").asString()).isEqualTo("NOT_FOUND");
        assertThat(keys(foreign)).isEqualTo(Set.of("status", "sessionId"));
        assertThat(unknown.get("status").asString()).isEqualTo("NOT_FOUND");
        assertThat(worker.reportRequests).isEmpty();
    }

    @Test
    void reportNeedsLogin() throws Exception {
        assertThat(mockMvc.perform(get("/api/v1/sessions/" + UUID.randomUUID() + "/report"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
        assertThat(mockMvc.perform(get("/api/v1/history"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
    }

    @Test
    void reportStaysBuildingWhileTheWorkerIsDownAndIsBuiltOnceItIsBack() throws Exception {
        UUID id = newSession(false);
        finish(id, "completed");
        worker.down = true;

        // фоновая сборка не вышла: отчёт не сохранён, клиент видит BUILDING, а не 503
        assertThat(body(httpGet("/api/v1/sessions/" + id + "/report"))
                        .get("status")
                        .asString())
                .isEqualTo("BUILDING");
        awaitIdle();
        assertThat(storedReports(id)).isZero();
        assertThat(body(httpGet("/api/v1/sessions/" + id + "/report"))
                        .get("status")
                        .asString())
                .isEqualTo("BUILDING");
        awaitIdle();

        worker.down = false;
        assertThat(readyReport(id).get("status").asString()).isEqualTo("READY");
        assertThat(storedReports(id)).isEqualTo(1);
    }

    @Test
    void finishedEventBuildsAndStoresTheReportOnceAfterCommit() throws Exception {
        UUID id = newSession(false);
        setStep(id, LAST_STEP);
        finish(id, "completed");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            events.publishEvent(new SessionFinished(id));
            // до фиксации ничего не строится
            assertThat(storedReports(id)).isZero();
        });
        for (int i = 0; i < 200 && storedReports(id) == 0; i++) {
            Thread.sleep(50);
        }

        assertThat(storedReports(id)).isEqualTo(1);
        assertThat(worker.reportRequests).hasSize(1);
        // отчёт объявлен один раз: report_ready в потоке событий
        assertThat(jdbc.sql(
                                "SELECT count(*) FROM sessions.session_event WHERE session_id = :id AND type = 'report_ready'")
                        .param("id", id)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        // повтор сборки и события ничего не меняет: отчёт неизменяем (INV-5)
        assertThat(builder.buildAndStore(id)).isTrue();
        assertThat(worker.reportRequests).hasSize(1);
        assertThatThrownBy(() -> jdbc.sql("UPDATE analysis.session_report SET played_days = 0 WHERE session_id = :id")
                        .param("id", id)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(readyReport(id).get("report").get("sessionId").asString()).isEqualTo(id.toString());
        // сессия, которая ещё идёт, не строится
        assertThat(builder.buildAndStore(newSession(false))).isFalse();
    }

    @Test
    void failingReportBuildIsRetriedNoMoreOftenThanTheInterval() throws Exception {
        UUID id = newSession(false);
        finish(id, "completed");
        worker.down = true;

        httpGet("/api/v1/sessions/" + id + "/report");
        awaitIdle();
        assertThat(worker.reportAttempts.get()).isEqualTo(1);
        // повторные запросы в течение интервала журнал заново не гоняют
        for (int i = 0; i < 3; i++) {
            assertThat(body(httpGet("/api/v1/sessions/" + id + "/report"))
                            .get("status")
                            .asString())
                    .isEqualTo("BUILDING");
        }
        awaitIdle();
        assertThat(worker.reportAttempts.get()).isEqualTo(1);
        // интервал прошёл: следующий запрос пробует снова
        Thread.sleep(1_000);
        httpGet("/api/v1/sessions/" + id + "/report");
        awaitIdle();
        assertThat(worker.reportAttempts.get()).isEqualTo(2);
    }

    // ---------- история ----------

    @Test
    void historyTakesTheDayFromThePlayerAndDoesNotNeedTheWorker() throws Exception {
        UUID id = newSession(false);
        setStep(id, 4);
        players.setCursor(id, 4, 2, 2);
        worker.down = true;

        JsonNode page = body(httpGet("/api/v1/history"));

        assertThat(page.get("items")).hasSize(1);
        assertThat(page.get("items").get(0).get("playedDays").asInt()).isEqualTo(2);
        assertThat(worker.dayRequests).isEmpty();
    }

    @Test
    void historyIsPagedNewestFirst() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(newSession(false));
        }
        UUID stranger = otherUsersSession();
        assertThat(stranger).isNotNull();

        JsonNode first = body(httpGet("/api/v1/history?pageSize=2"));
        JsonNode second = body(httpGet("/api/v1/history?pageSize=2&page=2"));
        JsonNode third = body(httpGet("/api/v1/history?pageSize=2&page=3"));
        JsonNode beyond = body(httpGet("/api/v1/history?pageSize=2&page=4"));

        assertThat(first.get("total").asInt()).isEqualTo(5);
        assertThat(first.get("page").asInt()).isEqualTo(1);
        assertThat(first.get("pageSize").asInt()).isEqualTo(2);
        assertThat(first.get("totalPages").asInt()).isEqualTo(3);
        assertThat(idsOf(first))
                .containsExactly(ids.get(4).toString(), ids.get(3).toString());
        assertThat(idsOf(second))
                .containsExactly(ids.get(2).toString(), ids.get(1).toString());
        assertThat(idsOf(third)).containsExactly(ids.get(0).toString());
        assertThat(beyond.get("items")).isEmpty();
        assertThat(beyond.get("total").asInt()).isEqualTo(5);
        // по умолчанию — первая страница из 20
        JsonNode defaults = body(httpGet("/api/v1/history"));
        assertThat(defaults.get("pageSize").asInt()).isEqualTo(20);
        assertThat(defaults.get("totalPages").asInt()).isEqualTo(1);
        assertThat(idsOf(defaults)).doesNotContain(stranger.toString()).hasSize(5);
    }

    @Test
    void historyRejectsBadParameters() throws Exception {
        for (String query : List.of(
                "page=0", "pageSize=0", "pageSize=101", "status=paused", "regime=wild", "kind=trade", "page=x")) {
            assertThat(httpGet("/api/v1/history?" + query).getResponse().getStatus())
                    .as(query)
                    .isEqualTo(400);
        }
    }

    @Test
    void historyFiltersByBlindStatusRegimeAndKind() throws Exception {
        UUID blindRunning = newSession(true, Regime.CALM, SECIDS, LocalDate.of(2022, 3, 14));
        UUID openRunning = newSession(false, Regime.STRESS, SECIDS, LocalDate.of(2022, 3, 14));
        UUID blindDone = newSession(true, Regime.STRESS, SECIDS, LocalDate.of(2022, 3, 14));
        finish(blindDone, "completed");
        UUID openEarly = newSession(false, Regime.MEDIUM, SECIDS, LocalDate.of(2022, 3, 14));
        finish(openEarly, "finished_early");

        assertThat(idsOf(body(httpGet("/api/v1/history?blindOnly=true"))))
                .containsExactly(blindDone.toString(), blindRunning.toString());
        assertThat(idsOf(body(httpGet("/api/v1/history?status=running"))))
                .containsExactly(openRunning.toString(), blindRunning.toString());
        assertThat(idsOf(body(httpGet("/api/v1/history?status=finished_early"))))
                .containsExactly(openEarly.toString());
        assertThat(idsOf(body(httpGet("/api/v1/history?status=bankrupt")))).isEmpty();
        assertThat(idsOf(body(httpGet("/api/v1/history?regime=stress"))))
                .containsExactly(blindDone.toString(), openRunning.toString());
        assertThat(idsOf(body(httpGet("/api/v1/history?regime=stress&status=completed&blindOnly=true"))))
                .containsExactly(blindDone.toString());
        assertThat(idsOf(body(httpGet("/api/v1/history?kind=session")))).hasSize(4);
        // прогонов алгоритмов пока нет: страница пуста, форма — контрактная
        JsonNode runs = body(httpGet("/api/v1/history?kind=algo_run"));
        assertThat(runs.get("items")).isEmpty();
        assertThat(runs.get("total").asInt()).isZero();
        assertThat(runs.get("totalPages").asInt()).isZero();
    }

    @Test
    void historyEntriesAreBlindUnfinishedOrRevealed() throws Exception {
        UUID blindRunning = newSession(true);
        UUID openRunning = newSession(false);
        setStep(openRunning, 3);
        // плеер запомнил день на шаге курсора: воркер для него не нужен
        players.setCursor(openRunning, 3, 2, 1);
        UUID blindDone = newSession(true);
        setStep(blindDone, LAST_STEP);
        finish(blindDone, "completed");

        // итог завершённой берётся из сохранённого отчёта; пока отчёта нет — null, воркер на строку не зовётся
        JsonNode early = body(httpGet("/api/v1/history"));
        for (JsonNode item : early.get("items")) {
            if (item.get("id").asString().equals(blindDone.toString())) {
                assertThat(item.get("netReturnPct").isNull()).isTrue();
                assertThat(item.get("playedDays").asInt()).isEqualTo(3);
            }
        }
        assertThat(worker.reportRequests).isEmpty();
        assertThat(worker.replayUpToSteps).isEmpty();
        builder.buildAndStore(blindDone);
        worker.dayRequests.clear();
        JsonNode page = body(httpGet("/api/v1/history"));
        // лёгкий вызов только для идущей сессии без строки плеера; открытая — из player_state, завершённая — из
        // хранилища
        assertThat(worker.dayRequests).hasSize(1);
        assertThat(worker.reportRequests).hasSize(1);

        Map<String, JsonNode> byId = new HashMap<>();
        page.get("items").forEach(item -> byId.put(item.get("id").asString(), item));
        JsonNode blind = byId.get(blindRunning.toString());
        assertThat(blind.get("view").asString()).isEqualTo("BLIND_UNFINISHED");
        assertThat(keys(blind)).isEqualTo(BLIND_UNFINISHED_KEYS);
        assertThat(blind.get("status").asString()).isEqualTo("running");
        assertThat(blind.get("kind").asString()).isEqualTo("session");
        assertThat(blind.get("owner").asString()).isEqualTo("USER");
        assertThat(blind.get("algoName").isNull()).isTrue();

        JsonNode open = byId.get(openRunning.toString());
        assertThat(open.get("view").asString()).isEqualTo("REVEALED");
        assertThat(open.get("title").asString()).startsWith("14.03.2022 — 09.04.2022 · ");
        for (String secid : SECIDS) {
            assertThat(open.get("title").asString()).contains(secid);
        }
        assertThat(open.get("netReturnPct").isNull()).isTrue();
        assertThat(open.get("playedDays").asInt()).isEqualTo(2);
        assertThat(open.get("totalDays").asInt()).isEqualTo(20);
        List<String> tickers = new ArrayList<>();
        open.get("papers").forEach(p -> tickers.add(p.asString()));
        assertThat(tickers).containsExactlyInAnyOrderElementsOf(SECIDS);
        assertThat(open.get("period").get("startDate").asString()).isEqualTo("2022-03-14");

        JsonNode done = byId.get(blindDone.toString());
        assertThat(done.get("view").asString()).isEqualTo("REVEALED");
        assertThat(done.get("blind").asBoolean()).isTrue();
        assertThat(done.get("status").asString()).isEqualTo("completed");
        assertThat(done.get("netReturnPct").asString()).isEqualTo("1.23");
        assertThat(done.get("playedDays").asInt()).isEqualTo(3);
    }

    @Test
    void historyOfUnfinishedBlindSessionsRevealsNothing() throws Exception {
        // INV-7: в записи нет бумаг, периода, названия и итога, ни в ключах, ни в тексте
        Random random = new Random(11);
        List<String> periodDates = new ArrayList<>();
        for (int run = 0; run < 6; run++) {
            LocalDate start = LocalDate.of(2022, 3, 14).plusDays(random.nextInt(90));
            periodDates.add(start.toString());
            periodDates.add(start.plusDays(26).toString());
            UUID id = newSession(true, Regime.values()[run % 3], SECIDS, start);
            setStep(id, random.nextInt((int) LAST_STEP + 1));
        }

        MvcResult raw = httpGet("/api/v1/history?pageSize=100");
        JsonNode page = body(raw);

        assertThat(page.get("items")).hasSize(6);
        for (JsonNode item : page.get("items")) {
            assertThat(keys(item)).isEqualTo(BLIND_UNFINISHED_KEYS);
        }
        String text = raw.getResponse().getContentAsString();
        for (String secid : SECIDS) {
            assertThat(text).doesNotContain(secid);
        }
        assertThat(text).doesNotContain("Сектор", "title", "papers", "period", "netReturnPct");
        for (String date : periodDates) {
            assertThat(text).doesNotContain(date);
        }
    }

    // ---------- помощники ----------

    private static JsonNode withoutPlayerState(JsonNode snapshot) {
        // у воспроизведения нет плеера (null), `finished` означает «дошли до конца»: у арены идущей сессии иначе
        tools.jackson.databind.node.ObjectNode copy = (tools.jackson.databind.node.ObjectNode) snapshot.deepCopy();
        copy.remove(List.of("playing", "speed", "finished", "lastEventId"));
        return copy;
    }

    private static JsonNode paper(JsonNode report, String ticker) {
        for (JsonNode paper : report.get("papers")) {
            if (ticker.equals(paper.get("ticker").asString())) {
                return paper;
            }
        }
        throw new AssertionError("нет бумаги " + ticker);
    }

    private static Set<String> keys(JsonNode node) {
        Set<String> keys = new HashSet<>();
        node.propertyNames().forEach(keys::add);
        return keys;
    }

    private static List<String> idsOf(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(item.get("id").asString()));
        return ids;
    }

    /** Отчёт готов: запрос запускает сборку, дальше ждём сохранения (фон) и читаем сохранённый. */
    private JsonNode readyReport(UUID id) throws Exception {
        for (int i = 0; i < 200; i++) {
            JsonNode result = body(httpGet("/api/v1/sessions/" + id + "/report"));
            if (!"BUILDING".equals(result.get("status").asString())) {
                return result;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("отчёт не построен");
    }

    /** Ждёт, пока фоновая сборка отчёта не освободит потоки (рядом с проверкой «отчёта нет»). */
    private void awaitIdle() throws Exception {
        Thread.sleep(300);
    }

    private long storedReports(UUID id) {
        return jdbc.sql("SELECT count(*) FROM analysis.session_report WHERE session_id = :id")
                .param("id", id)
                .query(Long.class)
                .single();
    }

    private void finish(UUID id, String status) {
        jdbc.sql("UPDATE sessions.session SET status = :status WHERE id = :id")
                .param("status", status)
                .param("id", id)
                .update();
    }

    private void setStep(UUID id, long step) {
        jdbc.sql("UPDATE sessions.session SET current_step = :s WHERE id = :id")
                .param("s", step)
                .param("id", id)
                .update();
    }

    private UUID otherUsersSession() {
        UUID other = createUser("other" + UUID.randomUUID() + "@example.com");
        return sessions.create(
                other, StartMode.CUSTOM, 20, plan(false, Regime.CALM, SECIDS, LocalDate.of(2022, 3, 14)));
    }

    private UUID newSession(boolean blind) {
        return newSession(blind, Regime.CALM, SECIDS, LocalDate.of(2022, 3, 14));
    }

    private UUID newSession(boolean blind, Regime regime, List<String> secids, LocalDate start) {
        return sessions.create(
                userId, blind ? StartMode.RANDOM : StartMode.CUSTOM, 20, plan(blind, regime, secids, start));
    }

    private static LaunchPlan plan(boolean blind, Regime regime, List<String> secids, LocalDate start) {
        Snapshot snapshot = registeredSnapshot();
        return plan(snapshot, blind, regime, secids, start);
    }

    private static LaunchPlan plan(
            Snapshot snapshot, boolean blind, Regime regime, List<String> secids, LocalDate start) {
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

    private MvcResult httpGet(String url) throws Exception {
        return mockMvc.perform(withAuth(get(url))).andReturn();
    }

    private MvcResult httpPost(UUID id, String suffix, String body) throws Exception {
        return mockMvc.perform(withAuth(post("/api/v1/sessions/" + id + suffix))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    /** Тело ответа 200 как JSON. */
    private JsonNode body(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static String openOrder(String secid, int seenStep, String side, String type, int lots, String limit) {
        return order("\"paperId\":\"" + Papers.id(secid) + "\"", seenStep, side, type, lots, limit);
    }

    private static String blindOrder(int seenStep, String side, String type, int lots, String limit) {
        return order("\"paperIndex\":0", seenStep, side, type, lots, limit);
    }

    private static String order(String paper, int seenStep, String side, String type, int lots, String limit) {
        String price = limit == null ? "" : ",\"limitPrice\":\"" + limit + "\"";
        return "{\"seenStep\":" + seenStep + "," + paper + ",\"side\":\"" + side + "\",\"type\":\"" + type
                + "\",\"lots\":" + lots + ",\"tif\":\"gtc\"" + price + "}";
    }

    @TestConfiguration
    static class TestWiring {

        @Bean
        @Primary
        FakeWorker fakeWorker() {
            return new FakeWorker();
        }
    }
}
