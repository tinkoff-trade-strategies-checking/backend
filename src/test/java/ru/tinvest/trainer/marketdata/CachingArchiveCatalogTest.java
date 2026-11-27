package ru.tinvest.trainer.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import ru.tinvest.trainer.api.DependencyUnavailableException;

class CachingArchiveCatalogTest {

    private static class Counting implements ArchiveCatalog {
        final AtomicInteger ready = new AtomicInteger();
        final AtomicInteger days = new AtomicInteger();
        final AtomicInteger intervals = new AtomicInteger();
        final AtomicInteger securities = new AtomicInteger();
        final AtomicInteger candleDays = new AtomicInteger();
        final AtomicInteger actions = new AtomicInteger();
        final AtomicInteger steps = new AtomicInteger();
        final AtomicInteger maxPerDay = new AtomicInteger();
        volatile boolean isReady;

        @Override
        public boolean isReady(String k) {
            ready.incrementAndGet();
            return isReady;
        }

        @Override
        public List<LocalDate> tradingDays(String k) {
            days.incrementAndGet();
            return List.of();
        }

        @Override
        public Set<Integer> intervals(String k) {
            intervals.incrementAndGet();
            return Set.of();
        }

        @Override
        public List<ArchivePaper> securities(String k) {
            securities.incrementAndGet();
            return List.of();
        }

        @Override
        public Map<String, Set<LocalDate>> candleDays(String k, int i) {
            candleDays.incrementAndGet();
            return Map.of();
        }

        @Override
        public Map<String, List<LocalDate>> corporateActions(String k) {
            actions.incrementAndGet();
            return Map.of();
        }

        @Override
        public int sessionSteps(String k, int s, int t, Collection<String> c, List<LocalDate> days) {
            return steps.incrementAndGet();
        }

        @Override
        public Map<Integer, Integer> maxStepsPerDay(String k, int source, List<Integer> steps) {
            maxPerDay.incrementAndGet();
            return Map.of();
        }
    }

    @Test
    void immutableSnapshotDataIsReadOnce() {
        var delegate = new Counting();
        var cache = new CachingArchiveCatalog(delegate, 2, 2);
        for (int i = 0; i < 3; i++) {
            cache.tradingDays("a");
            cache.intervals("a");
            cache.securities("a");
            cache.candleDays("a", 60);
            cache.corporateActions("a");
        }
        cache.candleDays("a", 300);
        cache.tradingDays("b");
        cache.maxStepsPerDay("a", 60, List.of(60, 300));
        cache.maxStepsPerDay("a", 60, List.of(60, 300));
        cache.maxStepsPerDay("a", 60, List.of(60));
        assertThat(delegate.maxPerDay).hasValue(2);
        assertThat(delegate.days).hasValue(2);
        assertThat(delegate.intervals).hasValue(1);
        assertThat(delegate.securities).hasValue(1);
        assertThat(delegate.candleDays).hasValue(2);
        assertThat(delegate.actions).hasValue(1);
    }

    @Test
    void onlyLastUsedSnapshotsAreKept() {
        var delegate = new Counting();
        var cache = new CachingArchiveCatalog(delegate, 2, 2);
        cache.tradingDays("a");
        cache.tradingDays("b");
        cache.tradingDays("a"); // a снова самый свежий, b — самый старый
        cache.tradingDays("c"); // вытесняет b
        assertThat(cache.cachedSnapshots()).isEqualTo(2);
        assertThat(delegate.days).hasValue(3);
        cache.tradingDays("a");
        cache.tradingDays("c");
        assertThat(delegate.days).hasValue(3);
        cache.tradingDays("b"); // читается заново
        assertThat(delegate.days).hasValue(4);
    }

    @Test
    void readinessIsCachedOnlyWhenTrueAndIntradayStepsAreCachedInBoundedLru() {
        var delegate = new Counting();
        var cache = new CachingArchiveCatalog(delegate, 2, 2, 2);
        assertThat(cache.isReady("a")).isFalse();
        assertThat(cache.isReady("a")).isFalse();
        assertThat(delegate.ready).hasValue(2);
        delegate.isReady = true;
        assertThat(cache.isReady("a")).isTrue();
        assertThat(cache.isReady("a")).isTrue();
        assertThat(delegate.ready).hasValue(3);

        var d1 = List.of(LocalDate.of(2024, 1, 1));
        var d2 = List.of(LocalDate.of(2024, 1, 2));
        var d3 = List.of(LocalDate.of(2024, 1, 3));
        // Повторный вызов и перестановка secids попадают в кэш
        assertThat(cache.sessionSteps("a", 60, 900, List.of("SBER", "GAZP"), d1))
                .isEqualTo(1);
        assertThat(cache.sessionSteps("a", 60, 900, List.of("GAZP", "SBER", "SBER"), d1))
                .isEqualTo(1);
        assertThat(delegate.steps).hasValue(1);

        // Заполняем LRU (лимит 2) и вытесняем d2 (d1 освежён)
        assertThat(cache.sessionSteps("a", 60, 900, List.of("SBER"), d2)).isEqualTo(2);
        assertThat(cache.sessionSteps("a", 60, 900, List.of("SBER", "GAZP"), d1))
                .isEqualTo(1);
        assertThat(cache.sessionSteps("a", 60, 900, List.of("SBER"), d3)).isEqualTo(3);
        assertThat(delegate.steps).hasValue(3);

        // d1 остался в кэше, d2 вытеснен и перечитывается
        assertThat(cache.sessionSteps("a", 60, 900, List.of("SBER", "GAZP"), d1))
                .isEqualTo(1);
        assertThat(delegate.steps).hasValue(3);
        assertThat(cache.sessionSteps("a", 60, 900, List.of("SBER"), d2)).isEqualTo(4);
        assertThat(delegate.steps).hasValue(4);
    }

