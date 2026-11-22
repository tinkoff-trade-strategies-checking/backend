package ru.tinvest.trainer.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Sheet;
import org.junit.jupiter.api.Test;
import ru.tinvest.trainer.e2e.ApiClient.BinaryResponse;
import ru.tinvest.trainer.e2e.ApiClient.Response;
import ru.tinvest.trainer.e2e.ApiClient.SseEvent;
import tools.jackson.databind.JsonNode;

/**
 * Сквозной сценарий к предзащите (backend#58) против настоящего C++ {@code simulation-worker}, PostgreSQL и ClickHouse:
 * мастерская стратегий (проверка DSL, линтер {@code L001} с автофиксом, запрет {@code news} по INV-9 и
 * {@code close[-1]} по {@code E-LA01}, публикация неизменяемой версии по INV-5, форк UC-20, скрытие UC-21),
 * фоновые прогоны алгоритмов с выгрузкой и побитовым повтором (UC-14, UC-18, INV-4) и ручная сессия с выбором
 * {@code backgroundAlgorithmId} и разбором расхождений (ФТ-18…20, INV-3).
 */
class WorkshopAndRunsE2eTest extends E2eBase {

    private static final String MAGIC_DSL = """
            rule "magic_entry"
              when position == 0 and change(close, 5) > 0
              then buy 1 lots because "импульс за 5 баров"
            """;

    private static final String NEWS_DSL = """
            rule "news_rule"
              when news > 0
              then buy 1 lots because "торговля по новостям"
            """;

    private static final String LOOKAHEAD_DSL = """
            rule "future_peek"
              when close[-1] > close
              then buy 1 lots because "чтение будущего"
            """;

    private static final String VALID_DSL = """
            param fast = 3 range 2..10 step 1
            param lot_qty = 1
            rule "entry_up"
              when position == 0 and close > sma(close, fast)
              then buy lot_qty lots because "цена выше быстрой SMA"
            rule "exit_down"
              when position > 0 and close < sma(close, fast)
              then sell all because "цена ниже быстрой SMA"
            """;

