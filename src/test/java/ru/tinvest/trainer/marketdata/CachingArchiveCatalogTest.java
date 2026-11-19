package ru.tinvest.trainer.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CachingArchiveCatalogTest {

    private static final class Counting implements ArchiveCatalog {
        int ready;
        int days;
        int intervals;
        int securities;
        int candleDays;
        int actions;
        int steps;
        boolean isReady;

        @Override
        public boolean isReady(String k) {
            ready++;
            return isReady;
        }

        @Override
        public List<LocalDate> tradingDays(String k) {
            days++;
            return List.of();
        }

        @Override
        public Set<Integer> intervals(String k) {
            intervals++;
            return Set.of();
        }

        @Override
        public List<ArchivePaper> securities(String k) {
            securities++;
            return List.of();
        }

        @Override
        public Map<String, Set<LocalDate>> candleDays(String k, int i) {
            candleDays++;
            return Map.of();
        }

        @Override
        public Map<String, List<LocalDate>> corporateActions(String k) {
            actions++;
            return Map.of();
        }

        @Override
        public int sessionSteps(String k, int s, int t, Collection<String> c, LocalDate f, LocalDate to) {
            return ++steps;
        }
    }

    @Test
    void immutableSnapshotDataIsReadOnce() {
        var delegate = new Counting();
        var cache = new CachingArchiveCatalog(delegate);
        for (int i = 0; i < 3; i++) {
            cache.tradingDays("a");
            cache.intervals("a");
            cache.securities("a");
            cache.candleDays("a", 60);
            cache.corporateActions("a");
        }
        cache.candleDays("a", 300);
        cache.tradingDays("b");
        assertThat(delegate.days).isEqualTo(2);
        assertThat(delegate.intervals).isEqualTo(1);
        assertThat(delegate.securities).isEqualTo(1);
        assertThat(delegate.candleDays).isEqualTo(2);
        assertThat(delegate.actions).isEqualTo(1);
    }

    @Test
    void readinessIsCachedOnlyWhenTrueAndStepsAreNot() {
        var delegate = new Counting();
        var cache = new CachingArchiveCatalog(delegate);
        assertThat(cache.isReady("a")).isFalse();
        assertThat(cache.isReady("a")).isFalse();
        assertThat(delegate.ready).isEqualTo(2);
        delegate.isReady = true;
        assertThat(cache.isReady("a")).isTrue();
        assertThat(cache.isReady("a")).isTrue();
        assertThat(delegate.ready).isEqualTo(3);

        var d = LocalDate.of(2024, 1, 1);
        assertThat(cache.sessionSteps("a", 60, 60, List.of("X"), d, d)).isEqualTo(1);
        assertThat(cache.sessionSteps("a", 60, 60, List.of("X"), d, d)).isEqualTo(2);
    }
}
