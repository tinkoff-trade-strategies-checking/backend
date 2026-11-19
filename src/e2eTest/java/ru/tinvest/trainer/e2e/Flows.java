package ru.tinvest.trainer.e2e;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Общие запросы сквозных сценариев: создание сессии, заявки, шаг, ожидание отчёта. */
final class Flows {

    static final String CAPITAL = "10000000.00";

    private Flows() {}

    static String blindRequest(int paperCount, String regime) {
        return "{\"traderKind\":\"human\",\"durationDays\":20,\"timeframe\":\"1d\",\"regime\":\"" + regime
                + "\",\"startMode\":\"random\",\"customStartDate\":null,\"paperCount\":" + paperCount
                + ",\"paperIds\":null,\"initialCapital\":\"" + CAPITAL + "\"}";
    }

    static String openRequest(String startDate, List<String> paperIds, String regime) {
        String ids = paperIds.stream()
                .map(id -> "\"" + id + "\"")
                .toList()
                .toString()
                .replace(", ", ",");
        return "{\"traderKind\":\"human\",\"durationDays\":20,\"timeframe\":\"1d\",\"regime\":\"" + regime
                + "\",\"startMode\":\"custom\","
                + "\"customStartDate\":\"" + startDate + "\",\"paperCount\":null,\"paperIds\":" + ids
                + ",\"initialCapital\":\"" + CAPITAL + "\"}";
    }

    /** Своя дата: категория окна заранее неизвестна, перебираем (неподходящая — 422 `NO_MATCHING_PERIOD`). */
    static String createOpen(ApiClient api, String startDate, List<String> paperIds) {
        for (String regime : List.of("calm", "medium", "stress")) {
            ApiClient.Response response = api.post("/api/v1/sessions", openRequest(startDate, paperIds, regime));
            if (response.status() == 201) {
                return response.json().get("sessionId").asString();
            }
        }
        throw new AssertionError("окно с " + startDate + " не подошло ни под одну категорию");
    }

    static String createSession(ApiClient api, String request) {
        return api.post("/api/v1/sessions", request).ok().get("sessionId").asString();
    }

    /** Фрагмент JSON с бумагой: {@code "paperIndex":0} (слепая) или {@code "paperId":"..."} (раскрытая). */
    static String order(String paper, long seenStep, String side, String type, long lots, String extra) {
        return "{\"seenStep\":" + seenStep + "," + paper + ",\"side\":\"" + side + "\",\"type\":\"" + type
                + "\",\"lots\":" + lots + ",\"tif\":\"gtc\"" + (extra == null ? "" : "," + extra) + "}";
    }

    static JsonNode place(ApiClient api, String sessionId, String body) {
        return api.post("/api/v1/sessions/" + sessionId + "/orders", body).ok();
    }

    static JsonNode step(ApiClient api, String sessionId) {
        return api.post("/api/v1/sessions/" + sessionId + "/player", "{\"action\":\"step\"}", UUID.randomUUID())
                .ok();
    }

    static JsonNode snapshot(ApiClient api, String sessionId) {
        return api.get("/api/v1/sessions/" + sessionId).ok();
    }

    /** Отчёт: ждёт, пока сборка (BUILDING) закончится. */
    static JsonNode report(ApiClient api, String sessionId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode result =
                    api.get("/api/v1/sessions/" + sessionId + "/report").ok();
            if (!"BUILDING".equals(result.get("status").asString())) {
                return result;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("отчёт не построен за 60 с");
    }
}