    @Test
    void workshopAlgoRunRepeatAndBackgroundSession() throws IOException {
        ApiClient author = newUser();
        ApiClient peer = newUser();

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String algoName = "SMA-Impulse-" + suffix;

        // 1. Мастерская: черновик с магической константой (L001 с fix), проверка news (INV-9) и close[-1] (E-LA01)
        JsonNode draft = author.post(
                        "/api/v1/workshop/algorithms",
                        json(Map.of(
                                "name", algoName,
                                "description", "Сквозная стратегия для предзащиты",
                                "source", MAGIC_DSL)))
                .ok();
        String strategyId = draft.get("strategyId").asString();
        assertThat(draft.get("latestVersion").asInt()).isZero();
        assertThat(draft.get("visibility").asString()).isEqualTo("public");

        JsonNode magicCheck = author.post("/api/v1/workshop/check", json(Map.of("source", MAGIC_DSL)))
                .ok();
        assertThat(magicCheck.get("lookaheadLevel").asString()).isEqualTo("OK");
        JsonNode l001 = findDiagnostic(magicCheck.get("diagnostics"), "L001");
        assertThat(l001.get("level").asString()).isEqualTo("WARNING");
        assertThat(l001.get("fixes")).isNotEmpty();
        assertThat(l001.get("fixes").get(0).get("edits")).isNotEmpty();

        JsonNode newsCheck = author.post("/api/v1/workshop/check", json(Map.of("source", NEWS_DSL)))
                .ok();
        JsonNode newsDiag = findDiagnostic(newsCheck.get("diagnostics"), "E-TYPE");
        assertThat(newsDiag.get("level").asString()).isEqualTo("ERROR");

        JsonNode lookaheadCheck = author.post("/api/v1/workshop/check", json(Map.of("source", LOOKAHEAD_DSL)))
                .ok();
        assertThat(lookaheadCheck.get("lookaheadLevel").asString()).isEqualTo("ERROR");
        JsonNode la01 = findDiagnostic(lookaheadCheck.get("diagnostics"), "E-LA01");
        assertThat(la01.get("level").asString()).isEqualTo("ERROR");

        // Попытка публикации невалидной стратегии с close[-1] или news -> 422 STRATEGY_INVALID
        author.put("/api/v1/workshop/algorithms/" + strategyId + "/draft", json(Map.of("source", LOOKAHEAD_DSL)))
                .ok();
        Response failedLookaheadPublish = author.post(
                "/api/v1/workshop/algorithms/" + strategyId + "/publish", json(Map.of("summary", "invalid")));
        assertThat(failedLookaheadPublish.status()).isEqualTo(422);
        assertThat(failedLookaheadPublish.json().get("code").asString()).isEqualTo("STRATEGY_INVALID");
        assertThat(failedLookaheadPublish.json().get("lookaheadLevel").asString())
                .isEqualTo("ERROR");
        assertThat(failedLookaheadPublish.json().get("diagnostics")).isNotEmpty();

        Response failedNewsPublish = author.post(
                "/api/v1/workshop/algorithms/" + strategyId + "/publish",
                json(Map.of("summary", "news", "source", NEWS_DSL)));
        assertThat(failedNewsPublish.status()).isEqualTo(422);
        assertThat(failedNewsPublish.json().get("code").asString()).isEqualTo("STRATEGY_INVALID");
        findDiagnostic(failedNewsPublish.json().get("diagnostics"), "E-TYPE");

        // Сохранение и публикация валидной стратегии с параметром fast -> v1 (INV-5)
        author.put("/api/v1/workshop/algorithms/" + strategyId + "/draft", json(Map.of("source", VALID_DSL)))
                .ok();
        JsonNode published = author.post(
                        "/api/v1/workshop/algorithms/" + strategyId + "/publish",
                        json(Map.of("summary", "Первая чистая версия")))
                .ok();
        String versionId = published.get("id").asString();
        assertThat(published.get("version").asInt()).isEqualTo(1);
        assertThat(published.get("immutable").asBoolean()).isTrue();
        assertThat(published.get("lookaheadLevel").asString()).isEqualTo("OK");
        assertThat(published.get("diagnostics")).isEmpty();
        assertThat(findParam(published.get("params"), "fast")
                        .get("defaultValue")
                        .asDouble())
                .isEqualTo(3.0);

        // Правка черновика не затрагивает опубликованную v1 (INV-5)
        author.put("/api/v1/workshop/algorithms/" + strategyId + "/draft", json(Map.of("source", MAGIC_DSL)))
                .ok();
        JsonNode detail =
                author.get("/api/v1/workshop/algorithms/" + strategyId).ok();
        assertThat(detail.get("status").asString()).isEqualTo("READY");
        JsonNode algo = detail.get("algorithm");
        assertThat(algo.get("latestVersion").asInt()).isEqualTo(1);
        assertThat(algo.get("latestVersionId").asString()).isEqualTo(versionId);
        assertThat(algo.get("versions").get(0).get("immutable").asBoolean()).isTrue();
        assertThat(algo.get("versions").get(0).get("source").asString()).isEqualTo(VALID_DSL);

        // Форк другим пользователем (UC-20) и скрытие из каталога (UC-21)
        JsonNode forked = peer.post("/api/v1/workshop/algorithms/" + strategyId + "/fork", "")
                .ok();
        assertThat(forked.get("source").asString()).isEqualTo(VALID_DSL);
        assertThat(forked.get("forkedFrom").get("strategyId").asString()).isEqualTo(strategyId);
        assertThat(forked.get("forkedFrom").get("versionId").asString()).isEqualTo(versionId);
        assertThat(forked.get("forkedFrom").get("version").asInt()).isEqualTo(1);

        JsonNode hidden = author.patch(
                        "/api/v1/workshop/algorithms/" + strategyId + "/visibility",
                        json(Map.of("visibility", "hidden")))
                .ok();
        assertThat(hidden.get("visibility").asString()).isEqualTo("hidden");

        JsonNode peerHiddenView =
                peer.get("/api/v1/workshop/algorithms/" + strategyId).ok();
        assertThat(peerHiddenView.get("status").asString()).isEqualTo("NOT_FOUND");
        assertThat(peerHiddenView.hasNonNull("algorithm")).isFalse();
        assertThat(peer.post("/api/v1/workshop/algorithms/" + strategyId + "/fork", "")
                        .status())
                .isEqualTo(404);
        assertThat(peer.post(
                                "/api/v1/runs",
                                json(Map.of(
                                        "strategyVersionId",
                                        versionId,
                                        "durationDays",
                                        20,
                                        "timeframe",
                                        "1d",
                                        "regime",
                                        "calm",
                                        "startMode",
                                        "custom",
                                        "customStartDate",
                                        day(105).toString(),
                                        "paperIds",
                                        List.of(),
                                        "initialCapital",
                                        Flows.CAPITAL)))
                        .status())
                .isEqualTo(404);

        JsonNode restored = author.patch(
                        "/api/v1/workshop/algorithms/" + strategyId + "/visibility",
                        json(Map.of("visibility", "public")))
                .ok();
        assertThat(restored.get("visibility").asString()).isEqualTo("public");
        assertThat(peer.get("/api/v1/workshop/algorithms/" + strategyId)
                        .ok()
                        .get("status")
                        .asString())
                .isEqualTo("READY");

        // 2. Прогон алгоритма с paramOverrides и повтор (UC-14, UC-18, INV-4)
        Map<String, String> paperIds = paperIdsByTicker(author);
        List<String> selectedPapers = List.of(paperIds.get("SBER"), paperIds.get("GAZP"));
        String startDate = day(105).toString();

        String runId1 = createRun(author, versionId, Map.of("fast", 4.0), startDate, selectedPapers);
        JsonNode run1Info = awaitRunFinished(author, runId1);
        assertThat(run1Info.get("status").asString()).isEqualTo("completed");
        assertThat(run1Info.get("doneSteps").asLong()).isEqualTo(20L);
        assertThat(run1Info.get("totalSteps").asLong()).isEqualTo(20L);

        try (ApiClient.Sse runSse = author.subscribe("/api/v1/runs/" + runId1 + "/events", null)) {
            runSse.await(3);
            List<String> eventTypes =
                    runSse.events().stream().map(SseEvent::type).toList();
            assertThat(eventTypes).contains("run_finished", "report_ready");
        }

        JsonNode runReportWrapper =
                author.get("/api/v1/runs/" + runId1 + "/report").ok();
        assertThat(runReportWrapper.get("status").asString()).isEqualTo("READY");
        JsonNode runReport = runReportWrapper.get("report");
        assertThat(runReport.get("level").asString()).isEqualTo("OK");
        assertThat(runReport.get("algoName").asString()).isEqualTo(algoName);
        assertThat(runReport.get("metrics").get("initialCapital").asString()).isEqualTo(Flows.CAPITAL);
        assertThat(runReport.get("metrics").get("finalCapital").asString()).isNotBlank();
        assertThat(runReport.get("fills")).isNotEmpty();
        assertThat(runReport.get("orders")).isNotEmpty();
        for (JsonNode ord : runReport.get("orders")) {
            assertThat(ord.get("ruleName").asString()).isIn("entry_up", "exit_down");
            assertThat(ord.get("because").asString()).isNotBlank();
        }

        BinaryResponse runXlsx = author.getBytes("/api/v1/runs/" + runId1 + "/report/export?format=xlsx");
        assertThat(runXlsx.status()).isEqualTo(200);
        assertThat(runXlsx.body()).isNotEmpty();
        assertThat(readSheetNames(runXlsx.body())).contains("Сводка", "Метрики", "Заявки");

        JsonNode historyRuns = author.get("/api/v1/history?kind=algo_run").ok();
        assertThat(historyRuns.get("items")).anySatisfy(item -> {
            assertThat(item.get("id").asString()).isEqualTo(runId1);
            assertThat(item.get("kind").asString()).isEqualTo("algo_run");
            assertThat(item.get("status").asString()).isEqualTo("completed");
            assertThat(item.get("view").asString()).isEqualTo("REVEALED");
        });

        String runId2 = author.post("/api/v1/runs/" + runId1 + "/repeat", "")
                .ok()
                .get("runId")
                .asString();
        JsonNode run2Info = awaitRunFinished(author, runId2);
        assertThat(run2Info.get("status").asString()).isEqualTo("completed");
        assertThat(run2Info.get("repeatOfRunId").asString()).isEqualTo(runId1);
        assertThat(run2Info.get("repeatMatch").asBoolean()).isTrue();

        JsonNode run2Report =
                author.get("/api/v1/runs/" + runId2 + "/report").ok().get("report");
        assertThat(run2Report.get("repeatMatch").asBoolean()).isTrue();
        assertThat(run2Report.get("metrics").get("finalCapital").asString())
                .isEqualTo(runReport.get("metrics").get("finalCapital").asString());
        assertThat(run2Report.get("fills")).hasSameSizeAs(runReport.get("fills"));

        // 3. Ручная сессия с backgroundAlgorithmId и разбор расхождений (ФТ-18…20, INV-3)
        String sberId = paperIds.get("SBER");
        String gazpId = paperIds.get("GAZP");
        String sessionId = createSessionWithBackground(author, startDate, selectedPapers, strategyId);

        JsonNode buySber = Flows.place(
                author, sessionId, Flows.order("\"paperId\":\"" + sberId + "\"", 0, "buy", "market", 2, null));
        assertThat(buySber.get("accepted").asBoolean()).isTrue();
        Flows.step(author, sessionId);
        Flows.step(author, sessionId);

        JsonNode sellSber = Flows.place(
                author, sessionId, Flows.order("\"paperId\":\"" + sberId + "\"", 2, "sell", "market", 1, null));
        assertThat(sellSber.get("accepted").asBoolean()).isTrue();
        JsonNode buyGazp = Flows.place(
                author, sessionId, Flows.order("\"paperId\":\"" + gazpId + "\"", 2, "buy", "market", 1, null));
        assertThat(buyGazp.get("accepted").asBoolean()).isTrue();
        Flows.step(author, sessionId);
        Flows.step(author, sessionId);

        JsonNode finished =
                author.post("/api/v1/sessions/" + sessionId + "/finish", "").ok();
        assertThat(finished.get("endReason").asString()).isEqualTo("early");

        JsonNode sessionReportWrapper = Flows.report(author, sessionId);
        assertThat(sessionReportWrapper.get("status").asString()).isEqualTo("READY");
        JsonNode sessionReport = sessionReportWrapper.get("report");
        assertThat(sessionReport.get("backgroundAlgorithmName").asString()).isEqualTo(algoName);
        assertThat(sessionReport.get("algoMetrics").isObject()).isTrue();
        assertThat(sessionReport.get("algoMetrics").get("initialCapital").asString())
                .isEqualTo(Flows.CAPITAL);
        assertThat(sessionReport.get("comparison").isObject()).isTrue();
        assertThat(sessionReport.get("equityCurve")).isNotEmpty();
        assertThat(sessionReport.get("equityCurve"))
                .allSatisfy(
                        point -> assertThat(point.get("algoCapital").asString()).isNotBlank());
        assertThat(sessionReport.get("divergences")).isNotEmpty();
        assertThat(sessionReport.get("divergences")).allSatisfy(div -> {
            assertThat(div.get("errorCostPct").asString()).isNotBlank();
            assertThat(div.get("because").asString()).isNotBlank();
        });

        BinaryResponse sessionXlsx = author.getBytes("/api/v1/sessions/" + sessionId + "/report/export?format=xlsx");
        assertThat(sessionXlsx.status()).isEqualTo(200);
        assertThat(sessionXlsx.body()).isNotEmpty();
        assertThat(readSheetNames(sessionXlsx.body())).contains("Сводка", "Метрики", "Расхождения");
    }

