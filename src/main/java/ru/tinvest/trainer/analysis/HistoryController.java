package ru.tinvest.trainer.analysis;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.tinvest.trainer.api.InvalidRequestException;
import ru.tinvest.trainer.marketdata.Regime;
import ru.tinvest.trainer.sessions.PaperRecord;
import ru.tinvest.trainer.sessions.PublicViews;
import ru.tinvest.trainer.sessions.SessionArchive;
import ru.tinvest.trainer.sessions.SessionLookup;
import ru.tinvest.trainer.sessions.SessionRecord;
import ru.tinvest.trainer.sessions.ViewFormat;
import ru.tinvest.trainer.simulation.RunArchive;
import ru.tinvest.trainer.simulation.RunPaperRecord;
import ru.tinvest.trainer.simulation.RunRecord;

/**
 * История сессий и прогонов алгоритмов пользователя (контракт {@code api/openapi.yaml}, {@code listHistory}): страница
 * по дате создания, новые сверху. Незавершённая слепая запись ничего не раскрывает (INV-7), у незавершённой открытой
 * итог {@code null} (INV-1).
 */
@RestController
@RequestMapping("/api/v1/history")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class HistoryController {

    private static final Set<String> STATUSES =
            Set.of("running", "completed", "finished_early", "bankrupt", "invalid", "failed");
    private static final int MAX_PAGE_SIZE = 100;
    private static final DateTimeFormatter TITLE_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private record SortableEntry(Instant createdAt, UUID id, Map<String, Object> body) {}

    private final SessionArchive sessionArchive;
    private final RunArchive runArchive;
    private final StoredReports store;

    HistoryController(SessionArchive sessionArchive, RunArchive runArchive, StoredReports store) {
        this.sessionArchive = sessionArchive;
        this.runArchive = runArchive;
        this.store = store;
    }

    @GetMapping
    Map<String, Object> history(
            @RequestParam(required = false) @Nullable String kind,
            @RequestParam(defaultValue = "false") boolean blindOnly,
            @RequestParam(required = false) @Nullable String status,
            @RequestParam(required = false) @Nullable String regime,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        if (kind != null && !kind.equals("session") && !kind.equals("algo_run")) {
            throw new InvalidRequestException("kind must be session or algo_run");
        }
        if (status != null && !STATUSES.contains(status)) {
            throw new InvalidRequestException("Unknown status");
        }
        Regime regimeFilter = null;
        if (regime != null) {
            regimeFilter = Regime.fromCode(regime).orElseThrow(() -> new InvalidRequestException("Unknown regime"));
        }
        if (page < 1) {
            throw new InvalidRequestException("page must be at least 1");
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("pageSize must be between 1 and " + MAX_PAGE_SIZE);
        }

        List<Map<String, Object>> items;
        long total;
        if ("session".equals(kind)) {
            SessionArchive.HistoryPage found = sessionArchive.history(
                    new SessionArchive.HistoryFilter(blindOnly, status, regimeFilter, page, pageSize));
            total = found.total();
            items = mapSessions(found.items());
        } else if ("algo_run".equals(kind)) {
            RunArchive.HistoryPage found =
                    runArchive.history(new RunArchive.HistoryFilter(blindOnly, status, regimeFilter, page, pageSize));
            total = found.total();
            items = mapRuns(found.items());
        } else {
            long need = Math.min((long) page * pageSize, 2000L);
            SessionArchive.HistoryPage sessionsPage = sessionArchive.history(
                    new SessionArchive.HistoryFilter(blindOnly, status, regimeFilter, 1, (int) need));
            RunArchive.HistoryPage runsPage =
                    runArchive.history(new RunArchive.HistoryFilter(blindOnly, status, regimeFilter, 1, (int) need));
            total = sessionsPage.total() + runsPage.total();
            long offset = (long) (page - 1) * pageSize;
            if (offset >= total || offset >= need) {
                items = List.of();
            } else {
                List<SortableEntry> merged = new ArrayList<>();
                Map<UUID, StoredReports.Stored> stored = store.findAll(sessionsPage.items().stream()
                        .filter(l -> l instanceof SessionLookup.Finished)
                        .map(l -> ((SessionLookup.Finished) l).session().id())
                        .toList());
                for (SessionLookup lookup : sessionsPage.items()) {
                    SessionRecord s = sessionOf(lookup);
                    merged.add(new SortableEntry(s.createdAt().toInstant(), s.id(), entry(lookup, stored)));
                }
                for (RunArchive.HistoryItem item : runsPage.items()) {
                    RunRecord r = item.run();
                    merged.add(new SortableEntry(r.createdAt().toInstant(), r.id(), runEntry(r, item.algoName())));
                }
                merged.sort(Comparator.comparing(SortableEntry::createdAt)
                        .reversed()
                        .thenComparing(SortableEntry::id, Comparator.reverseOrder()));
                int start = (int) offset;
                int end = Math.min(start + pageSize, merged.size());
                items = new ArrayList<>(end - start);
                for (int i = start; i < end; i++) {
                    items.add(merged.get(i).body());
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", total);
        out.put("page", page);
        out.put("pageSize", pageSize);
        out.put("totalPages", (total + pageSize - 1) / pageSize);
        return out;
    }

    private List<Map<String, Object>> mapSessions(List<SessionLookup> lookups) {
        Map<UUID, StoredReports.Stored> stored = store.findAll(lookups.stream()
                .filter(l -> l instanceof SessionLookup.Finished)
                .map(l -> ((SessionLookup.Finished) l).session().id())
                .toList());
        List<Map<String, Object>> items = new ArrayList<>(lookups.size());
        for (SessionLookup lookup : lookups) {
            items.add(entry(lookup, stored));
        }
        return items;
    }

    private static List<Map<String, Object>> mapRuns(List<RunArchive.HistoryItem> runs) {
        List<Map<String, Object>> items = new ArrayList<>(runs.size());
        for (RunArchive.HistoryItem item : runs) {
            items.add(runEntry(item.run(), item.algoName()));
        }
        return items;
    }

    private static SessionRecord sessionOf(SessionLookup lookup) {
        return switch (lookup) {
            case SessionLookup.Unfinished u -> u.session();
            case SessionLookup.Finished f -> f.session();
            case SessionLookup.Missing _ -> throw new IllegalStateException("Сессия из списка исчезла");
        };
    }

    private Map<String, Object> entry(SessionLookup lookup, Map<UUID, StoredReports.Stored> stored) {
        return switch (lookup) {
            case SessionLookup.Unfinished u -> entry(u.session(), u.playedDays(), null);
            case SessionLookup.Finished f -> {
                StoredReports.Stored report = stored.get(f.session().id());
                yield report != null
                        ? entry(f.session(), report.playedDays(), report.netReturnPct())
                        : entry(f.session(), sessionArchive.playedDays(f.session()), null);
            }
            case SessionLookup.Missing _ -> throw new IllegalStateException("Сессия из списка исчезла");
        };
    }

    private static Map<String, Object> entry(SessionRecord session, int playedDays, @Nullable String netReturnPct) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", session.id());
        out.put("kind", "session");
        out.put("owner", "USER");
        out.put("createdAt", ViewFormat.instant(session.createdAt().toInstant().toEpochMilli()));
        out.put("regime", session.regime().code());
        out.put("playedDays", playedDays);
        out.put("totalDays", session.tradingDays());
        out.put("algoName", null);
        out.put("blind", session.blind());
        out.put("status", session.status());
        if (!session.revealed()) {
            // INV-7: ни названия, ни бумаг, ни периода, ни итога
            out.put("view", "BLIND_UNFINISHED");
            return out;
        }
        List<String> papers = new ArrayList<>();
        for (PaperRecord paper : session.papers()) {
            papers.add(paper.secid());
        }
        Map<String, Object> period = new LinkedHashMap<>();
        period.put("startDate", session.periodStart().toString());
        period.put("endDate", session.periodEnd().toString());
        out.put("view", "REVEALED");
        out.put("title", PublicViews.revealedTitle(session));
        out.put("papers", papers);
        out.put("period", period);
        out.put("netReturnPct", netReturnPct);
        return out;
    }

    private static Map<String, Object> runEntry(RunRecord run, String algoName) {
        String historyStatus = run.isActive() ? "running" : run.status();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", run.id());
        out.put("kind", "algo_run");
        out.put("owner", "USER");
        out.put("createdAt", ViewFormat.instant(run.createdAt().toInstant().toEpochMilli()));
        out.put("regime", run.regime().code());
        out.put("playedDays", run.playedDays() != null ? run.playedDays() : 0);
        out.put("totalDays", run.tradingDays());
        out.put("algoName", algoName);
        out.put("blind", run.blind());
        out.put("status", historyStatus);
        if (!run.revealed()) {
            // INV-7: незавершённый слепой прогон ничего не раскрывает
            out.put("view", "BLIND_UNFINISHED");
            return out;
        }
        List<String> papers = new ArrayList<>(run.papers().size());
        for (RunPaperRecord paper : run.papers()) {
            papers.add(paper.secid());
        }
        Map<String, Object> period = new LinkedHashMap<>();
        period.put("startDate", run.periodStart().toString());
        period.put("endDate", run.periodEnd().toString());
        String periodTitle = TITLE_DATE.format(run.periodStart()) + " — " + TITLE_DATE.format(run.periodEnd());
        String title = papers.isEmpty()
                ? algoName + " · " + periodTitle
                : algoName + " · " + periodTitle + " · " + String.join(", ", papers);
        out.put("view", "REVEALED");
        out.put("title", title);
        out.put("papers", papers);
        out.put("period", period);
        out.put("netReturnPct", run.netReturnPct());
        return out;
    }
}
