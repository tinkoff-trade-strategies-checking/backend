package ru.tinvest.trainer.api;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Общий обработчик исключений HTTP API в формате RFC 9457 (ProblemDetail). */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail handleInvalidRequest(InvalidRequestException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Bad Request");
        ErrorCode.BAD_REQUEST.applyTo(problem);
        return problem;
    }

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail handleNotFound(NotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Not Found");
        ErrorCode.NOT_FOUND.applyTo(problem);
        return problem;
    }

    @ExceptionHandler({
        DependencyUnavailableException.class,
        DataAccessResourceFailureException.class,
        CannotGetJdbcConnectionException.class
    })
    ProblemDetail handleDependencyUnavailable(Exception ex) {
        log.error("Зависимость недоступна", ex);
        // Внутренний текст (хосты, SQL, учётные данные) клиенту не отдаём.
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Service temporarily unavailable");
        problem.setTitle("Service Unavailable");
        ErrorCode.SERVICE_UNAVAILABLE.applyTo(problem);
        return problem;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Необработанная ошибка HTTP API", ex);
        // Не раскрываем клиенту внутренние детали, стек и потенциальные данные слепого режима.
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
        problem.setTitle("Internal Server Error");
        ErrorCode.INTERNAL_ERROR.applyTo(problem);
        return problem;
    }

    /** Стандартные ошибки Spring (битое тело, 404 без обработчика, 405, 406, 415 и др.) получают код по статусу. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            ProblemCodes.forStatus(statusCode).applyTo(problem);
        }
        return response;
    }
}
