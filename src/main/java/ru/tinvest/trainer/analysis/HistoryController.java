package ru.tinvest.trainer.analysis;

import java.util.ArrayList;
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

/**
 * История сессий и прогонов пользователя (контракт {@code api/openapi.yaml}, {@code listHistory}): страница по дате
 * создания, новые сверху. Прогонов алгоритмов пока нет — {@code kind=algo_run} даёт пустую страницу, а форма записи
 * (union {@code HistoryEntry}) уже контрактная. Незавершённая слепая запись ничего не раскрывает (INV-7), у
 * незавершённой открытой итог {@code null} (INV-1).
 */
@RestController
@RequestMapping("/api/v1/history")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class HistoryController {

    private static final Set<String> STATUSES = Set.of("running", "completed", "finished_early", "bankrupt");
    private static final int MAX_PAGE_SIZE = 100;

    private final SessionArchive archive;
    private final StoredReports store;

    HistoryController(SessionArchive archive, StoredReports store) {
        this.archive = archive;
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

        List<Object> items = new ArrayList<>();
        long total = 0;
        if (!"algo_run".equals(kind)) {
            SessionArchive.HistoryPage found =
                    archive.history(new SessionArchive.HistoryFilter(blindOnly, status, regimeFilter, page, pageSize));
            total = found.total();
            // итог завершённых — из сохранённых отчётов, без вызова воркера; отчёта ещё нет (строится) — итог null
            Map<UUID, StoredReports.Stored> stored = store.findAll(found.items().stream()
                    .filter(l -> l instanceof SessionLookup.Finished)
                    .map(l -> ((SessionLookup.Finished) l).session().id())
                    .toList());
            for (SessionLookup lookup : found.items()) {
                items.add(entry(lookup, stored));
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

    private Map<String, Object> entry(SessionLookup lookup, Map<UUID, StoredReports.Stored> stored) {
        return switch (lookup) {
            case SessionLookup.Unfinished u -> entry(u.session(), u.playedDays(), null);
            case SessionLookup.Finished f -> {
                StoredReports.Stored report = stored.get(f.session().id());
                yield report != null
                        ? entry(f.session(), report.playedDays(), report.netReturnPct())
                        : entry(f.session(), archive.playedDays(f.session()), null);
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
}
