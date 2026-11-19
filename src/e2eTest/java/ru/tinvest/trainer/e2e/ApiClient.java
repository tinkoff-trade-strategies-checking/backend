package ru.tinvest.trainer.e2e;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP-клиент пользователя к настоящему серверу: свои cookie (сессионная cookie {@code Secure}, а JDK не отдаёт такие
 * по http) и заголовок CSRF. Каждый ответ запоминается в {@link #responses()}: проверка слепого режима обходит их все.
 */
final class ApiClient {

    static final ObjectMapper JSON = JsonMapper.builder().build();

    /** Ответ сервера: статус, тело и разобранный JSON (нет, если тело не JSON). */
    record Response(String method, String path, int status, String body, JsonNode json) {

        JsonNode ok() {
            if (status != 200 && status != 201) {
                throw new AssertionError(method + " " + path + " -> " + status + ": " + body);
            }
            return json;
        }
    }

    /** Событие SSE. */
    record SseEvent(long id, String type, String data, JsonNode json) {}

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String base;
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private final List<Response> responses = new CopyOnWriteArrayList<>();

    ApiClient(int port) {
        this.base = "http://localhost:" + port;
    }

    List<Response> responses() {
        return responses;
    }

    void login(String email, String password) {
        // токен CSRF для входа выдаёт любой запрос, в том числе 401 на /me
        request("GET", "/api/v1/me", null, Map.of());
        Response response = request(
                "POST",
                "/api/v1/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}",
                Map.of());
        if (response.status() != 204) {
            throw new AssertionError("вход: " + response.status() + " " + response.body());
        }
    }

    Response get(String path) {
        return request("GET", path, null, Map.of());
    }

    Response post(String path, String body) {
        return request("POST", path, body, Map.of());
    }

    Response post(String path, String body, UUID idempotencyKey) {
        return request("POST", path, body, Map.of("Idempotency-Key", idempotencyKey.toString()));
    }

    Response delete(String path) {
        return request("DELETE", path, null, Map.of());
    }

    private synchronized Response request(String method, String path, String body, Map<String, String> extra) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json, application/problem+json");
        applyCookies(builder);
        extra.forEach(builder::header);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        try {
            HttpResponse<String> response =
                    http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            storeCookies(response);
            JsonNode json = null;
            if (!response.body().isBlank()) {
                try {
                    json = JSON.readTree(response.body());
                } catch (RuntimeException e) {
                    // не JSON — оставляем только текст
                }
            }
            Response result = new Response(method, path, response.statusCode(), response.body(), json);
            responses.add(result);
            return result;
        } catch (IOException e) {
            throw new IllegalStateException(method + " " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void applyCookies(HttpRequest.Builder builder) {
        if (!cookies.isEmpty()) {
            List<String> pairs = new ArrayList<>();
            cookies.forEach((k, v) -> pairs.add(k + "=" + v));
            builder.header("Cookie", String.join("; ", pairs));
        }
        String xsrf = cookies.get("XSRF-TOKEN");
        if (xsrf != null) {
            builder.header("X-XSRF-TOKEN", xsrf);
        }
    }

    private void storeCookies(HttpResponse<?> response) {
        for (String header : response.headers().allValues("Set-Cookie")) {
            String pair = header.split(";", 2)[0];
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (value.isEmpty()) {
                cookies.remove(name);
            } else {
                cookies.put(name, value);
            }
        }
    }

    /** Подключение к SSE; читает события в отдельном потоке, пока не вызван {@link Sse#close()}. */
    Sse subscribe(String path, Long lastEventId) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create(base + path)).header("Accept", "text/event-stream");
        synchronized (this) {
            applyCookies(builder);
        }
        if (lastEventId != null) {
            builder.header("Last-Event-ID", Long.toString(lastEventId));
        }
        return new Sse(http, builder.build(), responses);
    }

    /** Живая подписка: события копятся в списке, тело ответа не буферизуется. */
    static final class Sse implements AutoCloseable {

        private final List<SseEvent> events = new CopyOnWriteArrayList<>();
        private final Thread reader;
        private volatile int status;
        private volatile Stream<String> lines;

        Sse(HttpClient http, HttpRequest request, List<Response> responses) {
            reader = Thread.ofVirtual().unstarted(() -> {
                try {
                    HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
                    status = response.statusCode();
                    lines = response.body();
                    long id = -1;
                    String type = "message";
                    StringBuilder data = new StringBuilder();
                    for (String line : (Iterable<String>) lines::iterator) {
                        if (line.isEmpty()) {
                            if (!data.isEmpty()) {
                                String text = data.toString();
                                SseEvent event = new SseEvent(id, type, text, JSON.readTree(text));
                                events.add(event);
                                // тело события проверяется наравне с обычными ответами
                                responses.add(new Response("SSE", request.uri().getPath(), 200, text, event.json()));
                            }
                            id = -1;
                            type = "message";
                            data.setLength(0);
                        } else if (line.startsWith("id:")) {
                            id = Long.parseLong(line.substring(3).trim());
                        } else if (line.startsWith("event:")) {
                            type = line.substring(6).trim();
                        } else if (line.startsWith("data:")) {
                            data.append(line.substring(5).trim());
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    // закрытие потока обрывает чтение: это штатный конец подписки
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            reader.start();
        }

        List<SseEvent> events() {
            return events;
        }

        int status() {
            return status;
        }

        /** Ждёт, пока число событий станет не меньше {@code count}. */
        void await(int count) {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (events.size() < count) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("ждали " + count + " событий SSE, пришли типы "
                            + events.stream().map(SseEvent::type).toList());
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        }

        @Override
        public void close() {
            Stream<String> current = lines;
            if (current != null) {
                current.close();
            }
            reader.interrupt();
        }
    }
}
