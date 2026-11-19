package ru.tinvest.trainer.api;

import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Общий обработчик исключений HTTP API в формате RFC 9457 (ProblemDetail). */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, ex.getMessage() != null ? ex.getMessage() : "Invalid request");
        problem.setTitle("Bad Request");
        return problem;
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail handleNotFound(NoSuchElementException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, ex.getMessage() != null ? ex.getMessage() : "Resource not found");
        problem.setTitle("Not Found");
        return problem;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Необработанная ошибка HTTP API", ex);
        // Не раскрываем клиенту внутренние детали, стек и потенциальные данные слепого режима.
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
        problem.setTitle("Internal Server Error");
        return problem;
    }
}
