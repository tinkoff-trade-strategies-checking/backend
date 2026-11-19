package ru.tinvest.trainer.marketdata.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Разбор настоящих ответов ISS и страницы ЦБ РФ (образцы записаны 19.11.2026). */
class IssParsersTest {

    private static String sample(String name) throws IOException {
        try (var in = IssParsersTest.class.getResourceAsStream("/iss/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void minuteCandlesAreConvertedFromMskToUtcWithMoneyScale() throws IOException {
        List<Candle> candles = IssParsers.candles(IssParsers.table(sample("candles-sber.json"), "candles"));

        assertThat(candles).hasSize(5);
        Candle first = candles.get(0);
        // 09:59 МСК = 06:59 UTC
        assertThat(first.tsOpen()).isEqualTo(Instant.parse("2024-03-01T06:59:00Z"));
        assertThat(first.open()).isEqualByComparingTo("292.2");
        assertThat(first.open().scale()).isEqualTo(6);
        assertThat(first.volume()).isEqualTo(78820L);
        // 40851055.69999998 — артефакт double в ISS, округляется до 6 знаков HALF_UP
        assertThat(candles.get(1).value()).isEqualTo(new BigDecimal("40851055.700000"));
        assertThat(IssParsers.mskDate(first)).isEqualTo(LocalDate.of(2024, 3, 1));
    }

    @Test
    void dailyIndexCandleStartsAtMskMidnight() throws IOException {
        List<Candle> candles = IssParsers.candles(IssParsers.table(sample("candles-imoex-daily.json"), "candles"));

        assertThat(candles).hasSize(3);
        // 2024-03-01 00:00 МСК = 2024-02-29 21:00 UTC, как в фикстуре core
        assertThat(candles.get(0).tsOpen()).isEqualTo(Instant.parse("2024-02-29T21:00:00Z"));
        assertThat(candles.get(0).close()).isEqualByComparingTo("3266.66");
        assertThat(candles.get(0).volume()).isZero();
    }

    @Test
    void cursorGivesTotalAndPrevDate() throws IOException {
        IssTable t = IssParsers.table(sample("analytics-moexfn.json"), "analytics");

        assertThat(t.size()).isGreaterThan(5);
        assertThat(t.total()).isNotNull();
        assertThat(t.get(t.rows().get(0), "secids").asString()).isNotEmpty();
    }

    @Test
    void securityParamsAreParsed() throws IOException {
        IssTable t = IssParsers.table(sample("security-sber.json"), "securities");

        assertThat(t.get(t.rows().get(0), "LOTSIZE").asLong()).isEqualTo(1);
        assertThat(IssParsers.money(t.get(t.rows().get(0), "MINSTEP"))).isEqualByComparingTo("0.01");
    }

    @Test
    void responseWithoutBlockIsAnError() {
        assertThatThrownBy(() -> IssParsers.table("{}", "candles")).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> IssParsers.table("<html>", "candles")).isInstanceOf(ImportException.class);
    }

    @Test
    void ruoniaIsParsedFromCbrPage() throws IOException {
        Map<LocalDate, BigDecimal> rates = CbrRuonia.parse(sample("ruonia-cbr.html"));

        assertThat(rates).containsKey(LocalDate.of(2024, 12, 27));
        assertThat(rates.get(LocalDate.of(2024, 12, 27))).isEqualByComparingTo("20.47");
        assertThat(rates.get(LocalDate.of(2024, 12, 27)).scale()).isEqualTo(6);
        assertThat(rates).hasSize(5);
    }

    @Test
    void cbrPageWithoutTableIsAnError() {
        assertThatThrownBy(() -> CbrRuonia.parse("<html>Технические работы</html>"))
                .isInstanceOf(ImportException.class);
    }
}
