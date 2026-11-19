package ru.tinvest.trainer.api;

/** Ошибка входных данных клиента: HTTP 400. Текст сообщения заготовлен автором и безопасен для клиента. */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String safeMessage) {
        super(safeMessage);
    }
}