    private static Map<String, String> paperIdsByTicker(ApiClient api) {
        JsonNode papers = api.get("/api/v1/papers").ok();
        Map<String, String> ids = new LinkedHashMap<>();
        papers.forEach(p -> ids.put(p.get("ticker").asString(), p.get("id").asString()));
        return ids;
    }

    private static String createRun(
            ApiClient api,
            String versionId,
            Map<String, Double> paramOverrides,
            String startDate,
            List<String> paperIds) {
        for (String regime : List.of("calm", "medium", "stress")) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("strategyVersionId", versionId);
            body.put("paramOverrides", paramOverrides);
            body.put("blind", false);
            body.put("durationDays", 20);
            body.put("timeframe", "1d");
            body.put("regime", regime);
            body.put("startMode", "custom");
            body.put("customStartDate", startDate);
            body.put("paperCount", null);
            body.put("paperIds", paperIds);
            body.put("initialCapital", Flows.CAPITAL);
            Response response = api.post("/api/v1/runs", json(body));
            if (response.status() == 201) {
                return response.json().get("runId").asString();
            }
        }
        throw new AssertionError("окно прогона с " + startDate + " не подошло ни под одну категорию");
    }

    private static String createSessionWithBackground(
            ApiClient api, String startDate, List<String> paperIds, String backgroundAlgorithmId) {
        for (String regime : List.of("calm", "medium", "stress")) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("traderKind", "human");
            body.put("durationDays", 20);
            body.put("timeframe", "1d");
            body.put("regime", regime);
            body.put("startMode", "custom");
            body.put("customStartDate", startDate);
            body.put("paperCount", null);
            body.put("paperIds", paperIds);
            body.put("initialCapital", Flows.CAPITAL);
            body.put("backgroundAlgorithmId", backgroundAlgorithmId);
            Response response = api.post("/api/v1/sessions", json(body));
            if (response.status() == 201) {
                return response.json().get("sessionId").asString();
            }
        }
        throw new AssertionError("окно сессии с " + startDate + " не подошло ни под одну категорию");
    }

    private static JsonNode awaitRunFinished(ApiClient api, String runId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode info = api.get("/api/v1/runs/" + runId).ok();
            String status = info.get("status").asString();
            if (!"queued".equals(status) && !"running".equals(status)) {
                return info;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("прогон " + runId + " не завершился за 60 с");
    }

    private static JsonNode findDiagnostic(JsonNode diagnostics, String code) {
        for (JsonNode d : diagnostics) {
            if (code.equals(d.get("code").asString())) {
                return d;
            }
        }
        throw new AssertionError("не найдена диагностика " + code + " в " + diagnostics);
    }

    private static JsonNode findParam(JsonNode params, String name) {
        for (JsonNode p : params) {
            if (name.equals(p.get("name").asString())) {
                return p;
            }
        }
        throw new AssertionError("не найден параметр " + name + " в " + params);
    }

    private static List<String> readSheetNames(byte[] xlsx) throws IOException {
        try (ReadableWorkbook wb = new ReadableWorkbook(new ByteArrayInputStream(xlsx))) {
            return wb.getSheets().map(Sheet::getName).toList();
        }
    }

    private static String json(Object value) {
        return ApiClient.JSON.writeValueAsString(value);
    }
}
