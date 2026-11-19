package ru.tinvest.trainer.marketdata.importer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Поддельные MOEX ISS и сайт ЦБ РФ на JDK HttpServer: формат ответов — как у настоящих (образцы в
 * {@code src/test/resources/iss}), сети нет. Торговые дни — будни с 2024-02-26 по 2024-03-12, в день по три минутные
 * свечи.
 */
final class FakeIssServer implements AutoCloseable {

    static final int PAGE = 5;
    static final int CANDLES_PER_DAY = 3;
    static final LocalDate FIRST = LocalDate.of(2024, 2, 12);
    static final LocalDate LAST = LocalDate.of(2024, 3, 12);

    private final HttpServer server;
    final List<String> requests = new CopyOnWriteArrayList<>();
    /** Сколько ближайших запросов, чей путь содержит ключ, ответить HTTP 500. */
    final Map<String, AtomicInteger> failures = new LinkedHashMap<>();
    /** Сколько ближайших запросов, чей путь содержит ключ, ответить пустыми данными. */
    final Map<String, AtomicInteger> empties = new LinkedHashMap<>();
    /** Пути, на которые отвечать HTTP 500 всегда, пока ключ в наборе. */
    final java.util.Set<String> broken = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Дни без свечей в ответе, ключ {@code SECID|yyyy-MM-dd}. */
    final java.util.Set<String> skipDays = java.util.concurrent.ConcurrentHashMap.newKeySet();

    final List<String> userAgents = new CopyOnWriteArrayList<>();
    /** Дни, где отдаётся только начало дня (обрыв посреди дня), ключ {@code SECID|yyyy-MM-dd}. */
    final java.util.Set<String> truncateDays = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Период торгов TQBR по секции boards: secid → {from, till}; по умолчанию весь период теста. */
    final Map<String, LocalDate[]> listings = new java.util.concurrent.ConcurrentHashMap<>();

    FakeIssServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String issUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/iss";
    }

    String cbrUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/cbr/ruonia";
    }

    static List<LocalDate> tradingDays() {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = FIRST; !d.isAfter(LAST); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days.add(d);
            }
        }
        return days;
    }

    long requestsContaining(String part) {
        return requests.stream().filter(r -> r.contains(part)).count();
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String raw = ex.getRequestURI().getRawQuery();
        requests.add(path + (raw == null ? "" : "?" + raw));
        userAgents.add(String.valueOf(ex.getRequestHeaders().getFirst("User-Agent")));
        Map<String, String> q = query(raw);
        String key = path + (raw == null ? "" : "?" + raw);
        if (broken.stream().anyMatch(key::contains)) {
            respond(ex, 500, "ошибка");
            return;
        }
        for (Map.Entry<String, AtomicInteger> f : failures.entrySet()) {
            if (key.contains(f.getKey()) && f.getValue().getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                respond(ex, 500, "ошибка");
                return;
            }
        }
        boolean empty = false;
        for (Map.Entry<String, AtomicInteger> f : empties.entrySet()) {
            if (key.contains(f.getKey()) && f.getValue().getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                empty = true;
            }
        }
        String body;
        if (path.startsWith("/cbr/ruonia")) {
            respond(ex, 200, ruoniaHtml(q));
            return;
        } else if (path.endsWith("/candles.json") && path.contains("/boards/TQBR/")) {
            String secid = path.substring(path.indexOf("/securities/") + 12, path.indexOf("/candles"));
            body = candles(secid, q, empty, false);
        } else if (path.matches("/iss/securities/[A-Z]+\\.json")) {
            String secid = path.substring(path.lastIndexOf('/') + 1, path.length() - 5);
            LocalDate[] l =
                    listings.getOrDefault(secid, new LocalDate[] {LocalDate.of(2000, 1, 1), LocalDate.of(2030, 1, 1)});
            body = "{\"boards\": {\"columns\": [\"boardid\", \"listed_from\", \"listed_till\"], \"data\": "
                    + "[[\"SMAL\", \"2011-11-21\", \"2025-08-01\"], [\"TQBR\", \"" + l[0] + "\", \"" + l[1] + "\"]]}}";
        } else if (path.endsWith("/candles.json") && path.contains("/boards/SNDX/")) {
            body = "10".equals(q.get("interval")) ? imoexIntraday(q, empty) : candles("IMOEX", q, empty, true);
        } else if (path.contains("/analytics/")) {
            String index = path.substring(path.lastIndexOf('/') + 1, path.length() - 5);
            body = analytics(index, q, empty);
        } else if (path.contains("/boards/TQBR/securities/")) {
            String secid = path.substring(path.lastIndexOf('/') + 1, path.length() - 5);
            body = security(secid);
        } else if (path.endsWith("/splits.json")) {
            body = "{\"splits\": {\"columns\": [\"tradedate\", \"secid\", \"before\", \"after\"], \"data\": "
                    + "[[\"2024-02-28\", \"SBER\", 1, 10], [\"2024-02-29\", \"NOPE\", 1, 2], "
                    + "[\"2024-03-05\", \"GAZP\", 5, 1]]}}";
        } else {
            respond(ex, 404, "нет");
            return;
        }
        respond(ex, 200, body);
    }

    /** Цена свечи зависит от бумаги и номера дня, чтобы тест мог сверить значения. */
    static String price(String secid, int dayIndex, int k) {
        int base = secid.equals("SBER") ? 290 : secid.equals("GAZP") ? 160 : 3200;
        return (base + dayIndex) + "." + (10 + k);
    }

    private String candles(String secid, Map<String, String> q, boolean empty, boolean daily) {
        LocalDate from = LocalDate.parse(q.get("from"));
        LocalDate till = LocalDate.parse(q.get("till"));
        int start = Integer.parseInt(q.getOrDefault("start", "0"));
        List<String> rows = new ArrayList<>();
        List<LocalDate> days = tradingDays();
        for (int i = 0; i < days.size(); i++) {
            LocalDate d = days.get(i);
            if (d.isBefore(from) || d.isAfter(till) || skipDays.contains(secid + "|" + d)) {
                continue;
            }
            if (daily) {
                rows.add("[%s, %s, %s, %s, 59649504551.5, 0, \"%s 00:00:00\", \"%s 23:59:59\"]"
                        .formatted(
                                price(secid, i, 0), price(secid, i, 1), price(secid, i, 2), price(secid, i, 0), d, d));
                continue;
            }
            int perDay = truncateDays.contains(secid + "|" + d) ? CANDLES_PER_DAY - 1 : CANDLES_PER_DAY;
            for (int k = 0; k < perDay; k++) {
                rows.add("[%s, %s, %s, %s, 40851055.69999998, %d, \"%s 10:0%d:00\", \"%s 10:0%d:59\"]"
                        .formatted(
                                price(secid, i, 0),
                                price(secid, i, 1),
                                price(secid, i, 2),
                                price(secid, i, 0),
                                1000 + k,
                                d,
                                k,
                                d,
                                k));
            }
        }
        int pageSize = PAGE;
        List<String> page = empty || start >= rows.size()
                ? List.of()
                : rows.subList(start, Math.min(rows.size(), start + pageSize));
        return "{\"candles\": {\"columns\": [\"open\", \"close\", \"high\", \"low\", \"value\", \"volume\", \"begin\", "
                + "\"end\"], \"data\": [" + String.join(", ", page) + "]}}";
    }

    /** Одна 10-минутная свеча IMOEX в день, начало 10:10 МСК: бумага должна торговаться до 10:02 (допуск 8 минут в тестах). */
    private String imoexIntraday(Map<String, String> q, boolean empty) {
        LocalDate from = LocalDate.parse(q.get("from"));
        LocalDate till = LocalDate.parse(q.get("till"));
        int start = Integer.parseInt(q.getOrDefault("start", "0"));
        List<String> rows = new ArrayList<>();
        for (LocalDate d : tradingDays()) {
            if (!d.isBefore(from) && !d.isAfter(till)) {
                rows.add("[100, 100, 100, 100, 1.5, 0, \"" + d + " 10:10:00\", \"" + d + " 10:19:59\"]");
            }
        }
        List<String> page =
                empty || start >= rows.size() ? List.of() : rows.subList(start, Math.min(rows.size(), start + PAGE));
        return "{\"candles\": {\"columns\": [\"open\", \"close\", \"high\", \"low\", \"value\", \"volume\", "
                + "\"begin\", \"end\"], \"data\": [" + String.join(", ", page) + "]}}";
    }

    private static final Map<String, List<String>> SECTORS = Map.of(
            "MOEXFN", List.of("SBER"),
            "MOEXOG", List.of("GAZP", "SBER"),
            "MOEXIT", List.of());

    private String analytics(String index, Map<String, String> q, boolean empty) {
        List<String> secids = index.equals("IMOEX")
                ? List.of("SBER", "GAZP", "TINY", "GONE")
                : SECTORS.getOrDefault(index, List.of());
        int start = Integer.parseInt(q.getOrDefault("start", "0"));
        int size = 2;
        List<String> page = empty || start >= secids.size()
                ? List.of()
                : secids.subList(start, Math.min(secids.size(), start + size));
        String rows = page.stream()
                .map(s -> "[\"" + index + "\", \"2024-03-12\", \"" + s + "\", \"x\", \"" + s + "\", 1.0, 3, null]")
                .collect(Collectors.joining(", "));
        return "{\"analytics\": {\"columns\": [\"indexid\", \"tradedate\", \"ticker\", \"shortnames\", \"secids\", "
                + "\"weight\", \"tradingsession\", \"trade_session_date\"], \"data\": [" + rows + "]}, "
                + "\"analytics.cursor\": {\"columns\": [\"INDEX\", \"TOTAL\", \"PAGESIZE\", \"PREV_DATE\", "
                + "\"NEXT_DATE\"], \"data\": [[0, " + secids.size() + ", " + size + ", \"2024-03-11\", null]]}}";
    }

    private String security(String secid) {
        String data = switch (secid) {
            case "SBER" -> "[\"SBER\", 1, 0.01]";
            case "GAZP" -> "[\"GAZP\", 10, 0.01]";
            case "TINY" -> "[\"TINY\", 1, 0.0000001]";
            default -> null;
        };
        return "{\"securities\": {\"columns\": [\"SECID\", \"LOTSIZE\", \"MINSTEP\"], \"data\": ["
                + (data == null ? "" : data) + "]}}";
    }

    private static String ruoniaHtml(Map<String, String> q) {
        StringBuilder sb = new StringBuilder("<html><table class=\"data\"><tr><th>Дата</th><th>Ставка</th></tr>");
        for (LocalDate d : tradingDays()) {
            sb.append("<tr> <td>%02d.%02d.%d</td> <td class=\"right\">20,%02d</td> </tr>"
                    .formatted(d.getDayOfMonth(), d.getMonthValue(), d.getYear(), d.getDayOfMonth()));
        }
        return sb.append("</table></html>").toString();
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> q = new LinkedHashMap<>();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int i = pair.indexOf('=');
                q.put(
                        URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return q;
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
