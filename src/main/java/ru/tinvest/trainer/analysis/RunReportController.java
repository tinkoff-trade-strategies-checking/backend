package ru.tinvest.trainer.analysis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.tinvest.trainer.api.ErrorCode;
import ru.tinvest.trainer.api.InvalidRequestException;
import ru.tinvest.trainer.api.NotFoundException;
import ru.tinvest.trainer.simulation.RunArchive;
import ru.tinvest.trainer.simulation.RunLookup;
import ru.tinvest.trainer.simulation.RunRecord;
import ru.tinvest.trainer.simulation.RunResultRecord;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Отчёт и выгрузка прогона алгоритма ({@code getRunReport}, {@code exportRunReport}). Слепой незавершённый прогон
 * ничего не раскрывает (INV-7), чужой или несуществующий — {@code NOT_FOUND} / 404.
 */
@RestController
@RequestMapping("/api/v1/runs/{runId}/report")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class RunReportController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final MediaType ZIP = MediaType.parseMediaType("application/zip");

    private final RunArchive archive;
    private final ObjectMapper json;

    RunReportController(RunArchive archive, ObjectMapper json) {
        this.archive = archive;
        this.json = json;
    }

    @GetMapping
    Map<String, Object> report(@PathVariable UUID runId) {
        return switch (archive.lookup(runId)) {
            case RunLookup.Missing _ -> statusBody("NOT_FOUND", runId);
            case RunLookup.Unfinished _ -> statusBody("NOT_FINISHED", runId);
            case RunLookup.Finished f ->
                archive.findResult(runId)
                        .map(r -> ready(json.readTree(r.reportJson())))
                        .orElseGet(() -> "failed".equals(f.run().status())
                                ? ready(archive.failedReportFallback(f.run()))
                                : statusBody("BUILDING", runId));
        };
    }

    @GetMapping("/export")
    ResponseEntity<?> export(@PathVariable UUID runId, @RequestParam String format) {
        boolean xlsx = "xlsx".equals(format);
        if (!xlsx && !"csv".equals(format)) {
            throw new InvalidRequestException("format must be xlsx or csv");
        }
        RunRecord run = archive.findOwned(runId).orElseThrow(() -> new NotFoundException("Run not found"));
        if (run.isActive()) {
            return conflict(ErrorCode.NOT_FINISHED, "The run is not finished");
        }
        RunResultRecord result = archive.findResult(runId).orElse(null);
        JsonNode reportNode;
        if (result != null) {
            reportNode = json.readTree(result.reportJson());
        } else if ("failed".equals(run.status())) {
            reportNode = json.valueToTree(archive.failedReportFallback(run));
        } else {
            return conflict(ErrorCode.REPORT_BUILDING, "The report is being built");
        }
        List<ReportSheets.Sheet> sheets =
                ReportSheets.ofAlgoRun(reportNode, run.snapshotId().toString());
        byte[] body = xlsx ? ReportFiles.xlsx(sheets) : ReportFiles.csvZip(sheets);
        return file(runId, xlsx, body);
    }

    private static Map<String, Object> ready(Object report) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "READY");
        body.put("report", report);
        return body;
    }

    private static Map<String, Object> statusBody(String status, UUID runId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("runId", runId);
        return body;
    }

    private static ResponseEntity<byte[]> file(UUID runId, boolean xlsx, byte[] body) {
        String name = "run-" + runId + (xlsx ? ".xlsx" : ".zip");
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(name).build().toString())
                .contentType(xlsx ? XLSX : ZIP)
                .contentLength(body.length)
                .body(body);
    }

    private static ResponseEntity<ProblemDetail> conflict(ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail);
        problem.setTitle("Conflict");
        code.applyTo(problem);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
