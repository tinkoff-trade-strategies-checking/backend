package ru.tinvest.trainer.marketdata;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кэш поверх {@link ArchiveCatalog}: снапшот неизменяем (INV-5), поэтому календарь, интервалы, бумаги, даты свечей и
 * корпоративные действия читаются из ClickHouse один раз на снапшот. Готовность кэшируется только когда она
 * получена ({@code true}). Размер ограничен числом снапшотов процесса (единицы); кэш живёт до перезапуска.
 */
final class CachingArchiveCatalog implements ArchiveCatalog {

    private record IntervalKey(String snapshotKey, int intervalSec) {}

    private final ArchiveCatalog delegate;
    private final Set<String> ready = ConcurrentHashMap.newKeySet();
    private final Map<String, List<LocalDate>> days = new ConcurrentHashMap<>();
    private final Map<String, Set<Integer>> intervals = new ConcurrentHashMap<>();
    private final Map<String, List<ArchivePaper>> securities = new ConcurrentHashMap<>();
    private final Map<IntervalKey, Map<String, Set<LocalDate>>> candleDays = new ConcurrentHashMap<>();
    private final Map<String, Map<String, List<LocalDate>>> actions = new ConcurrentHashMap<>();

    CachingArchiveCatalog(ArchiveCatalog delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean isReady(String snapshotKey) {
        if (ready.contains(snapshotKey)) {
            return true;
        }
        boolean value = delegate.isReady(snapshotKey);
        if (value) {
            ready.add(snapshotKey);
        }
        return value;
    }

    @Override
    public List<LocalDate> tradingDays(String snapshotKey) {
        return days.computeIfAbsent(snapshotKey, delegate::tradingDays);
    }

    @Override
    public Set<Integer> intervals(String snapshotKey) {
        return intervals.computeIfAbsent(snapshotKey, delegate::intervals);
    }

    @Override
    public List<ArchivePaper> securities(String snapshotKey) {
        return securities.computeIfAbsent(snapshotKey, delegate::securities);
    }

    @Override
    public Map<String, Set<LocalDate>> candleDays(String snapshotKey, int intervalSec) {
        return candleDays.computeIfAbsent(
                new IntervalKey(snapshotKey, intervalSec), k -> delegate.candleDays(snapshotKey, intervalSec));
    }

    @Override
    public Map<String, List<LocalDate>> corporateActions(String snapshotKey) {
        return actions.computeIfAbsent(snapshotKey, delegate::corporateActions);
    }

    @Override
    public int sessionSteps(
            String snapshotKey,
            int sourceIntervalSec,
            int stepSec,
            Collection<String> secids,
            LocalDate from,
            LocalDate to) {
        return delegate.sessionSteps(snapshotKey, sourceIntervalSec, stepSec, secids, from, to);
    }
}
