package ru.tinvest.trainer.analysis;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.tinvest.trainer.api.ErrorCode;

/**
 * Постфактум-сравнение завершённой сессии с 1–5 алгоритмами ({@code compareSessionWithAlgorithms}, UC-9 п. 1а/1б).
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/report/compare")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SessionCompareController {

    private final SessionCompareService service;

    SessionCompareController(SessionCompareService service) {
        this.service = service;
    }

    record CompareRequest(@Nullable List<UUID> algorithmIds) {}

    @PostMapping
    Map<String, Object> compare(@PathVariable UUID sessionId, @RequestBody CompareRequest body) {
        return service.compare(sessionId, body.algorithmIds());
    }

    @ExceptionHandler(SessionNotFinishedException.class)
    ProblemDetail notFinished(SessionNotFinishedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Conflict");
        ErrorCode.NOT_FINISHED.applyTo(problem);
        return problem;
    }
}
