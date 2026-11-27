package ru.tinvest.trainer.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Проверяет, что точечные и фильтрованные методы {@link InstrumentsCatalog} обслуживаются из in-memory кэша
 * (TTL 5 мин) и не ходят повторно в PostgreSQL (backend#104).
 */
class InstrumentsCatalogTest {

    @SuppressWarnings("unchecked")
    private static <T> JdbcClient stubJdbc(List<T> rows) {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<T> mapped = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(spec);
        when(spec.query(any(RowMapper.class))).thenReturn(mapped);
        when(mapped.list()).thenReturn(rows);
        return jdbc;
    }

    @Test
    void findBondUsesCachedListWithoutExtraDbQueries() {
        BondSpec ofz = new BondSpec(
                "SU26248RMFS3",
                "ОФЗ 26248",
                "Минфин РФ",
                "OFZ",
                new BigDecimal("1000.00"),
                new BigDecimal("12.25"),
                new BigDecimal("61.08"),
                182,
                LocalDate.of(2027, 6, 1),
                LocalDate.of(2040, 5, 16),
                null,
                new BigDecimal("10.00"),
                new BigDecimal("15.00"),
                new BigDecimal("6.50"),
                false,
                new BigDecimal("85.00"),
                "RUB");
        JdbcClient jdbc = stubJdbc(List.of(ofz));
        InstrumentsCatalog catalog = new InstrumentsCatalog(jdbc);

        assertThat(catalog.findBond(" su26248rmfs3 ")).contains(ofz);
        assertThat(catalog.findBond("UNKNOWN")).isEmpty();
        assertThat(catalog.listBonds()).containsExactly(ofz);
        assertThat(catalog.findBond("   ")).isEmpty();

        verify(jdbc, times(1)).sql(anyString());
    }

    @Test
    void findFuturesAndFindOptionUseCachedListsWithoutExtraDbQueries() {
        FuturesSpec fut = new FuturesSpec(
                "SiM6",
                "USD/RUB",
                "USD",
                1000,
                new BigDecimal("1"),
                new BigDecimal("1"),
                new BigDecimal("12000"),
                new BigDecimal("90000"),
                LocalDate.of(2026, 6, 18),
                "CASH",
                "RUB");
        JdbcClient futJdbc = stubJdbc(List.of(fut));
        InstrumentsCatalog futCatalog = new InstrumentsCatalog(futJdbc);

        assertThat(futCatalog.findFutures("sim6")).contains(fut);
        assertThat(futCatalog.listFutures()).containsExactly(fut);
        assertThat(futCatalog.findFutures("NONE")).isEmpty();
        verify(futJdbc, times(1)).sql(anyString());

        OptionSpec opt = new OptionSpec(
                "SR95000BF6",
                "SBER Call",
                "SBER",
                "CALL",
                new BigDecimal("300"),
                LocalDate.of(2026, 6, 18),
                new BigDecimal("305"),
                new BigDecimal("28.0"),
                new BigDecimal("16.0"),
                100,
                new BigDecimal("5000"));
        JdbcClient optJdbc = stubJdbc(List.of(opt));
        InstrumentsCatalog optCatalog = new InstrumentsCatalog(optJdbc);

        assertThat(optCatalog.findOption(" sr95000bf6 ")).contains(opt);
        assertThat(optCatalog.listOptions()).containsExactly(opt);
        assertThat(optCatalog.findOption("NONE")).isEmpty();
        verify(optJdbc, times(1)).sql(anyString());
    }

    @Test
    void dividendsByTickerAndLatestDividendUseCachedListWithExactTieBreaking() {
        DividendEventSpec older = new DividendEventSpec(
                1L,
                "SBER",
                "Сбербанк",
                LocalDate.of(2025, 7, 10),
                LocalDate.of(2025, 7, 9),
                LocalDate.of(2025, 7, 8),
                LocalDate.of(2025, 7, 25),
                new BigDecimal("33.30"),
                new BigDecimal("11.00"),
                new BigDecimal("9.50"),
                "RUB");
        DividendEventSpec newerId2 = new DividendEventSpec(
                2L,
                "SBER",
                "Сбербанк",
                LocalDate.of(2026, 7, 10),
                LocalDate.of(2026, 7, 9),
                LocalDate.of(2026, 7, 8),
                LocalDate.of(2026, 7, 25),
                new BigDecimal("35.00"),
                new BigDecimal("11.50"),
                new BigDecimal("10.00"),
                "RUB");
        DividendEventSpec newerId5 = new DividendEventSpec(
                5L,
                "SBER",
                "Сбербанк",
                LocalDate.of(2026, 7, 10),
                LocalDate.of(2026, 7, 9),
                LocalDate.of(2026, 7, 8),
                LocalDate.of(2026, 7, 25),
                new BigDecimal("36.00"),
                new BigDecimal("12.00"),
                new BigDecimal("10.50"),
                "RUB");
        // listDividends() упорядочен по record_date DESC, ticker ASC, id ASC
        JdbcClient jdbc = stubJdbc(List.of(newerId2, newerId5, older));
        InstrumentsCatalog catalog = new InstrumentsCatalog(jdbc);

        assertThat(catalog.listDividendsByTicker("sber")).containsExactly(newerId2, newerId5, older);
        assertThat(catalog.findLatestDividend("SBER")).contains(newerId5);
        assertThat(catalog.listDividendsByTicker("LKOH")).isEmpty();
        assertThat(catalog.findLatestDividend("LKOH")).isEmpty();
        verify(jdbc, times(1)).sql(anyString());
    }

    @Test
    void listMarketEventsFiltersInMemoryFromSingleCachedLoad() {
        MarketEventSpec cbr = new MarketEventSpec(
                1L, LocalDate.of(2026, 4, 24), "CBR_RATE", null, "Заседание ЦБ", "HIGH", "Ключевая ставка");
        MarketEventSpec sberDiv = new MarketEventSpec(
                2L, LocalDate.of(2026, 7, 10), "DIVIDEND", "SBER", "Дивиденды Сбера", "MEDIUM", "Отсечка");
        MarketEventSpec lkohDiv = new MarketEventSpec(
                3L, LocalDate.of(2026, 7, 15), "DIVIDEND", "LKOH", "Дивиденды Лукойла", "MEDIUM", "Отсечка");
        JdbcClient jdbc = stubJdbc(List.of(cbr, sberDiv, lkohDiv));
        InstrumentsCatalog catalog = new InstrumentsCatalog(jdbc);

        assertThat(catalog.listMarketEvents(null, null)).containsExactly(cbr, sberDiv, lkohDiv);
        assertThat(catalog.listMarketEvents("DIVIDEND", null)).containsExactly(sberDiv, lkohDiv);
        assertThat(catalog.listMarketEvents(null, "sber")).containsExactly(sberDiv);
        assertThat(catalog.listMarketEvents("DIVIDEND", "LKOH")).containsExactly(lkohDiv);
        assertThat(catalog.listMarketEvents("CBR_RATE", "SBER")).isEmpty();

        verify(jdbc, times(1)).sql(anyString());
    }
}
