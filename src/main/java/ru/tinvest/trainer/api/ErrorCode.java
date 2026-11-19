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
    /** 422: нет периода под параметры запуска (то же имя, что {@code LaunchRejectReason}). */
    NO_MATCHING_PERIOD,
    /** 422: слишком много свечей. */
    TOO_MANY_BARS,
    /** 422: недопустимые параметры запуска. */
    INVALID_PARAMS,
    /** 422: на периоде корпоративное действие. */
    CORPORATE_ACTION_ON_PERIOD;

    /** Кладёт код в тело ошибки. */
    public ProblemDetail applyTo(ProblemDetail problem) {
        problem.setProperty("code", name());
        return problem;
    }
}
