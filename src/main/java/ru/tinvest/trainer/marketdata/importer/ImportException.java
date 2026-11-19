package ru.tinvest.trainer.marketdata.importer;

/** Ошибка импорта: источник недоступен после всех повторов, ответ неразборчив или данные не проходят проверку. */
public class ImportException extends RuntimeException {

    public ImportException(String message) {
        super(message);
    }

    public ImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
