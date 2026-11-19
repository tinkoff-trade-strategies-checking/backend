package ru.tinvest.trainer.analysis;

import java.util.List;
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
import ru.tinvest.trainer.sessions.SessionArchive;
import ru.tinvest.trainer.sessions.SessionRecord;

/**
 * Выгрузка отчёта (ФТ-17, {@code exportReport}): файл из сохранённого отчёта без повторного расчёта и без вызова
 * воркера. Чужая и несуществующая сессия — 404; идущая (в том числе слепая, INV-7) и отчёт, который ещё строится, — 409
 * без каких-либо данных сессии.
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/report/export")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class ReportExportController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final MediaType ZIP = MediaType.parseMediaType("application/zip");

    private final SessionArchive archive;
    private final StoredReports store;
    private final ReportBuilder builder;

    ReportExportController(SessionArchive archive, StoredReports store, ReportBuilder builder) {
        this.archive = archive;
        this.store = store;
        this.builder = builder;
    }

    @GetMapping
    ResponseEntity<?> export(@PathVariable UUID sessionId, @RequestParam String format) {
        boolean xlsx = "xlsx".equals(format);
        if (!xlsx && !"csv".equals(format)) {
            throw new InvalidRequestException("format must be xlsx or csv");
        }
        SessionRecord session =
                archive.findOwned(sessionId).orElseThrow(() -> new NotFoundException("Session not found"));
        if (!SessionArchive.isFinished(session)) {
            return conflict(ErrorCode.NOT_FINISHED, "The session is not finished");
        }
        return store.find(session.id())
                .<ResponseEntity<?>>map(stored -> {
                    List<ReportSheets.Sheet> sheets = ReportSheets.of(
                            stored.report(), session.snapshotId().toString(), archive.commandLog(session));
                    byte[] body = xlsx ? ReportFiles.xlsx(sheets) : ReportFiles.csvZip(sheets);
                    return file(sessionId, xlsx, body);
                })
                .orElseGet(() -> {
                    builder.buildInBackground(session.id());
                    return conflict(ErrorCode.REPORT_BUILDING, "The report is being built");
                });
    }

    private static ResponseEntity<byte[]> file(UUID sessionId, boolean xlsx, byte[] body) {
        String name = "report-" + sessionId + (xlsx ? ".xlsx" : ".zip");
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
