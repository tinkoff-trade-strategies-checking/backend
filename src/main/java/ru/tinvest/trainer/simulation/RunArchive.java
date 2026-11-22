package ru.tinvest.trainer.simulation;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import ru.tinvest.trainer.api.NotFoundException;
import ru.tinvest.trainer.identity.CurrentUser;
import ru.tinvest.trainer.marketdata.Regime;
import ru.tinvest.trainer.workshop.StrategyCatalog;
import ru.tinvest.trainer.workshop.StrategyVersionView;

/**
 * Публичный вход модуля {@code simulation} для модуля {@code analysis} (отчёты, выгрузка и история): прогоны текущего
 * пользователя без раскрытия закрытых деталей до завершения слепого прогона (INV-7).
 */
@Service
public class RunArchive {

    private final Runs runs;
    private final CurrentUser currentUser;
    private final StrategyCatalog strategyCatalog;

    RunArchive(Runs runs, CurrentUser currentUser, StrategyCatalog strategyCatalog) {
        this.runs = runs;
        this.currentUser = currentUser;
        this.strategyCatalog = strategyCatalog;
    }

    /** Фильтры истории прогонов; {@code status} — значение {@code HistoryStatus} контракта. */
    public record HistoryFilter(
            boolean blindOnly,
            @Nullable String status,
            @Nullable Regime regime,
            int page,
            int pageSize) {}

    /** Элемент истории прогона с разрешённым именем алгоритма. */
    public record HistoryItem(RunRecord run, String algoName) {}

    /** Страница истории прогонов текущего пользователя (новые сверху). */
    public record HistoryPage(List<HistoryItem> items, long total) {}

    /** Состояние прогона текущего пользователя: нет или чужой — {@link RunLookup.Missing}. */
    public RunLookup lookup(UUID runId) {
        try {
            RunRecord run = runs.requireOwned(runId);
            return run.isActive() ? new RunLookup.Unfinished(run) : new RunLookup.Finished(run);
        } catch (NotFoundException e) {
            return new RunLookup.Missing();
        }
    }

    /** Прогон текущего пользователя без исключения: пусто, если прогона нет или он чужой. */
    public Optional<RunRecord> findOwned(UUID runId) {
        try {
            return Optional.of(runs.requireOwned(runId));
        } catch (NotFoundException e) {
            return Optional.empty();
        }
    }

    /** Сохранённый результат завершённого прогона (для {@code /report} и {@code /report/export}). */
    public Optional<RunResultRecord> findResult(UUID runId) {
        return runs.findResult(runId);
    }

    /** Запасной каркас отчёта для технически упавшего прогона ({@code status = failed}) без сохранённого отчёта. */
    public Map<String, Object> failedReportFallback(RunRecord run) {
        return RunReportViews.failedReport(run, algoName(run.strategyVersionId()));
    }

    /** Страница истории прогонов текущего пользователя. */
    public HistoryPage history(HistoryFilter filter) {
        UUID userId = currentUser.requireId();
        Runs.HistoryData data = runs.history(
                userId, filter.blindOnly(), filter.status(), filter.regime(), filter.page(), filter.pageSize());
        Map<UUID, String> algoNames = new java.util.HashMap<>();
        List<HistoryItem> items = new java.util.ArrayList<>(data.items().size());
        for (RunRecord run : data.items()) {
            String name = algoNames.computeIfAbsent(run.strategyVersionId(), this::algoName);
            items.add(new HistoryItem(run, name));
        }
        return new HistoryPage(items, data.total());
    }

    private String algoName(UUID strategyVersionId) {
        return strategyCatalog
                .findVersion(strategyVersionId)
                .map(StrategyVersionView::strategyName)
                .orElse("Алгоритм");
    }
}
