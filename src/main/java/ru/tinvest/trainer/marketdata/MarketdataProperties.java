package ru.tinvest.trainer.marketdata;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки модуля. {@code pool} — тикеры IMOEX для выбора бумаг (пусто — все бумаги снапшота: импорт без
 * {@code --import.tickers} грузит именно состав IMOEX). {@code maxBars} — предел свечей ручной сессии Арены
 * ({@code session.manual.max-bars}, decisions.md, «Запуск сессии и прогона»); {@code runMaxBars} — предел свечей
 * прогона и сравнения алгоритмов в Мастерской. {@code cacheSnapshots} — сколько
 * последних снапшотов держит кэш каталога, {@code maxConcurrentScans} — сколько чтений из ClickHouse одновременно,
 * {@code warmUp} — прогреть кэш последнего готового снапшота в фоне при старте. {@code clickhouse} — чтение каталога
 * архива, обязателен: без {@code marketdata.clickhouse.url} ({@code MARKETDATA_CLICKHOUSE_URL}) приложение не
 * стартует (кроме профиля {@code import}); пустая строка считается отсутствием.
 */
@ConfigurationProperties("marketdata")
public record MarketdataProperties(
        @DefaultValue List<String> pool,
        @DefaultValue("25000") int maxBars,
        @DefaultValue("250000") int runMaxBars,
        @DefaultValue("2") int cacheSnapshots,
        @DefaultValue("2") int maxConcurrentScans,
        @DefaultValue("true") boolean warmUp,
        @DefaultValue Execution execution,
        ClickHouseProperties clickhouse) {

    /** Настройки ClickHouse; нет {@code url} (или он пуст) — {@link IllegalStateException}: подбор запуска обязателен. */
    public ClickHouseProperties requiredClickhouse() {
        if (clickhouse == null || clickhouse.url() == null || clickhouse.url().isBlank()) {
            throw new IllegalStateException(
                    "Не задан marketdata.clickhouse.url (переменная MARKETDATA_CLICKHOUSE_URL)");
        }
        return clickhouse;
    }

    /**
     * Значения модели исполнения для экрана запуска ({@code ExecutionRulesSummary}, decisions.md, «Модель исполнения:
     * значения по умолчанию»). Источник правды для расчёта — конфигурация core; здесь только то, что показывается.
     */
    public record Execution(
            @DefaultValue("0.3") String commissionPct,
            @DefaultValue("0.01") String exchangeFeePct,
            @DefaultValue("0.1") String spreadPct,
            @DefaultValue("0.1") String slippageKappa,
            @DefaultValue("10") String liquidityLimitPct) {}

    public record ClickHouseProperties(
            String url,
            @DefaultValue("default") String database,
            @DefaultValue("default") String user,
            @DefaultValue("") String password,
            @DefaultValue("120s") Duration timeout) {}
}
