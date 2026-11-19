package ru.tinvest.trainer.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.tinvest.trainer.PostgresIntegrationTest;
import ru.tinvest.trainer.marketdata.LaunchPlan;
import ru.tinvest.trainer.marketdata.Papers;
import ru.tinvest.trainer.marketdata.PlannedPaper;
import ru.tinvest.trainer.marketdata.Regime;
import ru.tinvest.trainer.marketdata.Snapshot;
import ru.tinvest.trainer.marketdata.StartMode;
import ru.tinvest.trainer.marketdata.Timeframe;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Плеер и поток событий через HTTP: настоящие PostgreSQL, Spring Security и миграции, воркер — {@link FakeWorker},
 * часы — {@link ManualTimer} (виртуальное время, без ожидания). Шкала фейка — шаги 0..{@link FakeWorker#TOTAL_STEPS}-1.
 */
@AutoConfigureMockMvc
@Import(PlayerApiTest.TestWiring.class)
class PlayerApiTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct horse";
    private static final List<String> SECIDS = List.of("SBER", "GAZP", "LKOH");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern UUID_TEXT =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    /** Ключи, которых нет в слепом виде ни одного события (INV-7): рубли, тикеры, названия, реальное время. */
    private static final Set<String> OPEN_ONLY_KEYS = Set.of(
            "ticker",
            "name",
            "paperId",
            "instrumentIndex",
            "lotSize",
            "openedAt",
            "executedAt",
            "referencePrice",
            "amount",
            "commission",
            "exchangeFee",
            "requiredCash",
            "equity",
            "cash",
            "invested",
            "pnl",
            "costs",
            "marketValue",
            "cost",
            "period",
            "startDate",
            "endDate");

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
    private ManualTimer timer;

    @Autowired
    private SessionEvents events;

    @Autowired
    private Player player;

    @Autowired
    private ObjectMapper json;

    private UUID userId;
    private MockHttpSession httpSession;
    private String xsrf;
    private final List<SessionEvents.Subscription> open = new ArrayList<>();

    /** Получатель событий: запоминает номер, тип и тело. */
    private static final class Collector implements SessionEvents.Sink {

        record Event(long id, String type, String data) {}

        final List<Event> received = new CopyOnWriteArrayList<>();
        volatile boolean broken;

        @Override
        public void send(long eventId, String type, String data) throws Exception {
            if (broken) {
                throw new java.io.IOException("клиент ушёл");
            }
            received.add(new Event(eventId, type, data));
        }

        @Override
        public void ping() throws Exception {
            if (broken) {
                throw new java.io.IOException("клиент ушёл");
            }
        }

        List<String> types() {
            return received.stream().map(Event::type).toList();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        worker.reset();
        timer.reset();
        userId = createUser("u" + UUID.randomUUID() + "@example.com");
        login();
    }

    @AfterEach
    void closeSubscriptions() {
        open.forEach(SessionEvents.Subscription::close);
        open.clear();
    }

    // ---------- такты на виртуальных часах ----------

    @Test
    void clockAdvancesOneCandlePerSecondAtSpeedOne() throws Exception {
        UUID id = newSession(false);
        subscribe(id, null);

        assertThat(body(play(id, "play", null, null)).get("playing").asBoolean())
                .isTrue();
        timer.advance(Duration.ofMillis(999));
        assertThat(step(id)).isZero();
        timer.advance(Duration.ofMillis(1));
        assertThat(step(id)).isEqualTo(1);
        timer.advance(Duration.ofSeconds(3));
        assertThat(step(id)).isEqualTo(4);
    }

    @Test
    void clockAdvancesFiveTenAndHundredCandlesPerSecond() throws Exception {
        UUID five = newSession(false);
        subscribe(five, null);
        body(play(five, "speed", 5, null));
        body(play(five, "play", null, null));
        timer.advance(Duration.ofSeconds(1));
        assertThat(step(five)).isEqualTo(5);
        body(play(five, "pause", null, null));

        UUID ten = newSession(false);
        subscribe(ten, null);
        body(play(ten, "speed", 10, null));
        body(play(ten, "play", null, null));
        timer.advance(Duration.ofMillis(500));
        assertThat(step(ten)).isEqualTo(4);
        timer.advance(Duration.ofMillis(100));
        assertThat(step(ten)).isEqualTo(6);
        body(play(ten, "pause", null, null));

        // ×100: за 0,1 с проходит десять свечей, шкала фейка заканчивается раньше — сессия завершается на последнем
        // шаге
        UUID hundred = newSession(false);
        subscribe(hundred, null);
        body(play(hundred, "speed", 100, null));
        body(play(hundred, "play", null, null));
        timer.advance(Duration.ofMillis(200));
        assertThat(step(hundred)).isEqualTo(FakeWorker.TOTAL_STEPS - 1);
        assertThat(status(hundred)).isEqualTo("completed");
    }

    @Test
    void speedChangeWhilePlayingTakesEffectAndIsPublished() throws Exception {
        UUID id = newSession(false);
        Collector collector = subscribe(id, null);
        body(play(id, "play", null, null));
        timer.advance(Duration.ofSeconds(1));
        assertThat(step(id)).isEqualTo(1);

        JsonNode state = body(play(id, "speed", 5, null));

        assertThat(state.get("speed").asInt()).isEqualTo(5);
        assertThat(state.get("playing").asBoolean()).isTrue();
        timer.advance(Duration.ofSeconds(1));
        assertThat(step(id)).isEqualTo(6);
        JsonNode lastPlayer = lastOfType(collector, "player");
        assertThat(lastPlayer.get("speed").asInt()).isEqualTo(5);
        assertThat(lastPlayer.get("playing").asBoolean()).isTrue();
    }

    // ---------- команды плеера ----------

    @Test
    void stepMovesCursorByOneAndReturnsStepResult() throws Exception {
        UUID id = newSession(false);

        JsonNode state = body(play(id, "step", null, null));

        assertThat(state.get("mode").asString()).isEqualTo("OPEN");
        assertThat(state.get("step").asInt()).isEqualTo(1);
        assertThat(state.get("playing").asBoolean()).isFalse();
        JsonNode result = state.get("stepResult");
        assertThat(result.get("step").asInt()).isEqualTo(1);
        assertThat(result.get("bars")).hasSize(SECIDS.size());
        for (String paperId : result.get("bars").propertyNames()) {
            assertThat(result.get("bars").get(paperId).get("step").asInt()).isEqualTo(1);
        }
        assertThat(step(id)).isEqualTo(1);
        // остальные действия stepResult не возвращают
        assertThat(body(play(id, "pause", null, null)).get("stepResult").isNull())
                .isTrue();
    }

    @Test
    void conflictsOfThePlayerState() throws Exception {
        UUID id = newSession(false);
        subscribe(id, null);
        body(play(id, "play", null, null));

        // step во время play
        assertConflict(play(id, "step", null, null), "PLAYER_STATE_CONFLICT");
        body(play(id, "pause", null, null));

        // play и step на последнем шаге (сессия ещё идёт, но шагов нет)
        setStep(id, FakeWorker.TOTAL_STEPS - 1);
        assertConflict(play(id, "play", null, null), "PLAYER_STATE_CONFLICT");
        assertConflict(play(id, "step", null, null), "PLAYER_STATE_CONFLICT");
        assertThat(step(id)).isEqualTo(FakeWorker.TOTAL_STEPS - 1);
    }

    @Test
    void finishedSessionRejectsPlayerCommandsWith409() throws Exception {
        UUID id = newSession(false);
        body(httpPost(id, "/finish", ""));

        assertConflict(play(id, "play", null, null), "SESSION_NOT_ACTIVE");
        assertConflict(play(id, "step", null, null), "SESSION_NOT_ACTIVE");
        assertConflict(play(id, "speed", 5, null), "SESSION_NOT_ACTIVE");
    }

    @Test
    void malformedPlayerCommandsAre400() throws Exception {
        UUID id = newSession(false);

        assertThat(play(id, "speed", null, null).getResponse().getStatus()).isEqualTo(400);
        assertThat(play(id, "speed", 7, null).getResponse().getStatus()).isEqualTo(400);
        assertThat(play(id, "play", 5, null).getResponse().getStatus()).isEqualTo(400);
        assertThat(play(id, "step", 1, null).getResponse().getStatus()).isEqualTo(400);
        assertThat(playRaw(id, "{\"action\":\"rewind\"}", null).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(playRaw(id, "{}", null).getResponse().getStatus()).isEqualTo(400);
        // speed: null допустим у остальных действий
        assertThat(playRaw(id, "{\"action\":\"pause\",\"speed\":null}", null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(step(id)).isZero();
    }

    @Test
    void snapshotShowsPlayerState() throws Exception {
        UUID id = newSession(false);
        subscribe(id, null);
        assertThat(body(httpGet(id, "")).get("playing").asBoolean()).isFalse();
        assertThat(body(httpGet(id, "")).get("speed").asInt()).isEqualTo(1);

        body(play(id, "speed", 10, null));
        body(play(id, "play", null, null));

        JsonNode snapshot = body(httpGet(id, ""));
        assertThat(snapshot.get("playing").asBoolean()).isTrue();
        assertThat(snapshot.get("speed").asInt()).isEqualTo(10);
    }

    @Test
    void playerStepIsIdempotentByKey() throws Exception {
        UUID id = newSession(false);
        UUID key = UUID.randomUUID();

        JsonNode first = body(play(id, "step", null, key));
        JsonNode repeat = body(play(id, "step", null, key));

        assertThat(repeat).isEqualTo(first);
        assertThat(step(id)).isEqualTo(1);
        // тот же ключ с другим действием — 422
        assertThat(play(id, "pause", null, key).getResponse().getStatus()).isEqualTo(422);
        // другой ключ — новый шаг
        body(play(id, "step", null, UUID.randomUUID()));
        assertThat(step(id)).isEqualTo(2);
        assertThat(eventCount(id, "step")).isEqualTo(2);
    }

    @Test
    void foreignSessionIs404ForPlayerFinishAndEvents() throws Exception {
        UUID id = newSession(false);
        String strangerEmail = "x" + UUID.randomUUID() + "@example.com";
        createUser(strangerEmail);
        login(strangerEmail);

        assertThat(play(id, "step", null, UUID.randomUUID()).getResponse().getStatus())
                .isEqualTo(404);
        assertThat(httpPost(id, "/finish", "").getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(withAuth(
                                get("/api/v1/sessions/" + id + "/events").accept(MediaType.TEXT_EVENT_STREAM)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        assertThat(mockMvc.perform(withAuth(get("/api/v1/sessions/" + UUID.randomUUID() + "/events")))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        assertThat(step(id)).isZero();
        assertThat(eventCount(id, "step")).isZero();
    }

    // ---------- пауза без подписчиков и при сбое воркера ----------

    @Test
    void playerPausesAfterFiveSecondsWithoutSubscribers() throws Exception {
        UUID id = newSession(false);
        body(play(id, "play", null, null));

        timer.advance(Duration.ofMillis(4999));
        assertThat(playing(id)).isTrue();
        timer.advance(Duration.ofMillis(1));

        assertThat(playing(id)).isFalse();
        assertThat(timer.pendingGameTasks()).isZero();
        long stopped = step(id);
        timer.advance(Duration.ofSeconds(10));
        assertThat(step(id)).isEqualTo(stopped);
        assertThat(pausedReasons(id)).containsExactly("disconnect");
    }

    @Test
    void playerKeepsPlayingWhileSomeoneListensAndPausesAfterTheLastLeaves() throws Exception {
        UUID id = newSession(false);
        SessionEvents.Subscription tab = subscribeHandle(id, null);
        body(play(id, "play", null, null));

        timer.advance(Duration.ofSeconds(8));
        assertThat(playing(id)).isTrue();
        assertThat(step(id)).isEqualTo(8);

        // вторая вкладка перекрывает уход первой
        SessionEvents.Subscription second = subscribeHandle(id, null);
        tab.close();
        timer.advance(Duration.ofSeconds(1));
        assertThat(playing(id)).isFalse();
        // сессия закончилась (шаг 9), пауза не нужна и не публикуется
        assertThat(status(id)).isEqualTo("completed");
        second.close();
        assertThat(pausedReasons(id)).isEmpty();
    }

    @Test
    void reconnectWithinFiveSecondsCancelsTheDisconnectPause() throws Exception {
        UUID id = newSession(false);
        SessionEvents.Subscription tab = subscribeHandle(id, null);
        body(play(id, "play", null, null));
        timer.advance(Duration.ofSeconds(1));

        tab.close();
        timer.advance(Duration.ofSeconds(4));
        subscribe(id, null);
        // старый срок паузы (с момента ухода первой вкладки) проходит, игра идёт
        timer.advance(Duration.ofSeconds(3));

        assertThat(playing(id)).isTrue();
        assertThat(pausedReasons(id)).isEmpty();
    }

    @Test
    void brokenConnectionIsDetectedByHeartbeatAndPauses() throws Exception {
        UUID id = newSession(false);
        Collector tab = subscribe(id, null);
        body(play(id, "play", null, null));
        timer.advance(Duration.ofSeconds(1));
        assertThat(playing(id)).isTrue();

        tab.broken = true;
        // следующее событие или пульс не доходит: подписка снята, дальше — пять секунд без подписчиков
        timer.advance(Duration.ofSeconds(2));
        assertThat(events.subscribers(id)).isZero();
        timer.advance(Duration.ofSeconds(5));

        assertThat(playing(id)).isFalse();
        assertThat(pausedReasons(id)).containsExactly("disconnect");
    }

    @Test
    void workerFailureInATickPausesThePlayer() throws Exception {
        UUID id = newSession(false);
        Collector collector = subscribe(id, null);
        body(play(id, "play", null, null));
        timer.advance(Duration.ofSeconds(2));
        assertThat(step(id)).isEqualTo(2);

        worker.down = true;
        timer.advance(Duration.ofSeconds(1));

        assertThat(playing(id)).isFalse();
        assertThat(step(id)).isEqualTo(2);
        assertThat(status(id)).isEqualTo("running");
        assertThat(pausedReasons(id)).containsExactly("worker_failure");
        assertThat(lastOfType(collector, "player").get("playing").asBoolean()).isFalse();
        assertThat(timer.pendingGameTasks()).isZero();
        // воркер вернулся: игра снова запускается командой
        worker.down = false;
        assertThat(body(play(id, "play", null, null)).get("playing").asBoolean())
                .isTrue();
        timer.advance(Duration.ofSeconds(1));
        assertThat(step(id)).isEqualTo(3);
    }

    @Test
    void restartPausesEveryPlayingSessionWithoutGhostTimers() throws Exception {
        UUID id = newSession(false);
        subscribe(id, null);
        body(play(id, "play", null, null));
        timer.advance(Duration.ofSeconds(1));
        // «перезапуск»: таймеров нет, а в БД игра помечена идущей
        timer.reset();
        assertThat(playing(id)).isTrue();

        player.pauseAfterRestart();

        assertThat(playing(id)).isFalse();
        assertThat(timer.pendingGameTasks()).isZero();
        timer.advance(Duration.ofSeconds(10));
        assertThat(step(id)).isEqualTo(1);
        assertThat(body(httpGet(id, "")).get("playing").asBoolean()).isFalse();
        JsonNode last = lastStored(id);
        assertThat(lastStoredType(id)).isEqualTo("player");
        assertThat(last.get("playing").asBoolean()).isFalse();
    }

    // ---------- поток событий ----------

    @Test
    void eventsHaveIncreasingIdsAndSseNames() throws Exception {
        UUID id = newSession(false);
        Collector collector = subscribe(id, null);
        body(play(id, "play", null, null));
        timer.advance(Duration.ofSeconds(3));
        body(play(id, "pause", null, null));

        assertThat(collector.types()).containsExactly("player", "step", "step", "step", "player", "paused");
        long previous = 0;
        for (Collector.Event event : collector.received) {
            assertThat(event.id()).isGreaterThan(previous);
            previous = event.id();
            JsonNode data = json.readTree(event.data());
            assertThat(data.get("type").asString()).isEqualTo(event.type());
            assertThat(data.get("eventId").asString()).isEqualTo(Long.toString(event.id()));
        }
        assertThat(json.readTree(collector.received.get(5).data()).get("reason").asString())
                .isEqualTo("user");
    }

    @Test
    void lastEventIdResumesFromTheNextEvent() throws Exception {
        UUID id = newSession(false);
        Collector live = subscribe(id, null);
        body(play(id, "play", null, null));
        timer.advance(Duration.ofSeconds(4));
        body(play(id, "pause", null, null));
        long cursor = live.received.get(2).id();

        Collector resumed = subscribe(id, cursor);

        List<Long> expected = live.received.stream()
                .map(Collector.Event::id)
                .filter(n -> n > cursor)
                .toList();
        assertThat(resumed.received.stream().map(Collector.Event::id)).containsExactlyElementsOf(expected);
        assertThat(resumed.received).isNotEmpty();
        // без Last-Event-ID прошлое не повторяется
        assertThat(subscribe(id, null).received).isEmpty();
        // номер из будущего считается последним: событий нет, новые приходят
        Collector future = subscribe(id, 10_000L);
        assertThat(future.received).isEmpty();
        body(play(id, "step", null, null));
        assertThat(future.types()).containsExactly("step");
    }

    @Test
    void lastEventIdOverHttpReplaysAndRejectsGarbage() throws Exception {
        UUID id = newSession(false);
        body(play(id, "step", null, null));
        body(play(id, "step", null, null));

        MvcResult all = stream(id, "0");
        String text = all.getResponse().getContentAsString();
        assertThat(all.getRequest().isAsyncStarted()).isTrue();
        assertThat(text).contains("event:step").contains("id:1").contains("id:2");
        assertThat(all.getResponse().getContentType()).startsWith("text/event-stream");

        String afterFirst = stream(id, "1").getResponse().getContentAsString();
        assertThat(afterFirst).contains("id:2").doesNotContain("id:1\n");

        // живое событие доходит до открытого потока
        MvcResult live = stream(id, null);
        assertThat(live.getResponse().getContentAsString()).doesNotContain("event:step");
        body(play(id, "step", null, null));
        assertThat(live.getResponse().getContentAsString())
                .contains("event:step")
                .contains("id:3");

        assertThat(stream(id, "abc").getResponse().getStatus()).isEqualTo(400);
        assertThat(stream(id, "-1").getResponse().getStatus()).isEqualTo(400);
        // потоки закрываем, чтобы не держать подписки
        events.subscribers(id);
    }

    @Test
    void orderCommandsPublishOrderUpdatedForOtherTabs() throws Exception {
        UUID id = newSession(false);
        Collector collector = subscribe(id, null);

        MvcResult placed = postJson(
                id,
                "/orders",
                "{\"seenStep\":0,\"paperId\":\"" + Papers.id("SBER")
                        + "\",\"side\":\"buy\",\"type\":\"limit\",\"lots\":2,\"tif\":\"gtc\",\"limitPrice\":\"190.00\"}");
        JsonNode result = body(placed);

        assertThat(collector.types()).containsExactly("order_updated");
        JsonNode event = json.readTree(collector.received.get(0).data());
        assertThat(event.get("mode").asString()).isEqualTo("OPEN");
        assertThat(event.get("order").get("id").asString())
                .isEqualTo(result.get("orderIds").get(0).asString());
        assertThat(event.get("portfolio")).isNotNull();
    }

    // ---------- INV-1: события не дальше курсора ----------

    @Test
    void noEventCarriesDataBeyondTheCursor() throws Exception {
        for (boolean blind : new boolean[] {false, true}) {
            UUID id = newSession(blind);
            Collector collector = subscribe(id, null);
            body(postJson(id, "/orders", order(blind, "{\"seenStep\":0,", "buy", "market", 1, null)));
            body(play(id, "speed", 5, null));
            body(play(id, "play", null, null));
            long checked = 0;
            for (int i = 0; i < 12 && "running".equals(status(id)); i++) {
                timer.advance(Duration.ofMillis(400));
                // курсор в БД не меньше шага любого полученного события
                long cursor = step(id);
                for (Collector.Event event : collector.received) {
                    JsonNode data = json.readTree(event.data());
                    if (data.has("result") && data.get("result").has("step")) {
                        assertThat(data.get("result").get("step").asLong()).isLessThanOrEqualTo(cursor);
                    }
                    checked++;
                }
            }
            assertThat(status(id)).isEqualTo("completed");
            assertThat(checked).isPositive();
            long finalStep = step(id);
            long previousStep = -1;
            for (Collector.Event event : collector.received) {
                JsonNode data = json.readTree(event.data());
                for (long stepInside : stepsOf(data)) {
                    assertThat(stepInside)
                            .as("шаг внутри события %s (blind=%s)", event.type(), blind)
                            .isLessThanOrEqualTo(finalStep);
                }
                if ("step".equals(event.type())) {
                    long eventStep = data.get("result").get("step").asLong();
                    // каждое событие шага содержит свечи только своего шага и исполнения не дальше него
                    JsonNode result = data.get("result");
                    for (JsonNode bar : result.get("bars")) {
                        assertThat(bar.get("step").asLong()).isEqualTo(eventStep);
                    }
                    for (JsonNode fill : result.get("fills")) {
                        assertThat(fill.get("step").asLong()).isLessThanOrEqualTo(eventStep);
                    }
                    assertThat(eventStep).isGreaterThanOrEqualTo(previousStep);
                    previousStep = eventStep;
                }
            }
        }
    }

    // ---------- INV-7: слепые события ----------

    @Test
    void blindEventsCarryNoTickersDatesOrRubles() throws Exception {
        UUID id = newSession(true);
        Collector collector = subscribe(id, null);
        body(postJson(id, "/orders", order(true, "{\"seenStep\":0,", "buy", "market", 1, null)));
        body(postJson(id, "/orders", order(true, "{\"seenStep\":0,", "buy", "limit", 2, "100.50")));
        body(play(id, "step", null, null));
        body(play(id, "speed", 5, null));
        body(play(id, "play", null, null));
        timer.advance(Duration.ofMillis(400));
        body(play(id, "pause", null, null));
        body(play(id, "step", null, null));
        body(httpPost(id, "/finish", ""));

        assertThat(collector.types())
                .contains("order_updated", "step", "player", "paused", "finished")
                .doesNotContain("report_ready");
        for (Collector.Event event : collector.received) {
            assertBlindEvent(event.data(), event.type());
        }
        // то же самое — в хранимых телах (догон по Last-Event-ID отдаёт их же)
        for (String stored : storedBodies(id)) {
            assertBlindEvent(stored, "stored");
        }
        // конкретно: свеча и портфель слепого шага — проценты и индексы
        JsonNode step = json.readTree(collector.received.stream()
                .filter(e -> e.type().equals("step"))
                .findFirst()
                .orElseThrow()
                .data());
        assertThat(step.get("result").get("mode").asString()).isEqualTo("BLIND");
        assertThat(step.get("result").get("bars").propertyNames()).allMatch(name -> name.matches("\\d+"));
        assertThat(step.get("result").get("portfolio").get("equityPct")).isNotNull();
    }

    @Test
    void blindSessionIsRevealedOnlyAfterFinish() throws Exception {
        UUID id = newSession(true);
        subscribe(id, null);
        assertThat(sessions.reload(id).revealed()).isFalse();
        body(play(id, "step", null, null));
        assertThat(sessions.reload(id).revealed()).isFalse();

        JsonNode finish = body(httpPost(id, "/finish", ""));

        assertThat(sessions.reload(id).revealed()).isTrue();
        // итог слепой сессии остаётся слепым видом: раскрытие — в отчёте и воспроизведении
        assertThat(finish.get("mode").asString()).isEqualTo("BLIND");
        assertThat(finish.get("endReason").asString()).isEqualTo("early");
        assertThat(sessions.reload(id).status()).isEqualTo("finished_early");
        assertThat(sessions.reload(id).revealed()).isTrue();
        assertThat(newSessionRecord(false).revealed()).isTrue();
    }

    // ---------- завершение ----------

    @Test
    void userFinishSetsStatusStopsThePlayerAndPublishesFinished() throws Exception {
        UUID id = newSession(false);
        Collector collector = subscribe(id, null);
        body(play(id, "play", null, null));
        timer.advance(Duration.ofSeconds(2));

        JsonNode finish = body(httpPost(id, "/finish", ""));

        assertThat(finish.get("endReason").asString()).isEqualTo("early");
        assertThat(finish.get("finishedAtStep").asInt()).isEqualTo(2);
        assertThat(finish.get("mode").asString()).isEqualTo("OPEN");
        assertThat(status(id)).isEqualTo("finished_early");
        assertThat(playing(id)).isFalse();
        assertThat(timer.pendingGameTasks()).isZero();
        assertThat(collector.types()).endsWith("player", "finished");
        JsonNode event = lastOfType(collector, "finished");
        assertThat(event.get("result").get("endReason").asString()).isEqualTo("early");
        // повтор — 200 с тем же итогом, событий не прибавляется
        int before = collector.received.size();
        assertThat(body(httpPost(id, "/finish", ""))).isEqualTo(finish);
        assertThat(collector.received).hasSize(before);
        // курсор не движется
        timer.advance(Duration.ofSeconds(5));
        assertThat(step(id)).isEqualTo(2);
    }

    @Test
    void finishWithUnavailableWorkerIs503AndKeepsTheSessionActive() throws Exception {
        UUID id = newSession(false);
        worker.down = true;

        MvcResult result = httpPost(id, "/finish", "");

        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(status(id)).isEqualTo("running");
        assertThat(eventCount(id, "finished")).isZero();
        worker.down = false;
        assertThat(body(httpPost(id, "/finish", "")).get("endReason").asString())
                .isEqualTo("early");
    }

    @Test
    void endOfPeriodCompletesTheSession() throws Exception {
        UUID id = newSession(false);
        Collector collector = subscribe(id, null);
        body(play(id, "speed", 5, null));
        body(play(id, "play", null, null));

        timer.advance(Duration.ofSeconds(3));

        assertThat(status(id)).isEqualTo("completed");
        assertThat(step(id)).isEqualTo(FakeWorker.TOTAL_STEPS - 1);
        assertThat(playing(id)).isFalse();
        assertThat(timer.pendingGameTasks()).isZero();
        JsonNode finished = lastOfType(collector, "finished");
        assertThat(finished.get("result").get("endReason").asString()).isEqualTo("period_end");
        assertThat(finished.get("result").get("finishedAtStep").asInt()).isEqualTo((int) FakeWorker.TOTAL_STEPS - 1);
        // последний шаг помечен finished, события шага идут раньше итога
        JsonNode lastStep = lastOfType(collector, "step");
        assertThat(lastStep.get("result").get("finished").asBoolean()).isTrue();
        assertThat(collector.types().indexOf("finished"))
                .isGreaterThan(collector.types().lastIndexOf("step"));
        assertThat(body(httpGet(id, "")).get("finished").asBoolean()).isTrue();
        assertConflict(play(id, "play", null, null), "SESSION_NOT_ACTIVE");
    }

    @Test
    void bankruptcyStopsTheSessionAtTheBankruptStep() throws Exception {
        worker.bankruptAt = 3;
        UUID id = newSession(false);
        Collector collector = subscribe(id, null);
        body(play(id, "speed", 5, null));
        body(play(id, "play", null, null));

        timer.advance(Duration.ofSeconds(3));

        assertThat(status(id)).isEqualTo("bankrupt");
        assertThat(step(id)).isEqualTo(3);
        JsonNode finished = lastOfType(collector, "finished");
        assertThat(finished.get("result").get("endReason").asString()).isEqualTo("bankrupt");
        assertThat(finished.get("result").get("finishedAtStep").asInt()).isEqualTo(3);
    }

    @Test
    void stepOntoTheLastCandleCompletesTheSession() throws Exception {
        UUID id = newSession(false);
        setStep(id, FakeWorker.TOTAL_STEPS - 2);

        JsonNode state = body(play(id, "step", null, null));

        assertThat(state.get("finished").asBoolean()).isTrue();
        assertThat(state.get("stepResult").get("finished").asBoolean()).isTrue();
        assertThat(status(id)).isEqualTo("completed");
        assertThat(eventCount(id, "finished")).isEqualTo(1);
    }

    @Test
    void reportReadyIsPublishedForSubscribers() throws Exception {
        UUID id = newSession(false);
        Collector collector = subscribe(id, null);

        events.reportReady(id);

        assertThat(collector.types()).containsExactly("report_ready");
        assertThat(json.readTree(collector.received.get(0).data())
                        .get("sessionId")
                        .asString())
                .isEqualTo(id.toString());
    }

    // ---------- вспомогательное ----------

    private Collector subscribe(UUID id, Long lastEventId) {
        Collector collector = new Collector();
        open.add(events.subscribe(id, lastEventId, collector));
        return collector;
    }

    private SessionEvents.Subscription subscribeHandle(UUID id, Long lastEventId) {
        SessionEvents.Subscription subscription = events.subscribe(id, lastEventId, new Collector());
        open.add(subscription);
        return subscription;
    }

    private MvcResult stream(UUID id, String lastEventId) throws Exception {
        MockHttpServletRequestBuilder builder =
                withAuth(get("/api/v1/sessions/" + id + "/events")).accept(MediaType.TEXT_EVENT_STREAM);
        return mockMvc.perform(lastEventId == null ? builder : builder.header("Last-Event-ID", lastEventId))
                .andReturn();
    }

    private void assertBlindEvent(String data, String where) {
        JsonNode node = json.readTree(data);
        Set<String> keys = new HashSet<>();
        collectKeys(node, keys);
        assertThat(keys).as("ключи события %s: %s", where, data).doesNotContainAnyElementsOf(OPEN_ONLY_KEYS);
        List<String> texts = new ArrayList<>();
        collectTexts(node, texts);
        for (String text : texts) {
            if (UUID_TEXT.matcher(text).matches()) {
                continue;
            }
            assertThat(text).as(data).doesNotContain("SBER", "GAZP", "LKOH", "500000", "Z");
            assertThat(DATE.matcher(text).find()).as("дата в «%s»", text).isFalse();
        }
        for (JsonNode mode : modes(node)) {
            assertThat(mode.asString()).isEqualTo("BLIND");
        }
    }

    private static List<JsonNode> modes(JsonNode node) {
        List<JsonNode> result = new ArrayList<>();
        if (node.isObject()) {
            for (String name : node.propertyNames()) {
                if (name.equals("mode")) {
                    result.add(node.get(name));
                }
                result.addAll(modes(node.get(name)));
            }
        } else if (node.isArray()) {
            node.forEach(child -> result.addAll(modes(child)));
        }
        return result;
    }

    /** Все числа поля {@code step} во вложенных свечах и исполнениях события. */
    private static List<Long> stepsOf(JsonNode node) {
        List<Long> result = new ArrayList<>();
        if (node.isObject()) {
            for (String name : node.propertyNames()) {
                JsonNode child = node.get(name);
                if (name.equals("step") && child.isNumber()) {
                    result.add(child.asLong());
                }
                result.addAll(stepsOf(child));
            }
        } else if (node.isArray()) {
            node.forEach(child -> result.addAll(stepsOf(child)));
        }
        return result;
    }

    private static void collectKeys(JsonNode node, Set<String> keys) {
        if (node.isObject()) {
            for (String name : node.propertyNames()) {
                keys.add(name);
                collectKeys(node.get(name), keys);
            }
        } else if (node.isArray()) {
            node.forEach(child -> collectKeys(child, keys));
        }
    }

    private static void collectTexts(JsonNode node, List<String> texts) {
        if (node.isString()) {
            texts.add(node.asString());
        } else if (node.isObject()) {
            node.propertyNames().forEach(name -> collectTexts(node.get(name), texts));
        } else if (node.isArray()) {
            node.forEach(child -> collectTexts(child, texts));
        }
    }

    private JsonNode lastOfType(Collector collector, String type) {
        return collector.received.stream()
                .filter(e -> e.type().equals(type))
                .reduce((a, b) -> b)
                .map(e -> json.readTree(e.data()))
                .orElseThrow(() -> new AssertionError("нет события " + type + " среди " + collector.types()));
    }

    private JsonNode lastStored(UUID id) {
        List<String> bodies = storedBodies(id);
        return json.readTree(bodies.get(bodies.size() - 1));
    }

    private String lastStoredType(UUID id) {
        return jdbc.sql("SELECT type FROM sessions.session_event WHERE session_id = :id ORDER BY event_id DESC LIMIT 1")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private List<String> storedBodies(UUID id) {
        return jdbc.sql("SELECT payload::text FROM sessions.session_event WHERE session_id = :id ORDER BY event_id")
                .param("id", id)
                .query(String.class)
                .list();
    }

    private List<String> pausedReasons(UUID id) {
        return jdbc.sql("SELECT payload ->> 'reason' FROM sessions.session_event "
                        + "WHERE session_id = :id AND type = 'paused' ORDER BY event_id")
                .param("id", id)
                .query(String.class)
                .list();
    }

    private long eventCount(UUID id, String type) {
        return jdbc.sql("SELECT count(*) FROM sessions.session_event WHERE session_id = :id AND type = :t")
                .param("id", id)
                .param("t", type)
                .query(Long.class)
                .single();
    }

    private boolean playing(UUID id) {
        return jdbc.sql("SELECT coalesce((SELECT playing FROM sessions.player_state WHERE session_id = :id), false)")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    private String status(UUID id) {
        return jdbc.sql("SELECT status FROM sessions.session WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private long step(UUID id) {
        return jdbc.sql("SELECT current_step FROM sessions.session WHERE id = :id")
                .param("id", id)
                .query(Long.class)
                .single();
    }

    private void setStep(UUID id, long step) {
        jdbc.sql("UPDATE sessions.session SET current_step = :s WHERE id = :id")
                .param("s", step)
                .param("id", id)
                .update();
    }

    private void assertConflict(MvcResult result, String code) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(409);
        JsonNode problem = json.readTree(result.getResponse().getContentAsString());
        assertThat(problem.get("code").asString()).isEqualTo(code);
    }

    private MvcResult play(UUID id, String action, Integer speed, UUID key) throws Exception {
        String body = "{\"action\":\"" + action + "\"" + (speed == null ? "" : ",\"speed\":" + speed) + "}";
        return playRaw(id, body, key);
    }

    private MvcResult playRaw(UUID id, String body, UUID key) throws Exception {
        MockHttpServletRequestBuilder builder = withAuth(post("/api/v1/sessions/" + id + "/player"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        return mockMvc.perform(key == null ? builder : builder.header("Idempotency-Key", key.toString()))
                .andReturn();
    }

    private MvcResult httpPost(UUID id, String suffix, String body) throws Exception {
        return postJson(id, suffix, body.isEmpty() ? "{}" : body);
    }

    private MvcResult postJson(UUID id, String suffix, String body) throws Exception {
        return mockMvc.perform(withAuth(post("/api/v1/sessions/" + id + suffix))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult httpGet(UUID id, String suffix) throws Exception {
        return mockMvc.perform(withAuth(get("/api/v1/sessions/" + id + suffix))).andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static String order(boolean blind, String seen, String side, String type, int lots, String limit) {
        String paper = blind ? "\"paperIndex\":0" : "\"paperId\":\"" + Papers.id("SBER") + "\"";
        String price = limit == null ? "" : ",\"limitPrice\":\"" + limit + "\"";
        return seen + paper + ",\"side\":\"" + side + "\",\"type\":\"" + type + "\",\"lots\":" + lots
                + ",\"tif\":\"gtc\"" + price + "}";
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

    private void login() throws Exception {
        login(jdbc.sql("SELECT email FROM identity.user_account WHERE id = :id")
                .param("id", userId)
                .query(String.class)
                .single());
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

    private UUID newSession(boolean blind) {
        return newSessionRecord(blind).id();
    }

    private SessionRecord newSessionRecord(boolean blind) {
        List<PlannedPaper> list = new ArrayList<>();
        for (int i = 0; i < SECIDS.size(); i++) {
            list.add(new PlannedPaper(
                    Papers.id(SECIDS.get(i)), SECIDS.get(i), "Сектор" + i, 10 + i, new BigDecimal("0.01")));
        }
        Snapshot snapshot =
                new Snapshot(UUID.randomUUID(), UUID.randomUUID().toString(), OffsetDateTime.now(ZoneOffset.UTC), "t");
        LocalDate start = LocalDate.of(2022, 3, 14);
        LaunchPlan plan = new LaunchPlan(
                snapshot,
                blind,
                start,
                start.plusDays(26),
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
        UUID id = sessions.create(userId, blind ? StartMode.RANDOM : StartMode.CUSTOM, 20, plan);
        return sessions.reload(id);
    }

    @TestConfiguration
    static class TestWiring {

        @Bean
        @Primary
        FakeWorker fakeWorker() {
            return new FakeWorker();
        }

        @Bean
        @Primary
        ManualTimer manualTimer() {
            return new ManualTimer();
        }
    }
}
