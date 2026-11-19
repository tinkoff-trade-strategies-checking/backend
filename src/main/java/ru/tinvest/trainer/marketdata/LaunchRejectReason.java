package ru.tinvest.trainer.marketdata;

/** Причины отказа в запуске, как в контракте ({@code LaunchRejectReason}). */
public enum LaunchRejectReason {
    NO_MATCHING_PERIOD,
    TOO_MANY_BARS,
    INVALID_PARAMS,
    CORPORATE_ACTION_ON_PERIOD
}
