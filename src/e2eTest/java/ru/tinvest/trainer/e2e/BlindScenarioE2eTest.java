package ru.tinvest.trainer.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import ru.tinvest.trainer.e2e.ApiClient.SseEvent;
import ru.tinvest.trainer.e2e.LeakScanner.Kind;
import tools.jackson.databind.JsonNode;

/**
 * Сквозной сценарий слепой сессии с настоящим воркером (backend#4): вход, запуск, снимок, заявки (в том числе отказ),
 * шаги плеера, обрыв SSE и догон по {@code Last-Event-ID} (INV-8), завершение, раскрытие периода, отчёт, история,
 * воспроизведение. До завершения ни один ответ не содержит названий, тикеров, реальных дат и рублей (INV-7); тот же
 * сканер на неслепой сессии находит всё (положительный контроль).
 */
class BlindScenarioE2eTest extends E2eBase {

    private static final Set<BigDecimal> CAPITALS = Set.of(new BigDecimal(Flows.CAPITAL));

    private final LeakScanner scanner = new LeakScanner(E2eEnvironment.FIXTURE, CAPITALS);

    @Test
    void blindSessionFromLoginToReplay() {
        ApiClient api = newUser();
        assertThat(api.get("/api/v1/me").ok().get("role").asString()).isEqualTo("USER");
        api.get("/api/v1/launch/options").ok();

        String id = Flows.createSession(api, Flows.blindRequest(2, "calm"));
        String base = "/api/v1/sessions/" + id;

        JsonNode start = Flows.snapshot(api, id);
        assertThat(start.get("mode").asString()).isEqualTo("BLIND");
        assertThat(start.get("step").asLong()).isZero();
        assertThat(start.get("finished").asBoolean()).isFalse();
        assertThat(start.get("papers")).hasSize(2);
        long totalSteps = start.get("totalSteps").asLong();
        assertThat(totalSteps).isPositive();

        List<SseEvent> firstPart;
        long lastSeen;
        try (ApiClient.Sse sse = api.subscribe(base + "/events", 0L)) {
            // заявка принята, отказ из-за нехватки денег — тоже 200, но с причиной
            JsonNode buy = Flows.place(api, id, Flows.order("\"paperIndex\":0", 0, "buy", "market", 1, null));
            assertThat(buy.get("accepted").asBoolean()).isTrue();
            JsonNode tooMuch =
                    Flows.place(api, id, Flows.order("\"paperIndex\":1", 0, "buy", "market", 100_000_000, null));
            assertThat(tooMuch.get("accepted").asBoolean()).isFalse();
            assertThat(tooMuch.get("reason").asString()).isEqualTo("INSUFFICIENT_CASH");
            // лимитная заявка далеко от рынка живёт, пока её не отменят
            JsonNode limit = Flows.place(
                    api, id, Flows.order("\"paperIndex\":1", 0, "buy", "limit", 1, "\"limitPrice\":\"50.00\""));
            assertThat(limit.get("accepted").asBoolean()).isTrue();
            String limitId = limit.get("orderIds").get(0).asString();

            JsonNode first = Flows.step(api, id);
            assertThat(first.get("step").asLong()).isEqualTo(1);
            assertThat(first.get("stepResult").get("fills")).hasSize(1);
            JsonNode cancelled =
                    api.delete(base + "/orders/" + limitId + "?seenStep=1").ok();
            assertThat(cancelled.get("accepted").asBoolean()).isTrue();
            Flows.step(api, id);

            // два принятых заявки, шаг, отмена, шаг: по событию на каждую команду; отказ событий не даёт
            sse.await(5);
            firstPart = List.copyOf(sse.events());
            lastSeen = firstPart.getLast().id();
        }
        assertIncreasing(firstPart);

        // обрыв: подписчиков нет, время идёт командами; события копятся в журнале событий
        Flows.step(api, id);
        Flows.step(api, id);
        JsonNode beforeReconnect = Flows.snapshot(api, id);
        assertThat(beforeReconnect.get("step").asLong()).isEqualTo(4);

        try (ApiClient.Sse resumed = api.subscribe(base + "/events", lastSeen)) {
            resumed.await(2);
            List<SseEvent> missed = resumed.events();
            assertThat(missed.getFirst().id()).isGreaterThan(lastSeen);
            assertIncreasing(missed);
            List<SseEvent> steps =
                    missed.stream().filter(e -> e.type().equals("step")).toList();
            assertThat(steps).hasSize(2);
            assertThat(steps.getLast().json().get("result").get("step").asLong())
                    .isEqualTo(4);
            // состояние из потока и состояние, восстановленное из журнала, совпадают (INV-8)
            JsonNode streamed = steps.getLast().json().get("result").get("portfolio");
            assertThat(beforeReconnect.get("portfolio")).isEqualTo(streamed);
        }
        // F5: тот же снимок, воркер без состояния
        assertThat(Flows.snapshot(api, id)).isEqualTo(beforeReconnect);

        // до завершения: ничего не раскрыто (INV-7); отчёт и воспроизведение отказывают, в истории — без полей
        // раскрытия
        JsonNode notFinishedReport = api.get(base + "/report").ok();
        assertThat(notFinishedReport.get("status").asString()).isEqualTo("NOT_FINISHED");
        assertThat(api.get(base + "/replay?step=1").ok().get("status").asString())
                .isEqualTo("NOT_FINISHED");
        JsonNode running = api.get("/api/v1/history").ok().get("items").get(0);
        assertThat(running.get("view").asString()).isEqualTo("BLIND_UNFINISHED");
        assertThat(scanner.scanAll(api.responses())).as("утечки до завершения").isEmpty();

        // завершение досрочно, потом отчёт: период и бумаги раскрыты
        JsonNode finished = api.post(base + "/finish", "").ok();
        assertThat(finished.get("endReason").asString()).isEqualTo("early");
        JsonNode report = Flows.report(api, id);
        assertThat(report.get("status").asString()).isEqualTo("READY");
        JsonNode body = report.get("report");
        assertThat(body.get("blind").asBoolean()).isTrue();
        assertThat(body.get("finishedEarly").asBoolean()).isTrue();
        List<String> tickers = new ArrayList<>();
        body.get("papers").forEach(p -> tickers.add(p.get("ticker").asString()));
        assertThat(tickers).hasSize(2).isSubsetOf("SBER", "GAZP", "LKOH");
        assertThat(E2eEnvironment.FIXTURE.days())
                .contains(java.time.LocalDate.parse(
                        body.get("period").get("startDate").asString()));
        assertThat(body.get("humanMetrics").get("initialCapital").asString()).isEqualTo(Flows.CAPITAL);
        assertThat(body.get("backgroundAlgorithmName").asString()).isEqualTo("Эталон платформы");
        assertThat(body.get("algoMetrics").isObject()).isTrue();
        assertThat(body.get("algoMetrics").get("initialCapital").asString()).isEqualTo(Flows.CAPITAL);
        assertThat(body.get("comparison").isObject()).isTrue();
        assertThat(body.get("divergenceSummary").isObject()).isTrue();
        assertThat(body.get("divergences").isArray()).isTrue();
        assertThat(body.get("divergences")).isNotEmpty();
        assertThat(body.get("reverseCheckTrades").isArray()).isTrue();
        assertThat(body.get("equityCurve").get(0).get("algoCapital").isString()).isTrue();
        assertThat(body.get("underwaterCurve").get(0).get("algoDrawdownPct").isString())
                .isTrue();

        JsonNode entry = api.get("/api/v1/history").ok().get("items").get(0);
        assertThat(entry.get("view").asString()).isEqualTo("REVEALED");
        assertThat(entry.get("status").asString()).isEqualTo("finished_early");

        // воспроизведение журнала на шаге 4 совпадает с ареной на том же шаге (INV-8)
        JsonNode replay = api.get(base + "/replay?step=4").ok();
        assertThat(replay.get("status").asString()).isEqualTo("READY");
        JsonNode atFour = replay.get("snapshot");
        assertThat(atFour.get("step").asLong()).isEqualTo(4);
        assertThat(atFour.get("fills").size())
                .isEqualTo(beforeReconnect.get("fills").size());
    }

