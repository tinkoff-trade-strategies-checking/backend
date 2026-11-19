package ru.tinvest.trainer.marketdata.importer;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Минимальный клиент HTTP-интерфейса ClickHouse (порт 8123): SQL в теле POST, аутентификация заголовками. */
final class ClickHouse {

    private final HttpClient http;
    private final String url;
    private final String database;
    private final String user;
    private final String password;

    ClickHouse(HttpClient http, String url, String database, String user, String password) {
        this.http = http;
        this.url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.database = database;
        this.user = user;
        this.password = password;
    }

    String database() {
        return database;
    }

    /** Выполняет запрос и возвращает тело ответа (для SELECT — с {@code FORMAT TSV}). */
    String execute(String sql) {
        URI uri = URI.create(url + "/?database=" + URLEncoder.encode(database, StandardCharsets.UTF_8));
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(10))
                .POST(HttpRequest.BodyPublishers.ofString(sql, StandardCharsets.UTF_8));
        if (!user.isEmpty()) {
            b.header("X-ClickHouse-User", user).header("X-ClickHouse-Key", password);
        }
        try {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() != 200) {
                String body = r.body();
                throw new ImportException(
                        "ClickHouse HTTP " + r.statusCode() + ": " + body.substring(0, Math.min(body.length(), 500)));
            }
            return r.body();
        } catch (IOException e) {
            throw new ImportException("ClickHouse недоступен: " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImportException("Импорт прерван", e);
        }
    }

    long count(String sql) {
        return Long.parseLong(execute(sql + " FORMAT TSV").trim());
    }

    static String str(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    static String num(BigDecimal d) {
        return d.toPlainString();
    }
}
