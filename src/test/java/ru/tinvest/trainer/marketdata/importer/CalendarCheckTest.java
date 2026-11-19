package ru.tinvest.trainer.marketdata.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** Проверка дневного ряда IMOEX на синтетических календарях: настоящие события не должны выглядеть усечением. */
class CalendarCheckTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);

    /** Будни периода без праздников {@code holidays} и без дней {@code haltFrom}…{@code haltTill} включительно. */
    private static SortedSet<LocalDate> weekdays(
            LocalDate from, LocalDate till, Set<LocalDate> holidays, LocalDate haltFrom, LocalDate haltTill) {
        TreeSet<LocalDate> days = new TreeSet<>();
        for (LocalDate d = from; !d.isAfter(till); d = d.plusDays(1)) {
            boolean weekend = d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
            boolean halted = haltFrom != null && !d.isBefore(haltFrom) && !d.isAfter(haltTill);
            if (!weekend && !halted && !holidays.contains(d)) {
                days.add(d);
            }
        }
        return days;
    }

    private static List<Candle> candles(Set<LocalDate> days) {
        List<Candle> candles = new ArrayList<>();
        for (LocalDate d : days) {
            candles.add(new Candle(
                    d.atStartOfDay().toInstant(IssParsers.MSK),
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    0,
                    BigDecimal.ONE));
        }
        return candles;
    }

    private static Set<LocalDate> range(LocalDate from, LocalDate till) {
        Set<LocalDate> days = new TreeSet<>();
        for (LocalDate d = from; !d.isAfter(till); d = d.plusDays(1)) {
            days.add(d);
        }
        return days;
    }

    @Test
    void tradingHaltOfMarch2022NeedsConfirmationAndIsReported() {
        // годовой кусок прогона 2022-01-10…2022-12-30: акции не торговались 28.02–23.03.2022
        LocalDate from = LocalDate.of(2021, 12, 27);
        LocalDate till = LocalDate.of(2022, 11, 30);
        Set<LocalDate> holidays = new TreeSet<>(range(LocalDate.of(2022, 1, 3), LocalDate.of(2022, 1, 7)));
        holidays.addAll(List.of(LocalDate.of(2022, 2, 23), LocalDate.of(2022, 3, 8), LocalDate.of(2022, 5, 9)));
        SortedSet<LocalDate> days =
                weekdays(from, till, holidays, LocalDate.of(2022, 2, 28), LocalDate.of(2022, 3, 23));

        assertThatThrownBy(() -> ArchiveImporter.checkCalendar(candles(days), from, till, TODAY, false))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("2022-03 6 из 23 будних")
                .hasMessageContaining("остановка торгов")
                .hasMessageContaining("--import.allow-gaps=true");
        assertThatCode(() -> ArchiveImporter.checkCalendar(candles(days), from, till, TODAY, true))
                .doesNotThrowAnyException();
        assertThat(ArchiveImporter.calendarWarnings(days, from, till, TODAY)).containsExactly("2022-03 6 из 23 будних");
    }

    @Test
    void chunkStartingInsideTheHaltIsReportedByEdge() {
        // прогон с from = 2022-03-24: запас календаря начинается 10.03, посреди остановки
        LocalDate from = LocalDate.of(2022, 3, 10);
        LocalDate till = LocalDate.of(2022, 6, 30);
        SortedSet<LocalDate> days = weekdays(from, till, Set.of(), from, LocalDate.of(2022, 3, 23));

        assertThat(ArchiveImporter.calendarWarnings(days, from, till, TODAY))
                .containsExactly("ряд начинается 2022-03-24, ожидалось около 2022-03-10");
    }

    @Test
    void januaryOnlyRangePassesWithoutFlag() {
        // прогон 2024-01-01…2024-01-31, кусок с 18.12; худший случай праздников: торги с 9 января (17 из 23 будних)
        LocalDate from = LocalDate.of(2023, 12, 18);
        Set<LocalDate> holidays = range(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 8));
        for (LocalDate till : List.of(LocalDate.of(2024, 1, 31), LocalDate.of(2024, 1, 12), LocalDate.of(2024, 1, 8))) {
            SortedSet<LocalDate> days = weekdays(from, till, holidays, null, null);

            assertThatCode(() -> ArchiveImporter.checkCalendar(candles(days), from, till, TODAY, false))
                    .as("до %s", till)
                    .doesNotThrowAnyException();
            assertThat(ArchiveImporter.calendarWarnings(days, from, till, TODAY))
                    .isEmpty();
        }
    }

    @Test
    void emptySeriesIsAnErrorEvenWithAllowGaps() {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate till = LocalDate.of(2024, 12, 31);

        assertThatThrownBy(() -> ArchiveImporter.checkCalendar(List.of(), from, till, TODAY, true))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("пустой дневной ряд");
    }

    @Test
    void manyDatesAreShortenedToHeadAndTail() {
        TreeSet<LocalDate> five = new TreeSet<>(range(LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 5)));
        TreeSet<LocalDate> many = new TreeSet<>(range(LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 31)));

        assertThat(ArchiveImporter.dates(five))
                .isEqualTo("[2024-03-01, 2024-03-02, 2024-03-03, 2024-03-04, 2024-03-05]");
        assertThat(ArchiveImporter.dates(many))
                .isEqualTo("[2024-03-01, 2024-03-02, 2024-03-03, …, 2024-03-30, 2024-03-31]");
    }
}
