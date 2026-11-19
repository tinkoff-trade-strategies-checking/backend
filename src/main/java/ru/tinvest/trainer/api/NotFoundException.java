package ru.tinvest.trainer.api;

/** Запрошенный ресурс не найден: HTTP 404. Текст сообщения заготовлен автором и безопасен для клиента. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String safeMessage) {
        super(safeMessage);
    }
}
