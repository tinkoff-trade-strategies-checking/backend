package ru.tinvest.trainer.marketdata.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Постраничная выгрузка, пауза, повтор с экспоненциальной задержкой и пустые ответы — на поддельном ISS. */
class IssClientTest {

    private FakeIssServer server;
    private final List<Duration> sleeps = new ArrayList<>();
    private IssClient iss;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeIssServer();
        HttpFetcher fetcher = new HttpFetcher(
                HttpClient.newHttpClient(),
                Duration.ofMillis(100),
                3,
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                sleeps::add);
        iss = new IssClient(fetcher, server.issUrl(), 2, FakeIssServer.PAGE);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void candlesAreFetchedPageByPageUntilShortPage() {
        List<Candle> candles = iss.shareCandles("SBER", 1, LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 8));

        // 6 торговых дней (1, 4, 5, 6, 7, 8 марта) по 3 свечи = 18, страницы по 5: 5+5+5+3
        assertThat(candles).hasSize(18);
        assertThat(server.requestsContaining("/candles.json")).isEqualTo(4);
        assertThat(server.requests).anyMatch(r -> r.contains("start=15"));
        assertThat(candles).extracting(Candle::tsOpen).isSorted();
    }

    @Test
    void fullLastPageIsFollowedByEmptyPageAndStops() {
        // 5 дней по 3 свечи = 15 = три полные страницы; после полной страницы нужна ещё одна, пустая
        // (её повторяют emptyRetries = 2 раза, прежде чем принять за конец)
        List<Candle> candles = iss.shareCandles("SBER", 1, LocalDate.of(2024, 3, 4), LocalDate.of(2024, 3, 8));

        assertThat(candles).hasSize(15);
        assertThat(server.requestsContaining("/candles.json")).isEqualTo(6);
    }

    @Test
    void errorsAreRetriedWithExponentialBackoff() {
        server.failures.put("/SBER/candles", new AtomicInteger(3));

        List<Candle> candles = iss.shareCandles("SBER", 1, LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 1));

        assertThat(candles).hasSize(3);
        // три ошибки: задержки 1 с, 2 с, 4 с; пауза между запросами — 100 мс
        assertThat(sleeps).contains(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
    }

    @Test
    void backoffIsCappedAtMax() {
        server.failures.put("/SBER/candles", new AtomicInteger(3));
        HttpFetcher fetcher = new HttpFetcher(
                HttpClient.newHttpClient(),
                Duration.ZERO,
                5,
                Duration.ofSeconds(4),
                Duration.ofSeconds(5),
                sleeps::add);
        IssClient capped = new IssClient(fetcher, server.issUrl(), 0, FakeIssServer.PAGE);

        capped.shareCandles("SBER", 1, LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 1));

        assertThat(sleeps).containsExactly(Duration.ofSeconds(4), Duration.ofSeconds(5), Duration.ofSeconds(5));
    }

    @Test
    void persistentErrorFailsAfterMaxRetries() {
        server.broken.add("/SBER/candles");

        assertThatThrownBy(() -> iss.shareCandles("SBER", 1, LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 1)))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("3 повторов");
        // первая попытка и три повтора
        assertThat(server.requestsContaining("/SBER/candles")).isEqualTo(4);
    }

    @Test
    void emptyPageIsRetriedBeforeBeingAcceptedAsEnd() {
        server.empties.put("/SBER/candles", new AtomicInteger(2));

        List<Candle> candles = iss.shareCandles("SBER", 1, LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 1));

        assertThat(candles).hasSize(3);
    }

    @Test
    void persistentlyEmptyResponseGivesEmptyResultAfterEmptyRetries() {
        // выходной: данных нет по-настоящему
        List<Candle> candles = iss.shareCandles("SBER", 1, LocalDate.of(2024, 3, 2), LocalDate.of(2024, 3, 3));

        assertThat(candles).isEmpty();
        // первая попытка и два повтора (emptyRetries = 2)
        assertThat(server.requestsContaining("/SBER/candles")).isEqualTo(3);
    }

    @Test
    void pauseIsKeptBetweenRequests() {
        iss.shareCandles("SBER", 1, LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 8));

        // пауза 100 мс: sleeper получает остаток паузы перед каждым запросом, кроме первого
        assertThat(sleeps).isNotEmpty().allSatisfy(d -> assertThat(d).isLessThanOrEqualTo(Duration.ofMillis(100)));
    }

    @Test
    void compositionIsReadByCursorTotal() {
        IssClient.IndexComposition c = iss.composition("IMOEX", LocalDate.of(2024, 3, 12));

        assertThat(c.secids()).containsExactly("SBER", "GAZP", "TINY", "GONE");
        assertThat(c.date()).isEqualTo(LocalDate.of(2024, 3, 12));
    }
}