    @Test
    void scannerFindsEverythingInAnOpenSession() {
        ApiClient api = newUser();
        JsonNode papers = api.get("/api/v1/papers").ok();
        List<String> ids = new ArrayList<>();
        papers.forEach(p -> ids.add(p.get("id").asString()));
        String id = Flows.createOpen(api, day(105).toString(), ids.subList(0, 2));
        Flows.place(api, id, Flows.order("\"paperId\":\"" + ids.get(0) + "\"", 0, "buy", "market", 1, null));
        Flows.step(api, id);
        Flows.snapshot(api, id);
        api.post("/api/v1/sessions/" + id + "/finish", "").ok();
        Flows.report(api, id);
        Set<Kind> found = LeakScanner.kinds(scanner.scanAll(api.responses()));
        // сканер не слепой: в раскрытой сессии находит все виды утечек, иначе проверка слепой ничего не стоит
        Set<Kind> expected = java.util.EnumSet.allOf(Kind.class);
        assertThat(found).containsAll(expected);
    }

    /** Свойство маскировки (INV-7): для любой слепой сессии и любых команд ответы ничего не раскрывают. */
    @Test
    void blindMaskNeverLetsPaperFieldsThrough() {
        Random random = new Random(7);
        ApiClient api = newUser();
        Set<String> createdRegimes = new java.util.TreeSet<>();
        int created = 0;
        for (String regime : List.of("calm", "medium", "stress")) {
            for (int papers = 1; papers <= 3; papers++) {
                ApiClient.Response response = api.post("/api/v1/sessions", Flows.blindRequest(papers, regime));
                if (response.status() != 201) {
                    // категории может не быть в маленьком архиве: отказ тоже ответ и тоже не раскрывает
                    assertThat(response.status()).isEqualTo(422);
                    continue;
                }
                String id = response.json().get("sessionId").asString();
                created++;
                createdRegimes.add(regime);
                for (int i = 0; i < 5; i++) {
                    int paper = random.nextInt(papers);
                    String side = random.nextBoolean() ? "buy" : "sell";
                    long lots = 1 + random.nextInt(3);
                    JsonNode snapshot = Flows.snapshot(api, id);
                    Flows.place(
                            api,
                            id,
                            Flows.order(
                                    "\"paperIndex\":" + paper,
                                    snapshot.get("step").asLong(),
                                    side,
                                    "market",
                                    lots,
                                    null));
                    // отказ формы: бумаги с таким индексом нет
                    api.post(
                            "/api/v1/sessions/" + id + "/orders",
                            Flows.order("\"paperIndex\":99", 0, "buy", "market", 1, null));
                    Flows.step(api, id);
                }
                api.post(
                        "/api/v1/sessions/" + id + "/order-estimate",
                        Flows.order("\"paperIndex\":0", 0, "buy", "market", 1, null)
                                .replace("\"seenStep\":0,", ""));
                api.get("/api/v1/sessions/" + id + "/report");
                api.get("/api/v1/sessions/" + id + "/replay");
                api.get("/api/v1/history");
            }
        }
        // свойство не должно пройти вхолостую: сессий создано достаточно, и категории представлены все
        assertThat(created).as("созданных слепых сессий").isGreaterThanOrEqualTo(6);
        assertThat(createdRegimes).containsExactly("calm", "medium", "stress");
        assertThat(scanner.scanAll(api.responses())).isEmpty();
    }

    private static void assertIncreasing(List<SseEvent> events) {
        long previous = 0;
        for (SseEvent event : events) {
            assertThat(event.id()).isGreaterThan(previous);
            assertThat(event.json().get("eventId").asString()).isEqualTo(Long.toString(event.id()));
            previous = event.id();
        }
    }
}
