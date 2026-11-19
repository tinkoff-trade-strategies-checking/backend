package ru.tinvest.trainer.marketdata;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки модуля. {@code pool} — тикеры IMOEX для выбора бумаг (пусто — все бумаги снапшота: импорт без
 * {@code --import.tickers} грузит именно состав IMOEX). {@code maxBars} — предел свечей ручной сессии
 * ({@code session.manual.max-bars}, decisions.md, «Запуск сессии и прогона»). {@code clickhouse} — чтение каталога
 * архива; не задан ({@code marketdata.clickhouse.url} пуст) — подбор запуска не создаётся.
 */
@ConfigurationProperties("marketdata")
public record MarketdataProperties(
        @DefaultValue List<String> pool,
        @DefaultValue("5000") int maxBars,
        ClickHouseProperties clickhouse) {

    public record ClickHouseProperties(
            String url,
            @DefaultValue("default") String database,
            @DefaultValue("default") String user,
            @DefaultValue("") String password,
            @DefaultValue("30s") Duration timeout) {}
}
