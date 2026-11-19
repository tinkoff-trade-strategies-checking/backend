package ru.tinvest.trainer.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.Test;
import ru.tinvest.trainer.e2e.LeakScanner.Kind;

/** Сам сканер INV-7 на синтетических ответах: каждая проверка срабатывает на своём образце и не шумит на допустимом. */
class LeakScannerSelfTest {

    private final MarketFixture fixture = new MarketFixture();
    private final LeakScanner scanner = new LeakScanner(fixture, Set.of(new BigDecimal("10000000.00")));

    private Set<Kind> kinds(String json) {
        return LeakScanner.kinds(scanner.scan("sample", ApiClient.JSON.readTree(json)));
    }

    @Test
    void cleanBlindAnswerPasses() {
        assertThat(kinds("{\"title\":\"Слепая сессия от 20.11.2026\",\"createdAt\":\"2026-11-20T10:00:00Z\","
                        + "\"step\":3,\"price\":\"101.25\",\"equityPct\":\"100.00\",\"lots\":5000000}"))
                .isEmpty();
    }

    @Test
    void titleWithPeriodAndTickersIsALeak() {
        assertThat(kinds("{\"title\":\"26.02.2025 — 21.05.2025 · SBER, GAZP\"}"))
                .contains(Kind.DATE, Kind.TICKER);
    }

    @Test
    void createdAtInsideTheArchiveIsALeak() {
        assertThat(kinds("{\"createdAt\":\"2025-06-10T10:00:00Z\"}")).contains(Kind.DATE);
    }

    @Test
    void epochSecondsAndMillisecondsAreLeaks() {
        long ms = fixture.days()
                .get(30)
                .atStartOfDay(java.time.ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli();
        assertThat(kinds("{\"ts\":" + ms + "}")).contains(Kind.DATE);
        assertThat(kinds("{\"ts\":" + ms / 1000 + "}")).contains(Kind.DATE);
    }

    @Test
    void monthWordsAndOtherDateFormatsAreLeaks() {
        assertThat(kinds("{\"a\":\"3 марта 2025\"}")).contains(Kind.DATE);
        assertThat(kinds("{\"a\":\"Mar 2025\"}")).contains(Kind.DATE);
        assertThat(kinds("{\"a\":\"2025/03/03\"}")).contains(Kind.DATE);
        assertThat(kinds("{\"a\":\"20250303\"}")).contains(Kind.DATE);
    }

    @Test
    void rublesInTextAndAsLargeNumbersAreLeaks() {
        assertThat(kinds("{\"detail\":\"не хватает 12 345,67 ₽\"}")).contains(Kind.RUBLE);
        assertThat(kinds("{\"total\":\"9951816.88\"}")).contains(Kind.RUBLE);
        assertThat(kinds("{\"price\":1601.14}")).contains(Kind.RUBLE);
    }

    @Test
    void capitalPricesNamesAndFieldsAreLeaks() {
        assertThat(kinds("{\"a\":\"10000000.00\"}")).contains(Kind.CAPITAL);
        assertThat(kinds("{\"a\":\"Газпром\"}")).contains(Kind.NAME);
        assertThat(kinds("{\"cash\":\"1.00\"}")).contains(Kind.FIELD);
        assertThat(kinds("{\"at\":\"10:30:00\"}")).contains(Kind.TIME);
        BigDecimal real = fixture.allPrices().getFirst();
        assertThat(kinds("{\"p\":\"" + real.toPlainString() + "\"}")).contains(Kind.PRICE);
    }
}
