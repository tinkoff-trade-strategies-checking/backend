package ru.tinvest.trainer.sessions;

/**
 * Достигнут лимит дневного убытка (kill-switch): открытие новых позиций (buy) заблокировано. Сообщение не содержит
 * денежных сумм, тикеров и дат (INV-7).
 */
final class DailyLossLimitException extends RuntimeException {

    DailyLossLimitException() {
        super("Daily loss limit reached; opening new positions is blocked");
    }
}
