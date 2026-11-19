package ru.tinvest.trainer.marketdata;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Чтение каталога архива снапшота (ClickHouse). Все даты — московские. */
interface ArchiveCatalog {

    record ArchivePaper(String secid, String sector, long lotSize, BigDecimal priceStep) {}

    /** Снапшот целиком загружен ({@code snapshots_ready}). */
    boolean isReady(String snapshotKey);

    /** Торговые дни календаря, по возрастанию. */
    List<LocalDate> tradingDays(String snapshotKey);

    /** Значения {@code interval_sec}, для которых есть свечи бумаг (без бенчмарка IMOEX). */
    Set<Integer> intervals(String snapshotKey);

    List<ArchivePaper> securities(String snapshotKey);

    /** Для каждой бумаги — число разных дней со свечами интервала {@code intervalSec} в [from, to]. */
    Map<String, Integer> daysWithCandles(String snapshotKey, int intervalSec, LocalDate from, LocalDate to);

    /** Бумаги со сплитом или консолидацией в [from, to]. */
    Set<String> withCorporateActions(String snapshotKey, LocalDate from, LocalDate to);
}
