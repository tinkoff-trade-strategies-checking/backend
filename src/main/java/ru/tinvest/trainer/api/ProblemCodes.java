package ru.tinvest.trainer.api;

import org.springframework.http.HttpStatusCode;

/** Код ошибки по HTTP-статусу для ответов без собственного кода (стандартные ошибки Spring). */
final class ProblemCodes {

    private ProblemCodes() {}

    static ErrorCode forStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> ErrorCode.BAD_REQUEST;
            case 401 -> ErrorCode.UNAUTHENTICATED;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 406 -> ErrorCode.NOT_ACCEPTABLE;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case 429 -> ErrorCode.TOO_MANY_REQUESTS;
            case 503 -> ErrorCode.SERVICE_UNAVAILABLE;
            default -> status.is4xxClientError() ? ErrorCode.BAD_REQUEST : ErrorCode.INTERNAL_ERROR;
        };
    }
}
