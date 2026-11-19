package ru.tinvest.trainer.marketdata;

import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Кэш поверх {@link ArchiveCatalog}: снапшот неизменяем (INV-5), поэтому календарь, интервалы, бумаги, даты свечей и
 * корпоративные действия читаются из ClickHouse один раз на снапшот.
 *
 * <ul>
 *   <li>Хранятся данные только последних {@code maxSnapshots} использованных снапшотов, остальные вытесняются.
 *   <li>Загрузка не держит никакой блокировки map: одновременные запросы одного и того же ждут общий результат
 *       ({@link CompletableFuture}), а провал (например, таймаут) не кэшируется — следующий вызов читает заново.
 *   <li>Одновременных чтений из ClickHouse не больше {@code maxConcurrentLoads}: первый скан свечей снапшота тяжёлый.
 *   <li>Готовность кэшируется только когда она получена ({@code true}).
 * </ul>
 */
final class CachingArchiveCatalog implements ArchiveCatalog {

    /** Данные одного снапшота. */
    private static final class Entry {
        volatile boolean ready;
        final Map<String, CompletableFuture<?>> inflight = new ConcurrentHashMap<>();
        final Map<String, Object> values = new ConcurrentHashMap<>();
    }

    private final ArchiveCatalog delegate;
    private final int maxSnapshots;
    private final Semaphore loads;
    private final Map<String, Entry> entries = new LinkedHashMap<>(4, 0.75f, true);

    CachingArchiveCatalog(ArchiveCatalog delegate, int maxSnapshots, int maxConcurrentLoads) {
        if (maxSnapshots < 1 || maxConcurrentLoads < 1) {
            throw new IllegalArgumentException("Кэш каталога: число снапшотов и загрузок должно быть не меньше 1");
        }
        this.delegate = delegate;
        this.maxSnapshots = maxSnapshots;
        this.loads = new Semaphore(maxConcurrentLoads);
    }

    private synchronized Entry entry(String snapshotKey) {
        Entry entry = entries.computeIfAbsent(snapshotKey, k -> new Entry());
        while (entries.size() > maxSnapshots) {
            String eldest = entries.keySet().iterator().next();
            entries.remove(eldest);
        }
        return entry;
    }

    private synchronized boolean known(String snapshotKey) {
        Entry entry = entries.get(snapshotKey);
        return entry != null && entry.ready;
    }

    /** Снапшотов в кэше (для тестов). */
    synchronized int cachedSnapshots() {
        return entries.size();
    }

    @SuppressWarnings("unchecked")
    private <T> T memo(String snapshotKey, String name, Supplier<T> load) {
        Entry entry = entry(snapshotKey);
        Object cached = entry.values.get(name);
        if (cached != null) {
            return (T) cached;
        }
        CompletableFuture<T> mine = new CompletableFuture<>();
        CompletableFuture<T> existing = (CompletableFuture<T>) entry.inflight.putIfAbsent(name, mine);
        if (existing != null) {
            return await(existing);
        }
        try {
            // поток мог увидеть пустое значение до записи предыдущего владельца и прийти после его inflight.remove
            Object late = entry.values.get(name);
            if (late != null) {
                mine.complete((T) late);
                return (T) late;
            }
            T value = loadLimited(load);
            entry.values.put(name, value);
            mine.complete(value);
            return value;
        } catch (RuntimeException | Error e) {
            // провал не кэшируется: ожидающие получают ту же ошибку, следующий вызов читает заново
            mine.completeExceptionally(e);
            throw e;
        } finally {
            entry.inflight.remove(name);
        }
    }

    private <T> T loadLimited(Supplier<T> load) {
        try {
            loads.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Чтение архива прервано", e);
        }
        try {
            return load.get();
        } finally {
            loads.release();
        }
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }

    @Override
    public boolean isReady(String snapshotKey) {
        if (known(snapshotKey)) {
            return true;
        }
        boolean value = delegate.isReady(snapshotKey);
        if (value) {
            // неготовый снапшот в кэше не заводим: он не должен вытеснять данные готового
            entry(snapshotKey).ready = true;
        }
        return value;
    }

    @Override
    public List<LocalDate> tradingDays(String snapshotKey) {
        return memo(snapshotKey, "days", () -> delegate.tradingDays(snapshotKey));
    }

    @Override
    public Set<Integer> intervals(String snapshotKey) {
        return memo(snapshotKey, "intervals", () -> delegate.intervals(snapshotKey));
    }

    @Override
    public List<ArchivePaper> securities(String snapshotKey) {
        return memo(snapshotKey, "securities", () -> delegate.securities(snapshotKey));
    }

    @Override
    public Map<String, Set<LocalDate>> candleDays(String snapshotKey, int intervalSec) {
        return memo(snapshotKey, "candleDays:" + intervalSec, () -> delegate.candleDays(snapshotKey, intervalSec));
    }

    @Override
    public Map<String, List<LocalDate>> corporateActions(String snapshotKey) {
        return memo(snapshotKey, "actions", () -> delegate.corporateActions(snapshotKey));
    }

    @Override
    public int sessionSteps(
            String snapshotKey,
            int sourceIntervalSec,
            int stepSec,
            Collection<String> secids,
            List<LocalDate> windowDays) {
        return delegate.sessionSteps(snapshotKey, sourceIntervalSec, stepSec, secids, windowDays);
    }

    @Override
    public Map<Integer, Integer> maxStepsPerDay(String snapshotKey, int sourceIntervalSec, List<Integer> stepSecs) {
        return memo(
                snapshotKey,
                "maxStepsPerDay:" + sourceIntervalSec + ":" + stepSecs,
                () -> delegate.maxStepsPerDay(snapshotKey, sourceIntervalSec, stepSecs));
    }
}
