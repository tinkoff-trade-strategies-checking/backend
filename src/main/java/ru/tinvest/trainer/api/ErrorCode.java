package ru.tinvest.trainer.api;

import org.springframework.http.ProblemDetail;

/**
 * Машинные коды {@code Problem.code} — единственный источник значений. Список совпадает с enum {@code ProblemCode} в
 * {@code api/openapi.yaml} (сверяет {@code ErrorCodeContractTest}). Строковых литералов кода в ответах не заводим.
 */
public enum ErrorCode {
    /** 422: ключ идемпотентности уже использован с другим запросом. */
    IDEMPOTENCY_KEY_REUSED,
    /** 409: первый запрос с этим ключом ещё выполняется. */
    IDEMPOTENCY_KEY_IN_PROGRESS,
    /** 409: сессия уже завершена. */
    SESSION_NOT_ACTIVE,
    /** 409: команда не подходит состоянию плеера. */
    PLAYER_STATE_CONFLICT,
    /** 409: выгрузка отчёта недоступна, пока сессия не завершена. */
    NOT_FINISHED,
    /** 409: отчёт ещё строится, запрос повторяют позже. */
    REPORT_BUILDING,
    /** 422: нет периода под параметры запуска (то же имя, что {@code LaunchRejectReason}). */
    NO_MATCHING_PERIOD,
    /** 422: слишком много свечей. */
    TOO_MANY_BARS,
    /** 422: недопустимые параметры запуска. */
    INVALID_PARAMS,
    /** 422: на периоде корпоративное действие. */
    CORPORATE_ACTION_ON_PERIOD,
    /** 422: стратегия содержит статические ошибки DSL. */
    STRATEGY_INVALID,
    /** 409: конфликт состояния или дублирующееся имя алгоритма. */
    CONFLICT,
    /** 400: некорректный запрос — валидация, тело, параметры. */
    BAD_REQUEST,
    /** 401: нет аутентификации. */
    UNAUTHENTICATED,
    /** 401: неверная почта или пароль при входе. */
    INVALID_CREDENTIALS,
    /** 403: нет или неверен CSRF-токен. */
    CSRF_INVALID,
    /** 403: доступ запрещён по другой причине. */
    FORBIDDEN,
    /** 404: нет ресурса или маршрута. */
    NOT_FOUND,
    /** 405: метод не поддерживается. */
    METHOD_NOT_ALLOWED,
    /** 406: нет подходящего формата ответа. */
    NOT_ACCEPTABLE,
    /** 415: тип содержимого не поддерживается. */
    UNSUPPORTED_MEDIA_TYPE,
    /** 429: слишком много запросов (попыток входа). */
    TOO_MANY_REQUESTS,
    /** 500: внутренняя ошибка. */
    INTERNAL_ERROR,
    /** 503: зависимость (воркер ядра, база, архив) недоступна. */
    SERVICE_UNAVAILABLE;

    /** Кладёт код в тело ошибки. */
    public ProblemDetail applyTo(ProblemDetail problem) {
        problem.setProperty("code", name());
        return problem;
    }
}
