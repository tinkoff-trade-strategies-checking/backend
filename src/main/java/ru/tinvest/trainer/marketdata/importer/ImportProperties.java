package ru.tinvest.trainer.marketdata.importer;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки импорта (профиль {@code import}): {@code --import.from}, {@code --import.to}, {@code --import.tickers}
 * (без них — пул IMOEX), {@code --import.label} (новый снапшот на тех же параметрах).
 */
@ConfigurationProperties("import")
public record ImportProperties(
        LocalDate from,
        LocalDate to,
        List<String> tickers,
        @DefaultValue("") String label,
        @DefaultValue Iss iss,
        @DefaultValue Cbr cbr,
        @DefaultValue ClickHouseProperties clickhouse) {

    /** {@code pause} — пауза между запросами; ошибка — до {@code maxRetries} повторов; пустой ответ — {@code emptyRetries}. */
    public record Iss(
            @DefaultValue("https://iss.moex.com/iss") String baseUrl,
            @DefaultValue("300ms") Duration pause,
            @DefaultValue("6") int maxRetries,
            @DefaultValue("2s") Duration initialBackoff,
            @DefaultValue("60s") Duration maxBackoff,
            @DefaultValue("3") int emptyRetries) {}

    public record Cbr(
            @DefaultValue("https://www.cbr.ru/hd_base/ruonia/dynamics/")
            String url) {}

    public record ClickHouseProperties(
            @DefaultValue("http://localhost:8123") String url,
            @DefaultValue("ttsc") String database,
            @DefaultValue("ttsc") String user,
            @DefaultValue("") String password) {}
}
