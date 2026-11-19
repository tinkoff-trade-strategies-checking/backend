package ru.tinvest.trainer.marketdata.importer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Function;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GET с паузой между запросами и повтором с экспоненциальной задержкой. Ошибка (сеть, статус не 200, ответ не
 * разбирается) повторяется до {@code maxRetries} раз, затем {@link ImportException}. Пустой ответ (источник иногда
 * отдаёт пустую страницу вместо данных) повторяется до {@code emptyRetries} раз, после чего считается настоящим
 * концом данных.
 */
final class HttpFetcher {

    private static final Logger log = LoggerFactory.getLogger(HttpFetcher.class);

    private final HttpClient http;
    private final Duration pause;
    private final int maxRetries;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Sleeper sleeper;
    private long lastRequestNanos;
    private boolean requested;

    HttpFetcher(
            HttpClient http,
            Duration pause,
            int maxRetries,
            Duration initialBackoff,
            Duration maxBackoff,
            Sleeper sleeper) {
        this.http = http;
        this.pause = pause;
        this.maxRetries = maxRetries;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.sleeper = sleeper;
    }

    <T> T get(URI uri, Function<String, T> parser, Predicate<T> isEmpty, int emptyRetries) {
        int errors = 0;
        int empties = 0;
        while (true) {
            pace();
            T parsed;
            try {
                parsed = parser.apply(send(uri));
            } catch (Interrupted e) {
                throw new ImportException("Импорт прерван", e);
            } catch (IOException | RuntimeException e) {
                if (++errors > maxRetries) {
                    throw new ImportException("Запрос не удался после " + maxRetries + " повторов: " + uri, e);
                }
                log.warn("Ошибка запроса {} (попытка {}): {}", uri, errors, e.toString());
                sleeper.sleep(backoff(errors));
                continue;
            }
            if (isEmpty.test(parsed) && ++empties <= emptyRetries) {
                log.warn("Пустой ответ {} (повтор {} из {})", uri, empties, emptyRetries);
                sleeper.sleep(backoff(empties));
                continue;
            }
            return parsed;
        }
    }

    private String send(URI uri) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Interrupted(e);
        }
    }

    private static final class Interrupted extends RuntimeException {
        Interrupted(Throwable cause) {
            super(cause);
        }
    }

    private void pace() {
        if (requested) {
            long elapsed = System.nanoTime() - lastRequestNanos;
            Duration left = pause.minusNanos(elapsed);
            if (left.isPositive()) {
                sleeper.sleep(left);
            }
        }
        requested = true;
        lastRequestNanos = System.nanoTime();
    }

    private Duration backoff(int attempt) {
        long factor = 1L << Math.min(attempt - 1, 20);
        Duration delay = initialBackoff.multipliedBy(factor);
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }
}