    @Test
    void dailySessionStepsAreComputedInMemoryFromCachedCandleDaysWithoutDelegateCall() {
        LocalDate d1 = LocalDate.of(2024, 1, 10);
        LocalDate d2 = LocalDate.of(2024, 1, 11);
        LocalDate d3 = LocalDate.of(2024, 1, 12);
        LocalDate d4 = LocalDate.of(2024, 1, 15);
        var delegate = new Counting() {
            @Override
            public Map<String, Set<LocalDate>> candleDays(String k, int i) {
                candleDays.incrementAndGet();
                return Map.of(
                        "SBER", Set.of(d1, d2),
                        "GAZP", Set.of(d2, d3));
            }
        };
        var cache = new CachingArchiveCatalog(delegate, 2, 2);

        // Объединение {d1, d2} U {d2, d3} пересечённое с [d1, d2, d3, d4] = 3 дня (d4 нет ни у одной бумаги)
        assertThat(cache.sessionSteps("a", 60, 86_400, List.of("SBER", "GAZP"), List.of(d1, d2, d3, d4)))
                .isEqualTo(3);
        // Только SBER в окне [d1, d3, d4] = 1 день (d1)
        assertThat(cache.sessionSteps("a", 60, 86_400, List.of("SBER"), List.of(d1, d3, d4)))
                .isEqualTo(1);
        // Пустые аргументы -> 0 без обращений
        assertThat(cache.sessionSteps("a", 60, 86_400, List.of(), List.of(d1))).isZero();
        assertThat(cache.sessionSteps("a", 60, 86_400, List.of("SBER"), List.of()))
                .isZero();

        assertThat(delegate.steps).hasValue(0);
        assertThat(delegate.candleDays).hasValue(1);
    }

    @Test
    void notReadySnapshotDoesNotDisplaceReadyOne() {
        var delegate = new Counting();
        var cache = new CachingArchiveCatalog(delegate, 1, 2);
        delegate.isReady = true;
        assertThat(cache.isReady("ready")).isTrue();
        cache.tradingDays("ready");
        delegate.isReady = false;
        for (int i = 0; i < 3; i++) {
            assertThat(cache.isReady("new")).isFalse();
        }
        cache.tradingDays("ready");
        assertThat(delegate.days).hasValue(1);
        assertThat(cache.cachedSnapshots()).isEqualTo(1);
    }

    @Test
    void failureIsNotCachedAndNextCallReadsAgain() {
        var attempts = new AtomicInteger();
        var delegate = new Counting() {
            @Override
            public Map<String, Set<LocalDate>> candleDays(String k, int i) {
                if (attempts.incrementAndGet() == 1) {
                    throw new DependencyUnavailableException("timeout", null);
                }
                return Map.of("SBER", Set.of());
            }
        };
        var cache = new CachingArchiveCatalog(delegate, 2, 2);

        assertThatThrownBy(() -> cache.candleDays("a", 60)).isInstanceOf(DependencyUnavailableException.class);
        assertThat(cache.candleDays("a", 60)).containsKey("SBER");
        assertThat(cache.candleDays("a", 60)).containsKey("SBER");
        assertThat(attempts).hasValue(2);
    }

    @Test
    void concurrentCallsOfOneKeyShareASingleReadWithoutBlockingOthers() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var reads = new AtomicInteger();
        var delegate = new Counting() {
            @Override
            public Map<String, Set<LocalDate>> candleDays(String k, int i) {
                reads.incrementAndGet();
                started.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Map.of("SBER", Set.of());
            }
        };
        var cache = new CachingArchiveCatalog(delegate, 2, 2);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Future<?> first = pool.submit(() -> cache.candleDays("a", 60));
            started.await(5, TimeUnit.SECONDS);
            Future<?> second = pool.submit(() -> cache.candleDays("a", 60));
            // другое чтение не ждёт долгого скана (общая карта не заблокирована)
            assertThat(pool.submit(() -> cache.tradingDays("a")).get(5, TimeUnit.SECONDS))
                    .isEmpty();
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(reads).hasValue(1);
    }

    @Test
    void numberOfConcurrentReadsIsLimited() throws Exception {
        var running = new AtomicInteger();
        var peak = new AtomicInteger();
        var delegate = new Counting() {
            @Override
            public Map<String, Set<LocalDate>> candleDays(String k, int i) {
                int now = running.incrementAndGet();
                peak.accumulateAndGet(now, Math::max);
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                running.decrementAndGet();
                return Map.of();
            }
        };
        var cache = new CachingArchiveCatalog(delegate, 4, 2);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                int interval = 60 + i;
                futures.add(pool.submit(() -> cache.candleDays("a", interval)));
            }
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(peak.get()).isLessThanOrEqualTo(2);
    }
}
