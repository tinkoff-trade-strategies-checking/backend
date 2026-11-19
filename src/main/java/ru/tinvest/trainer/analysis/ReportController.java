package ru.tinvest.trainer.analysis;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.tinvest.trainer.sessions.PublicViews;
import ru.tinvest.trainer.sessions.SessionArchive;
import ru.tinvest.trainer.sessions.SessionLookup;

/**
 * Отчёт сессии (контракт {@code api/openapi.yaml}, {@code getReport}): всегда 200 со {@code status}. Отчёт строится в
 * фоне после завершения ({@link ReportBuilder}) и читается из {@code analysis.session_report}; пока его нет —
 * {@code BUILDING}, а запрос подстраховывает сборку, если событие завершения не отработало. Идущая сессия, в том числе
 * слепая, — {@code NOT_FINISHED} без раскрытия (INV-1, INV-7); чужая — {@code NOT_FOUND}, как несуществующая.
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/report")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class ReportController {

    private final SessionArchive archive;
    private final StoredReports store;
    private final ReportBuilder builder;

    ReportController(SessionArchive archive, StoredReports store, ReportBuilder builder) {
        this.archive = archive;
        this.store = store;
        this.builder = builder;
    }

    @GetMapping
    Map<String, Object> report(@PathVariable UUID sessionId) {
        return switch (archive.lookup(sessionId)) {
            case SessionLookup.Missing _ -> PublicViews.notFound(sessionId);
            case SessionLookup.Unfinished u -> PublicViews.notFinished(u);
            case SessionLookup.Finished f ->
                store.find(f.session().id()).map(this::ready).orElseGet(() -> building(f));
        };
    }

    private Map<String, Object> ready(StoredReports.Stored stored) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "READY");
        body.put("report", stored.report());
        return body;
    }

    private Map<String, Object> building(SessionLookup.Finished finished) {
        builder.buildInBackground(finished.session().id());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "BUILDING");
        body.put("sessionId", finished.session().id());
        return body;
    }
}
