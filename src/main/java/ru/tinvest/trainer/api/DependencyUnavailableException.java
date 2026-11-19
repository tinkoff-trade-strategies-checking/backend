package ru.tinvest.trainer.api;

/**
 * Внешняя зависимость недоступна (Simulation Worker, ClickHouse и т. п.): HTTP 503. Клиенту уходит фиксированный
 * текст, сообщение исключения только для логов.
 */
public class DependencyUnavailableException extends RuntimeException {

    public DependencyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
