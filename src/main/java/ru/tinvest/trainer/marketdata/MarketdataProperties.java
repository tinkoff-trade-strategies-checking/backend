package ru.tinvest.trainer.marketdata;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки модуля. {@code pool} — тикеры IMOEX для выбора бумаг (пусто — все бумаги снапшота: импорт без
 * {@code --import.tickers} грузит именно состав IMOEX). {@code maxBars} — предел свечей ручной сессии
 * ({@code session.manual.max-bars}, decisions.md, «Запуск сессии и прогона»). {@code clickhouse} — чтение каталога
 * архива, обязателен: без {@code marketdata.clickhouse.url} ({@code MARKETDATA_CLICKHOUSE_URL}) приложение не
 * стартует (кроме профиля {@code import}); пустая строка считается отсутствием.
 */
@ConfigurationProperties("marketdata")
public record MarketdataProperties(
        @DefaultValue List<String> pool,
        @DefaultValue("5000") int maxBars,
        ClickHouseProperties clickhouse) {

    /** Настройки ClickHouse; нет {@code url} (или он пуст) — {@link IllegalStateException}: подбор запуска обязателен. */
    public ClickHouseProperties requiredClickhouse() {
        if (clickhouse == null || clickhouse.url() == null || clickhouse.url().isBlank()) {
            throw new IllegalStateException(
                    "Не задан marketdata.clickhouse.url (переменная MARKETDATA_CLICKHOUSE_URL)");
        }
        return clickhouse;
    }

    public record ClickHouseProperties(
            String url,
            @DefaultValue("default") String database,
            @DefaultValue("default") String user,
            @DefaultValue("") String password,
            @DefaultValue("120s") Duration timeout) {}
}
