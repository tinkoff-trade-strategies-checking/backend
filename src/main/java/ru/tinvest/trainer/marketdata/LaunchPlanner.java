package ru.tinvest.trainer.marketdata;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Подбор периода и бумаг для запуска ручной сессии (backend#23, decisions.md: «Запуск сессии и прогона»,
 * «Стресс-сценарии»). Не создаёт сессию и ничего не пишет: возвращает {@link LaunchPlan} или {@link LaunchRejection}.
 * Категорию окна считает core ({@code ClassifyPeriods}); недоступность воркера или ClickHouse и отсутствие готового
 * снапшота — {@link ru.tinvest.trainer.api.DependencyUnavailableException} (503).
 *
 * <p>Случайный выбор (слепая сессия) детерминирован по {@code seed}: окна категории перемешиваются, берётся первое,
 * где набирается нужное число подходящих бумаг, то есть окно равномерно среди допустимых. В сессии сохраняется
 * выбранный период, а не seed.
 */
public interface LaunchPlanner {

    /** Длительности ручной сессии в торговых днях. */
    Set<Integer> DURATIONS = Set.of(20, 60, 120, 250);

    int DEFAULT_DURATION = 60;

    /** Пресеты начального капитала, ₽ (решение владельца продукта, 19.11.2026); по умолчанию — {@link #DEFAULT_CAPITAL}. */
    List<BigDecimal> CAPITAL_PRESETS = List.of(
            new BigDecimal("100000"), new BigDecimal("500000"), new BigDecimal("1000000"), new BigDecimal("5000000"));

    BigDecimal DEFAULT_CAPITAL = new BigDecimal("1000000");

    /** Своё значение капитала — от 10 000 до 100 000 000 ₽, до 2 знаков. */
    BigDecimal MIN_CAPITAL = new BigDecimal("10000");

    BigDecimal MAX_CAPITAL = new BigDecimal("100000000");

    int MIN_PAPERS = 1;
    int MAX_PAPERS = 5;

    /** Подбор по последнему готовому снапшоту, случайность — из генератора приложения. */
    LaunchResult plan(LaunchRequest request);

    /**
     * @param snapshotId снапшот или {@code null} — последний готовый
     * @param seed зерно случайного выбора окна и бумаг слепой сессии
     * @throws ru.tinvest.trainer.api.NotFoundException снапшот с этим id не найден или не готов
     * @throws ru.tinvest.trainer.api.DependencyUnavailableException нет ни одного готового снапшота
     */
    LaunchResult plan(LaunchRequest request, @Nullable UUID snapshotId, long seed);

    /** Читает в кэш каталога данные последнего готового снапшота (вызывается в фоне при старте). */
    default void warmUp() {}

    /** Бумаги пула в снапшоте (для экрана выбора бумаг при своей дате); {@code null} — последний готовый. */
    List<PlannedPaper> papers(@Nullable UUID snapshotId);

    /** Шаги свечей, для которых в архиве есть данные; {@code null} — последний готовый снапшот. */
    List<Timeframe> availableTimeframes(@Nullable UUID snapshotId);

    /**
     * Для каждого доступного шага — наибольшая длительность в торговых днях, при которой шагов шкалы сессии заведомо не
     * больше лимита ({@code max-bars}): оценка консервативная, по наибольшему числу шагов за день в архиве снапшота
     * по всем бумагам. Дни и порядок — как у {@link #availableTimeframes}; пограничные случаи решает отказ
     * {@code TOO_MANY_BARS} при запуске. Нет данных за день у шага — {@code max-bars}.
     */
    Map<Timeframe, Integer> maxDurations(@Nullable UUID snapshotId);
}
