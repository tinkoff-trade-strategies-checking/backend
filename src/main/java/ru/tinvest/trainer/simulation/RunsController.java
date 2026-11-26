package ru.tinvest.trainer.simulation;

import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ru.tinvest.trainer.api.ErrorCode;
import ru.tinvest.trainer.api.InvalidRequestException;
import ru.tinvest.trainer.marketdata.LaunchPlanner;
import ru.tinvest.trainer.marketdata.LaunchRejectReason;
import ru.tinvest.trainer.marketdata.LaunchRejection;
import ru.tinvest.trainer.marketdata.LaunchRequest;
import ru.tinvest.trainer.marketdata.PlannedPaper;
import ru.tinvest.trainer.marketdata.Regime;
import ru.tinvest.trainer.marketdata.StartMode;
import ru.tinvest.trainer.marketdata.Timeframe;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyCheck;
import ru.tinvest.trainer.workshop.StrategyCatalog;
import ru.tinvest.trainer.workshop.StrategyVersionView;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** REST API и SSE-поток прогонов алгоритмов ({@code /api/v1/runs/...}). */
@RestController
@RequestMapping("/api/v1/runs")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class RunsController {

    private static final Pattern MONEY = Pattern.compile("^-?[0-9]+(\\.[0-9]{1,2})?$");
    private static final String LAST_EVENT_ID = "Last-Event-ID";

    private final RunLaunchService launchService;
    private final Runs runs;
    private final RunEvents events;
    private final StrategyCatalog strategyCatalog;
    private final ObjectMapper json;

    RunsController(
            RunLaunchService launchService,
            Runs runs,
            RunEvents events,
            StrategyCatalog strategyCatalog,
            ObjectMapper json) {
        this.launchService = launchService;
        this.runs = runs;
        this.events = events;
        this.strategyCatalog = strategyCatalog;
        this.json = json;
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> create(@RequestBody CreateRunRequest body) {
        UUID runId = launchService.launch(
                body.requireStrategyVersionId(),
                body.safeParamOverrides(),
                Boolean.TRUE.equals(body.blind()),
                body.toLaunchRequest());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }

    @PostMapping("/compare")
    ResponseEntity<Map<String, Object>> createCompare(@RequestBody CreateCompareRunsRequest body) {
        RunLaunchService.CompareLaunchResult launched = launchService.launchCompare(
                body.algorithmIds(), Boolean.TRUE.equals(body.blind()), body.toLaunchRequest());
        return ResponseEntity.status(HttpStatus.CREATED).body(compareResponse(launched));
    }

    @GetMapping("/{runId}")
    Map<String, Object> get(@PathVariable UUID runId) {
        RunRecord run = runs.requireOwned(runId);
        RunResultRecord result = runs.findResult(runId).orElse(null);
        String algoName = strategyCatalog
                .findVersion(run.strategyVersionId())
                .map(StrategyVersionView::strategyName)
                .orElse("Алгоритм");
        long lastEventId = events.lastEventId(runId);
        return runInfo(run, algoName, lastEventId, result);
    }

    @PostMapping("/{runId}/repeat")
    ResponseEntity<Map<String, Object>> repeat(@PathVariable UUID runId) {
        UUID repeatedId = launchService.repeat(runId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", repeatedId);
        return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }

    @GetMapping("/{runId}/events")
    SseEmitter stream(
            @PathVariable UUID runId,
            @RequestHeader(name = LAST_EVENT_ID, required = false) @Nullable String lastEventId,
            HttpServletResponse response) {
        runs.requireOwned(runId);
        Long last = parseLastEventId(lastEventId);
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(0L);
        RunEvents.Subscription subscription = events.subscribe(runId, last, new RunEvents.Sink() {
            @Override
            public void send(long eventId, String type, String data) throws Exception {
                emitter.send(
                        SseEmitter.event().id(Long.toString(eventId)).name(type).data(data, MediaType.TEXT_PLAIN));
            }

            @Override
            public void ping() throws Exception {
                emitter.send(SseEmitter.event().comment("keepalive"));
            }

            @Override
            public void abort() {
                emitter.completeWithError(new java.io.IOException("Client is gone or too slow"));
            }
        });
        emitter.onCompletion(subscription::close);
        emitter.onTimeout(subscription::close);
        emitter.onError(e -> subscription.close());
        return emitter;
    }

    @ExceptionHandler(RunLaunchRejectedException.class)
    ResponseEntity<Map<String, Object>> rejected(RunLaunchRejectedException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(launchProblem(e.rejection()));
    }

    @ExceptionHandler(RunStrategyInvalidException.class)
    ProblemDetail strategyInvalid(RunStrategyInvalidException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        problem.setTitle("Unprocessable Content");
        ErrorCode.STRATEGY_INVALID.applyTo(problem);
        StrategyCheck check = e.check();
        problem.setProperty("lookaheadLevel", check.lookaheadLevel().name());
        problem.setProperty("diagnostics", check.diagnostics());
        return problem;
    }

    @ExceptionHandler(RunConflictException.class)
    ProblemDetail conflict(RunConflictException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Conflict");
        e.code().applyTo(problem);
        return problem;
    }

    @ExceptionHandler(RunQueueLimitException.class)
    ProblemDetail tooManyRuns(RunQueueLimitException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        problem.setTitle("Too Many Requests");
        ErrorCode.TOO_MANY_REQUESTS.applyTo(problem);
        return problem;
    }

    private Map<String, Object> runInfo(
            RunRecord run, String algoName, long lastEventId, @Nullable RunResultRecord result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", run.id());
        out.put("strategyVersionId", run.strategyVersionId());
        out.put("algoName", algoName);
        out.put("blind", run.blind());
        out.put("status", run.status());
        out.put("doneSteps", run.doneSteps());
        out.put("totalSteps", run.totalSteps());
        out.put("repeatOfRunId", run.repeatOfRunId());
        out.put("repeatMatch", result == null ? null : result.repeatMatch());
        out.put("lastEventId", lastEventId > 0 ? Long.toString(lastEventId) : null);
        out.put("createdAt", RunFormat.instant(run.createdAt().toInstant().toEpochMilli()));
        out.put(
                "finishedAt",
                run.finishedAt() == null
                        ? null
                        : RunFormat.instant(run.finishedAt().toInstant().toEpochMilli()));
        out.put("errorCode", run.errorCode());
        out.put("diagnostics", result == null ? List.of() : json.readTree(result.diagnosticsJson()));

        if (!run.revealed()) {
            // INV-7: до завершения слепого прогона период и бумаги не раскрываются
            out.put("period", null);
            out.put("papers", null);
            return out;
        }
        Map<String, Object> period = new LinkedHashMap<>();
        period.put("startDate", run.periodStart().toString());
        period.put("endDate", run.periodEnd().toString());
        out.put("period", period);
        if (result != null) {
            JsonNode reportNode = json.readTree(result.reportJson());
            JsonNode papersNode = reportNode.path("papers");
            out.put("papers", papersNode.isArray() ? papersNode : RunReportViews.papers(run, List.of()));
        } else {
            out.put("papers", RunReportViews.papers(run, List.of()));
        }
        return out;
    }

    private static Map<String, Object> launchProblem(LaunchRejection rejection) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", "Launch rejected");
        body.put("status", 422);
        body.put("detail", rejectDetail(rejection.reason()));
        body.put("instance", null);
        body.put("code", ErrorCode.valueOf(rejection.reason().name()).name());
        body.put("field", rejection.field());
        body.put("barsCount", rejection.barsCount());
        body.put("maxBars", rejection.maxBars());
        return body;
    }

    private static String rejectDetail(LaunchRejectReason reason) {
        return switch (reason) {
            case NO_MATCHING_PERIOD -> "No historical period matches the requested parameters";
            case TOO_MANY_BARS -> "Requested period exceeds the maximum number of bars";
            case INVALID_PARAMS -> "Launch parameters are invalid";
            case CORPORATE_ACTION_ON_PERIOD -> "Selected period contains a corporate action";
        };
    }

    private static @Nullable Long parseLastEventId(@Nullable String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            long value = Long.parseLong(header.trim());
            if (value < 0) {
                throw new InvalidRequestException("Last-Event-ID must not be negative");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new InvalidRequestException("Last-Event-ID must be an event number");
        }
    }

    private static Map<String, Object> compareResponse(RunLaunchService.CompareLaunchResult launched) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean blind = launched.blind();
        out.put("startDate", blind ? null : launched.plan().startDate().toString());
        out.put("endDate", blind ? null : launched.plan().endDate().toString());
        out.put("timeframe", launched.plan().timeframe().code());
        out.put("durationDays", launched.durationDays());
        out.put("initialCapital", RunFormat.money(launched.plan().capital()));
        out.put("blind", blind);

        List<Map<String, Object>> papers = new ArrayList<>(launched.papers().size());
        for (int i = 0; i < launched.papers().size(); i++) {
            PlannedPaper p = launched.papers().get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            if (blind) {
                // INV-7: в слепом режиме идентификатор, тикер и реальное название бумаги не раскрываются
                item.put("id", null);
                item.put("ticker", "");
                item.put("name", RunFormat.label(i));
            } else {
                item.put("id", p.id());
                item.put("ticker", p.secid());
                item.put("name", p.name().isBlank() ? p.secid() : p.name());
            }
            papers.add(item);
        }
        out.put("papers", papers);

        List<Map<String, Object>> runsList = new ArrayList<>(launched.runs().size());
        for (RunLaunchService.CompareLaunchItem r : launched.runs()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("algorithmId", r.algorithmId());
            item.put("algorithmName", r.algorithmName());
            item.put("versionId", r.versionId());
            item.put("runId", r.runId());
            item.put("status", r.status());
            runsList.add(item);
        }
        out.put("runs", runsList);
        return out;
    }

    private static LaunchRequest parseLaunchRequest(
            @Nullable Integer durationDays,
            @Nullable String timeframe,
            @Nullable String regime,
            @Nullable String startMode,
            @Nullable LocalDate customStartDate,
            @Nullable Integer paperCount,
            @Nullable List<UUID> paperIds,
            @Nullable String initialCapital) {
        if (durationDays == null) {
            throw new InvalidRequestException("durationDays is required");
        }
        Timeframe tf = timeframe == null ? null : Timeframe.fromCode(timeframe).orElse(null);
        if (tf == null) {
            throw new InvalidRequestException("timeframe is invalid");
        }
        Regime r = regime == null ? null : Regime.fromCode(regime).orElse(null);
        if (r == null) {
            throw new InvalidRequestException("regime is invalid");
        }
        StartMode mode = switch (startMode == null ? "" : startMode) {
            case "random" -> StartMode.RANDOM;
            case "custom" -> StartMode.CUSTOM;
            default -> throw new InvalidRequestException("startMode is invalid");
        };
        if (!LaunchPlanner.DURATIONS.contains(durationDays)) {
            throw new InvalidRequestException("durationDays must be one of 20, 60, 120, 250");
        }
        if (initialCapital == null || !MONEY.matcher(initialCapital).matches()) {
            throw new InvalidRequestException("initialCapital must be a decimal string with up to 2 digits");
        }
        BigDecimal capital = new BigDecimal(initialCapital);
        return new LaunchRequest(durationDays, tf, r, mode, customStartDate, paperCount, paperIds, capital);
    }

    record CreateRunRequest(
            @Nullable UUID strategyVersionId,
            @Nullable Map<String, Double> paramOverrides,
            @Nullable Boolean blind,
            @Nullable Integer durationDays,
            @Nullable String timeframe,
            @Nullable String regime,
            @Nullable String startMode,
            @Nullable LocalDate customStartDate,
            @Nullable Integer paperCount,
            @Nullable List<UUID> paperIds,
            @Nullable String initialCapital) {

        UUID requireStrategyVersionId() {
            if (strategyVersionId == null) {
                throw new InvalidRequestException("strategyVersionId is required");
            }
            return strategyVersionId;
        }

        Map<String, Double> safeParamOverrides() {
            if (paramOverrides == null || paramOverrides.isEmpty()) {
                return Map.of();
            }
            Map<String, Double> clean = new LinkedHashMap<>();
            for (Map.Entry<String, Double> entry : paramOverrides.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                    throw new InvalidRequestException("paramOverrides contains invalid entry");
                }
                if (!Double.isFinite(entry.getValue())) {
                    throw new InvalidRequestException("paramOverrides values must be finite numbers");
                }
                clean.put(entry.getKey().strip(), entry.getValue());
            }
            return clean;
        }

        LaunchRequest toLaunchRequest() {
            return parseLaunchRequest(
                    durationDays, timeframe, regime, startMode, customStartDate, paperCount, paperIds, initialCapital);
        }
    }

    record CreateCompareRunsRequest(
            @Nullable List<UUID> algorithmIds,
            @Nullable Boolean blind,
            @Nullable Integer durationDays,
            @Nullable String timeframe,
            @Nullable String regime,
            @Nullable String startMode,
            @Nullable LocalDate customStartDate,
            @Nullable Integer paperCount,
            @Nullable List<UUID> paperIds,
            @Nullable String initialCapital) {

        LaunchRequest toLaunchRequest() {
            return parseLaunchRequest(
                    durationDays, timeframe, regime, startMode, customStartDate, paperCount, paperIds, initialCapital);
        }
    }
}
