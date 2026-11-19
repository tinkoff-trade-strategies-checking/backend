package ru.tinvest.trainer.marketdata;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Чтение каталога архива снапшота (ClickHouse). Все даты — московские: торговый день — дата календаря IMOEX, все
 * сессии дня (утренняя, основная, вечерняя) входят в него. Снапшот неизменяем (INV-5), поэтому всё, кроме
 * {@link #sessionSteps}, можно кэшировать по ключу снапшота.
 */
interface ArchiveCatalog {

    record ArchivePaper(String secid, String sector, long lotSize, BigDecimal priceStep) {}

    /** Снапшот целиком загружен ({@code snapshots_ready}). */
    boolean isReady(String snapshotKey);

    /** Торговые дни календаря, по возрастанию. */
    List<LocalDate> tradingDays(String snapshotKey);

    /** Значения {@code interval_sec}, для которых есть свечи бумаг (без бенчмарка IMOEX). */
    Set<Integer> intervals(String snapshotKey);

    List<ArchivePaper> securities(String snapshotKey);

    /** Для каждой бумаги — даты (МСК), в которые есть свечи интервала {@code intervalSec}. */
    Map<String, Set<LocalDate>> candleDays(String snapshotKey, int intervalSec);

    /** Для каждой бумаги — даты сплитов и консолидаций. */
    Map<String, List<LocalDate>> corporateActions(String snapshotKey);

    /**
     * Точное число шагов общей шкалы сессии в окне: разных начал свечей шага {@code stepSec}, собранных из свечей
     * {@code sourceIntervalSec}, по объединению выбранных бумаг (шкала сессии общая для всех бумаг, это и есть «на
     * бумагу» из лимита). Считаются только свечи торговых дней окна по календарю IMOEX: свечи бумаг в выходные и
     * праздники вне календаря в шкалу не входят.
     */
    int sessionSteps(
            String snapshotKey,
            int sourceIntervalSec,
            int stepSec,
            Collection<String> secids,
            List<LocalDate> windowDays);
}
