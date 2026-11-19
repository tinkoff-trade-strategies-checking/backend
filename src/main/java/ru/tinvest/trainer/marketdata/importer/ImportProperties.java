package ru.tinvest.trainer.marketdata.importer;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
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
        @DefaultValue("false") boolean allowGaps,
        @DefaultValue Iss iss,
        @DefaultValue Cbr cbr,
        ClickHouseProperties clickhouse) {

    public ImportProperties {
        Objects.requireNonNull(clickhouse, "Не заданы import.clickhouse.* (профиль import, IMPORT_CLICKHOUSE_*)");
    }

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

    /** Без значений по умолчанию: адрес и учётные данные задаются явно ({@code IMPORT_CLICKHOUSE_*}). */
    public record ClickHouseProperties(String url, String database, String user, String password) {

        public ClickHouseProperties {
            require("IMPORT_CLICKHOUSE_URL", url, false);
            require("IMPORT_CLICKHOUSE_DATABASE", database, false);
            require("IMPORT_CLICKHOUSE_USER", user, false);
            require("IMPORT_CLICKHOUSE_PASSWORD", password, true);
        }

        /** Незаданная переменная окружения остаётся в значении литералом {@code ${...}} — это ошибка настройки. */
        private static void require(String name, String value, boolean mayBeEmpty) {
            if (value == null || value.contains("${") || (!mayBeEmpty && value.isBlank())) {
                throw new IllegalStateException("Не задана переменная окружения " + name);
            }
        }
    }
}
