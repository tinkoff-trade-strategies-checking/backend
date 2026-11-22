package ru.tinvest.trainer.workshop;

import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.tinvest.trainer.api.ErrorCode;
import ru.tinvest.trainer.api.InvalidRequestException;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyCheck;
import ru.tinvest.trainer.workshop.WorkshopViews.AlgorithmSummaryView;
import ru.tinvest.trainer.workshop.WorkshopViews.AlgorithmVersionView;
import ru.tinvest.trainer.workshop.WorkshopViews.DetailResultView;
import ru.tinvest.trainer.workshop.WorkshopViews.DraftView;
import ru.tinvest.trainer.workshop.WorkshopViews.WorkshopPageView;

/** REST API мастерской стратегий ({@code /api/v1/workshop/...}). */
@RestController
@RequestMapping("/api/v1/workshop")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class WorkshopController {

    private final WorkshopService service;
    private final WorkshopCatalogQueries queries;

    WorkshopController(WorkshopService service, WorkshopCatalogQueries queries) {
        this.service = service;
        this.queries = queries;
    }

    @GetMapping("/algorithms")
    WorkshopPageView list(
            @RequestParam(defaultValue = "published_desc") String sort,
            @RequestParam(required = false) @Nullable String authorId,
            @RequestParam(defaultValue = "false") boolean mineOnly,
            @RequestParam(defaultValue = "false") boolean cleanOnly,
            @RequestParam(required = false) @Nullable String search,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        UUID parsedAuthorId = parseOptionalUuid(authorId, "authorId");
        return queries.list(sort, parsedAuthorId, mineOnly, cleanOnly, search, page, pageSize);
    }

    @GetMapping("/algorithms/{id}")
    DetailResultView getById(@PathVariable UUID id) {
        return queries.getById(id);
    }

    @PostMapping("/algorithms")
    ResponseEntity<DraftView> create(@RequestBody CreateAlgorithmRequest body) {
        DraftView created = service.createDraft(body.name(), body.description(), body.source(), resolveAuthorName());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/algorithms/{id}/draft")
    DraftView getDraft(@PathVariable UUID id) {
        return service.getDraft(id);
    }

    @PutMapping("/algorithms/{id}/draft")
    DraftView updateDraft(@PathVariable UUID id, @RequestBody UpdateDraftRequest body) {
        return service.updateDraft(id, body.name(), body.description(), body.source());
    }

    @PostMapping("/check")
    StrategyCheck check(@RequestBody CheckRequest body) {
        return service.check(body.source(), body.paramOverrides());
    }

    @PostMapping("/algorithms/{id}/publish")
    ResponseEntity<AlgorithmVersionView> publish(
            @PathVariable UUID id, @RequestBody(required = false) @Nullable PublishRequest body) {
        String summary = body == null ? null : body.summary();
        String source = body == null ? null : body.source();
        AlgorithmVersionView published = service.publish(id, summary, source);
        return ResponseEntity.status(HttpStatus.CREATED).body(published);
    }

    @PostMapping("/algorithms/{id}/fork")
    ResponseEntity<DraftView> fork(@PathVariable UUID id, @RequestBody(required = false) @Nullable ForkRequest body) {
        Integer version = body == null ? null : body.version();
        String name = body == null ? null : body.name();
        String description = body == null ? null : body.description();
        DraftView forked = service.fork(id, version, name, description, resolveAuthorName());
        return ResponseEntity.status(HttpStatus.CREATED).body(forked);
    }

    @PatchMapping("/algorithms/{id}/visibility")
    AlgorithmSummaryView changeVisibility(@PathVariable UUID id, @RequestBody VisibilityRequest body) {
        service.changeVisibility(id, body.visibility());
        return queries.getSummary(id);
    }

    @ExceptionHandler(WorkshopConflictException.class)
    ProblemDetail handleConflict(WorkshopConflictException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Conflict");
        ex.code().applyTo(problem);
        return problem;
    }

    @ExceptionHandler(StrategyInvalidException.class)
    ProblemDetail handleStrategyInvalid(StrategyInvalidException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setTitle("Unprocessable Content");
        ErrorCode.STRATEGY_INVALID.applyTo(problem);
        StrategyCheck check = ex.check();
        problem.setProperty("lookaheadLevel", check.lookaheadLevel().name());
        problem.setProperty("diagnostics", check.diagnostics());
        return problem;
    }

    private static @Nullable UUID parseOptionalUuid(@Nullable String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.strip());
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(field + " must be a valid UUID");
        }
    }

    private static String resolveAuthorName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            return "Автор";
        }
        String raw = auth.getName().strip();
        int at = raw.indexOf('@');
        String display = at > 0 ? raw.substring(0, at) : raw;
        return display.isBlank() ? "Автор" : display;
    }

    record CreateAlgorithmRequest(
            @Nullable String name,
            @Nullable String description,
            @Nullable String source) {}

    record UpdateDraftRequest(
            @Nullable String name,
            @Nullable String description,
            @Nullable String source) {}

    record CheckRequest(@Nullable String source, @Nullable Map<String, Double> paramOverrides) {}

    record PublishRequest(
            @Nullable String summary, @Nullable String source) {}

    record ForkRequest(
            @Nullable Integer version,
            @Nullable String name,
            @Nullable String description) {}

    record VisibilityRequest(@Nullable String visibility) {}
}
