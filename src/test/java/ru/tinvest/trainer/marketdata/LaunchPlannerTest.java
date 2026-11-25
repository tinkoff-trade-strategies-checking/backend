package ru.tinvest.trainer.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.tinvest.trainer.api.DependencyUnavailableException;
import ru.tinvest.trainer.api.NotFoundException;
import ru.tinvest.trainer.marketdata.ArchiveCatalog.ArchivePaper;
import ru.tinvest.trainer.worker.PeriodCategory;
import ru.tinvest.trainer.worker.PeriodClassification;
import ru.tinvest.trainer.worker.PeriodWindow;
import ru.tinvest.trainer.worker.SimulationWorkerClient;

/** Подбор запуска на поддельных архиве и воркере: без сети и контейнеров. */
class LaunchPlannerTest {

    private static final int DAYS = 600;
    private static final BigDecimal CAPITAL = new BigDecimal("1000000.00");

    private final List<LocalDate> calendar = weekdays(DAYS);
    private final Snapshot snapshot =
            new Snapshot(UUID.randomUUID(), UUID.randomUUID().toString(), OffsetDateTime.now(), "test");
    private final FakeArchive archive = new FakeArchive();
    private final FakeWorker worker = new FakeWorker();
    private final Snapshots snapshots = new Snapshots(null) {
        @Override
        public List<Snapshot> findAllNewestFirst() {
            return List.of(snapshot);
        }

        @Override
        public Optional<Snapshot> find(UUID id) {
            return snapshot.id().equals(id) ? Optional.of(snapshot) : Optional.empty();
        }
    };
    private final LaunchPlanner planner =
            new DefaultLaunchPlanner(snapshots, archive, worker, Set.of(), 5000, () -> 1L);

    private static List<LocalDate> weekdays(int n) {
        List<LocalDate> days = new ArrayList<>();
        LocalDate d = LocalDate.of(2023, 1, 2);
        while (days.size() < n) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days.add(d);
            }
            d = d.plusDays(1);
        }
        return days;
    }

    private static LaunchRequest random(Regime regime, int paperCount) {
        return new LaunchRequest(20, Timeframe.D1, regime, StartMode.RANDOM, null, paperCount, null, CAPITAL);
    }

    private static LaunchRequest custom(Regime regime, LocalDate date, String... secids) {
        List<UUID> ids = new ArrayList<>();
        for (String s : secids) {
            ids.add(Papers.id(s));
        }
        return new LaunchRequest(20, Timeframe.D1, regime, StartMode.CUSTOM, date, null, ids, CAPITAL);
    }

    private static LaunchPlan plan(LaunchResult r) {
        assertThat(r).isInstanceOf(LaunchPlan.class);
        return (LaunchPlan) r;
    }

    private static LaunchRejection rejection(LaunchResult r) {
        assertThat(r).isInstanceOf(LaunchRejection.class);
        return (LaunchRejection) r;
    }

    @Test
    void everyRegimePicksWindowOfThatRegime() {
        for (Regime regime : Regime.values()) {
            for (long seed = 0; seed < 15; seed++) {
                LaunchPlan p = plan(planner.plan(random(regime, 3), null, seed));
                assertThat(p.regime()).isEqualTo(regime);
                assertThat(worker.categoryAt(calendar.indexOf(p.startDate()))).isEqualTo(FakeWorker.category(regime));
                assertThat(calendar.indexOf(p.endDate()) - calendar.indexOf(p.startDate()))
                        .isEqualTo(19);
                assertThat(p.tradingDays()).isEqualTo(20);
                assertThat(p.blind()).isTrue();
                assertThat(p.snapshot()).isEqualTo(snapshot);
                assertThat(p.capital()).isEqualTo(CAPITAL);
                assertThat(p.p33()).isEqualTo(0.15);
                assertThat(p.p80()).isEqualTo(0.35);
            }
        }
    }

    @Test
    void blindPapersHaveSectorsNoDuplicatesNoSplitsAndFullCoverage() {
        for (long seed = 0; seed < 40; seed++) {
            LaunchPlan p = plan(planner.plan(random(Regime.MEDIUM, 5), null, seed));
            assertThat(p.papers()).hasSize(5);
            assertThat(p.papers()).extracting(PlannedPaper::sector).doesNotContain("");
            assertThat(p.papers()).extracting(PlannedPaper::secid).doesNotHaveDuplicates();
            assertThat(p.papers()).extracting(PlannedPaper::secid).doesNotContain("NOSECTOR");
            int start = calendar.indexOf(p.startDate());
            for (PlannedPaper paper : p.papers()) {
                if (paper.secid().equals("SPLIT")) {
                    assertThat(start <= FakeArchive.SPLIT_AT && FakeArchive.SPLIT_AT <= start + 19)
                            .isFalse();
                }
                if (paper.secid().equals("LATE")) {
                    assertThat(start).isGreaterThanOrEqualTo(FakeArchive.LATE_FROM);
                }
            }
        }
    }

    @Test
    void sameSeedGivesSamePlanDifferentSeedsGiveDifferentWindows() {
        LaunchRequest request = random(Regime.CALM, 4);
        assertThat(planner.plan(request, null, 42L)).isEqualTo(planner.plan(request, null, 42L));
        Set<LocalDate> starts = new HashSet<>();
        for (long seed = 0; seed < 10; seed++) {
            starts.add(plan(planner.plan(request, null, seed)).startDate());
        }
        assertThat(starts.size()).isGreaterThan(3);
    }

    @Test
    void impossiblePairsAreRejectedEarlyByLowerBound() {
        var r = new LaunchRequest(60, Timeframe.M5, Regime.CALM, StartMode.RANDOM, null, 2, null, CAPITAL);
        LaunchRejection rej = rejection(planner.plan(r));
        assertThat(rej.reason()).isEqualTo(LaunchRejectReason.TOO_MANY_BARS);
        assertThat(rej.barsCount()).isEqualTo(60 * 106);
        assertThat(rej.maxBars()).isEqualTo(5000);
        assertThat(rej.field()).isEqualTo("durationDays");
        // воркер и архив не дёргали
        assertThat(worker.calls).isZero();
    }

    @Test
    void barsLimitIsDecidedByExactStepsFromArchive() {
        // M10 × 60 дней: нижняя граница 3180 проходит ранний отсев, решает точное число шагов по архиву
        var m10 = new LaunchRequest(60, Timeframe.M10, Regime.CALM, StartMode.RANDOM, null, 2, null, CAPITAL);
        archive.steps = 5000;
        LaunchPlan ok = plan(planner.plan(m10, null, 1L));
        assertThat(ok.barsCount()).isEqualTo(5000);
        assertThat(archive.lastStepSec).isEqualTo(600);
        assertThat(archive.lastStepPapers).hasSize(2);

        // слепой режим: окон с подходящим числом шагов нет — отказ без числа шагов скрытого окна
        archive.steps = 5001;
        archive.stepCalls = 0;
        LaunchRejection rej = rejection(planner.plan(m10, null, 1L));
        assertThat(rej.reason()).isEqualTo(LaunchRejectReason.TOO_MANY_BARS);
        assertThat(rej.barsCount()).isNull();
        assertThat(rej.maxBars()).isEqualTo(5000);
        assertThat(rej.field()).isEqualTo("durationDays");
        // проверок не больше предела, а не по запросу на каждое из сотен окон
        assertThat(archive.stepCalls).isEqualTo(50);
    }

    @Test
    void blindWindowOverTheLimitIsSkippedNotRejected() {
        var m10 = new LaunchRequest(20, Timeframe.M10, Regime.MEDIUM, StartMode.RANDOM, null, 2, null, CAPITAL);
        // окна с чётным началом превышают лимит, с нечётным — нет
        archive.stepsFor = days -> calendar.indexOf(days.getFirst()) % 2 == 0 ? 5001 : 4000;
        for (long seed = 0; seed < 30; seed++) {
            LaunchPlan p = plan(planner.plan(m10, null, seed));
            assertThat(calendar.indexOf(p.startDate()) % 2).isEqualTo(1);
            assertThat(p.barsCount()).isEqualTo(4000);
        }
    }

    @Test
    void sessionStepsAreCountedOverTheCalendarDaysOfTheWindow() {
        int i = 30;
        var request = new LaunchRequest(
                20,
                Timeframe.H1,
                regimeAt(i),
                StartMode.CUSTOM,
                calendar.get(i),
                null,
                List.of(Papers.id("A")),
                CAPITAL);
        plan(planner.plan(request));
        assertThat(archive.lastStepDays).isEqualTo(calendar.subList(i, i + 20));
    }

    @Test
    void exactStepsAreCountedOnCustomDateToo() {
        int i = 30;
        var request = new LaunchRequest(
                20,
                Timeframe.H1,
                regimeAt(i),
                StartMode.CUSTOM,
                calendar.get(i),
                null,
                List.of(Papers.id("A")),
                CAPITAL);
        archive.steps = 5001;
        assertThat(rejection(planner.plan(request)).barsCount()).isEqualTo(5001);
        archive.steps = 280;
        assertThat(plan(planner.plan(request)).barsCount()).isEqualTo(280);
        assertThat(archive.lastStepDays).isEqualTo(calendar.subList(i, i + 20));
    }

    @Test
    void ownDateOutOfArchiveRangeIsRejected() {
        LaunchRejection before = rejection(planner.plan(custom(Regime.CALM, LocalDate.of(2020, 1, 6), "A")));
        assertThat(before.reason()).isEqualTo(LaunchRejectReason.INVALID_PARAMS);
        assertThat(before.field()).isEqualTo("customStartDate");

        // за 19 дней до конца календаря окно из 20 дней уже не помещается
        LaunchRejection tail = rejection(planner.plan(custom(Regime.CALM, calendar.get(DAYS - 19), "A")));
        assertThat(tail.reason()).isEqualTo(LaunchRejectReason.INVALID_PARAMS);

        // выходной — не торговый день
        LaunchRejection weekend = rejection(planner.plan(custom(Regime.CALM, LocalDate.of(2023, 3, 4), "A")));
        assertThat(weekend.reason()).isEqualTo(LaunchRejectReason.INVALID_PARAMS);
    }

    @Test
    void ownDateOfMatchingRegimeGivesPlanWithUsersPapers() {
        int i = 30;
        Regime regime = regimeAt(i);
        LaunchPlan p = plan(planner.plan(custom(regime, calendar.get(i), "A", "B", "NOSECTOR")));
        assertThat(p.blind()).isFalse();
        assertThat(p.startDate()).isEqualTo(calendar.get(i));
        assertThat(p.endDate()).isEqualTo(calendar.get(i + 19));
        assertThat(p.regime()).isEqualTo(regime);
        assertThat(p.papers()).extracting(PlannedPaper::secid).containsExactly("A", "B", "NOSECTOR");
        PlannedPaper a = p.papers().getFirst();
        assertThat(a.lotSize()).isEqualTo(10);
        assertThat(a.priceStep()).isEqualByComparingTo("0.01");
        assertThat(a.id()).isEqualTo(Papers.id("A"));
        assertThat(p.sigma()).isEqualTo(worker.sigmaAt(i));
    }

    @Test
    void ownDateOfOtherRegimeIsNoMatchingPeriod() {
        int i = 30;
        Regime other = regimeAt(i) == Regime.CALM ? Regime.STRESS : Regime.CALM;
        LaunchRejection rej = rejection(planner.plan(custom(other, calendar.get(i), "A")));
        assertThat(rej.reason()).isEqualTo(LaunchRejectReason.NO_MATCHING_PERIOD);
        assertThat(rej.field()).isEqualTo("customStartDate");
    }

    @Test
    void ownPapersAreValidated() {
        int i = 30;
        Regime regime = regimeAt(i);
        LocalDate date = calendar.get(i);

        LaunchRejection unknown = rejection(planner.plan(custom(regime, date, "A", "NOPE")));
        assertThat(unknown.reason()).isEqualTo(LaunchRejectReason.INVALID_PARAMS);
        assertThat(unknown.field()).isEqualTo("paperIds");

        assertThat(rejection(planner.plan(custom(regime, date))).field()).isEqualTo("paperIds");
        assertThat(rejection(planner.plan(custom(regime, date, "A", "A"))).field())
                .isEqualTo("paperIds");
        var withNull = new LaunchRequest(
                20,
                Timeframe.D1,
                regime,
                StartMode.CUSTOM,
                date,
                null,
                java.util.Arrays.asList(Papers.id("A"), null),
                CAPITAL);
        assertThat(rejection(planner.plan(withNull)).field()).isEqualTo("paperIds");
        LaunchPlan sixPapers = plan(planner.plan(custom(regime, date, "A", "B", "C", "D", "E", "F")));
        assertThat(sixPapers.papers()).hasSize(6);

        // LATE торгуется только с дня LATE_FROM
        LaunchRejection late = rejection(planner.plan(custom(regime, date, "A", "LATE")));
        assertThat(late.reason()).isEqualTo(LaunchRejectReason.NO_MATCHING_PERIOD);
        assertThat(late.field()).isEqualTo("paperIds");

        // у HOLE нет свечей в торговый день 50, зато есть свеча в выходной: по числу дней покрытие сошлось бы
        LaunchRejection hole = rejection(planner.plan(custom(regimeAt(40), calendar.get(40), "A", "HOLE")));
        assertThat(hole.reason()).isEqualTo(LaunchRejectReason.NO_MATCHING_PERIOD);
        assertThat(hole.field()).isEqualTo("paperIds");
        assertThat(planner.plan(custom(regimeAt(60), calendar.get(60), "A", "HOLE")))
                .isInstanceOf(LaunchPlan.class);

        int s = FakeArchive.SPLIT_AT - 5;
        LaunchRejection split = rejection(planner.plan(custom(regimeAt(s), calendar.get(s), "A", "SPLIT")));
        assertThat(split.reason()).isEqualTo(LaunchRejectReason.CORPORATE_ACTION_ON_PERIOD);
    }

    @Test
    void corporateActionInTheWarmupWindowRejectsThePeriodToo() {
        // окно целиком после сплита, но прогрев (200 шагов до начала) захватывает день сплита
        int s = FakeArchive.SPLIT_AT + 3;
        LaunchRejection split = rejection(planner.plan(custom(regimeAt(s), calendar.get(s), "A", "SPLIT")));
        assertThat(split.reason()).isEqualTo(LaunchRejectReason.CORPORATE_ACTION_ON_PERIOD);
        assertThat(split.field()).isEqualTo("paperIds");
    }

    @Test
    void invalidParamsAreRejected() {
        var duration = new LaunchRequest(30, Timeframe.D1, Regime.CALM, StartMode.RANDOM, null, 2, null, CAPITAL);
        assertThat(rejection(planner.plan(duration)).field()).isEqualTo("durationDays");
        assertThat(rejection(planner.plan(random(Regime.CALM, 0))).field()).isEqualTo("paperCount");
        assertThat(rejection(planner.plan(random(Regime.CALM, -1))).field()).isEqualTo("paperCount");
        assertThat(plan(planner.plan(random(Regime.CALM, 6), null, 1L)).papers())
                .hasSize(6);
        var noCount = new LaunchRequest(20, Timeframe.D1, Regime.CALM, StartMode.RANDOM, null, null, null, CAPITAL);
        assertThat(rejection(planner.plan(noCount)).field()).isEqualTo("paperCount");
        for (String bad : List.of("0", "9999.99", "100000000.01", "-5", "500000.001")) {
            var capital = new LaunchRequest(
                    20, Timeframe.D1, Regime.CALM, StartMode.RANDOM, null, 2, null, new BigDecimal(bad));
            assertThat(rejection(planner.plan(capital)).field()).as(bad).isEqualTo("initialCapital");
        }
        for (String good : List.of("10000", "10000.50", "100000", "500000", "1000000", "5000000", "100000000")) {
            var capital = new LaunchRequest(
                    20, Timeframe.D1, Regime.CALM, StartMode.RANDOM, null, 2, null, new BigDecimal(good));
            assertThat(planner.plan(capital, null, 1L)).as(good).isInstanceOf(LaunchPlan.class);
        }
        assertThat(LaunchPlanner.CAPITAL_PRESETS).hasSize(4).contains(LaunchPlanner.DEFAULT_CAPITAL);
        // лишние поля не игнорируются молча
        var randomWithIds = new LaunchRequest(
                20, Timeframe.D1, Regime.CALM, StartMode.RANDOM, null, 2, List.of(Papers.id("A")), CAPITAL);
        assertThat(rejection(planner.plan(randomWithIds)).field()).isEqualTo("paperIds");
        var randomWithDate =
                new LaunchRequest(20, Timeframe.D1, Regime.CALM, StartMode.RANDOM, calendar.get(3), 2, null, CAPITAL);
        assertThat(rejection(planner.plan(randomWithDate)).field()).isEqualTo("customStartDate");
        var customWithCount = new LaunchRequest(
                20, Timeframe.D1, Regime.CALM, StartMode.CUSTOM, calendar.get(3), 2, List.of(Papers.id("A")), CAPITAL);
        assertThat(rejection(planner.plan(customWithCount)).field()).isEqualTo("paperCount");
        var fraction = new LaunchRequest(
                20, Timeframe.D1, Regime.CALM, StartMode.RANDOM, null, 2, null, new BigDecimal("100.001"));
        assertThat(rejection(planner.plan(fraction)).field()).isEqualTo("initialCapital");
        var noDate = new LaunchRequest(
                20, Timeframe.D1, Regime.CALM, StartMode.CUSTOM, null, null, List.of(Papers.id("A")), CAPITAL);
        assertThat(rejection(planner.plan(noDate)).field()).isEqualTo("customStartDate");
    }

    @Test
    void timeframeMustBeAvailableInArchive() {
        archive.intervals = Set.of(86400);
        var m5 = new LaunchRequest(20, Timeframe.M5, Regime.CALM, StartMode.RANDOM, null, 2, null, CAPITAL);
        LaunchRejection rej = rejection(planner.plan(m5));
        assertThat(rej.reason()).isEqualTo(LaunchRejectReason.INVALID_PARAMS);
        assertThat(rej.field()).isEqualTo("timeframe");
        assertThat(planner.plan(random(Regime.CALM, 2), null, 1L)).isInstanceOf(LaunchPlan.class);
        assertThat(archive.lastCandleInterval).isEqualTo(86400);

        // шаг есть как агрегат минутных свечей
        archive.intervals = Set.of(60);
        planner.plan(m5, null, 1L);
        assertThat(archive.lastCandleInterval).isEqualTo(60);
    }

    @Test
    void warmUpReadsLatestSnapshotIntoTheCatalog() {
        archive.intervals = Set.of(60, 86400);
        planner.warmUp();
        assertThat(archive.candleIntervalsRead).containsExactlyInAnyOrder(60, 86400);
        archive.ready = false;
        assertThatThrownBy(planner::warmUp).isInstanceOf(DependencyUnavailableException.class);
    }

    @Test
    void availableTimeframesFollowArchiveIntervals() {
        archive.intervals = Set.of(60);
        assertThat(planner.availableTimeframes(null)).containsExactly(Timeframe.values());
        archive.intervals = Set.of(86400, 3600);
        assertThat(planner.availableTimeframes(null)).containsExactly(Timeframe.H1, Timeframe.D1);
        archive.intervals = Set.of(300);
        assertThat(planner.availableTimeframes(null)).containsExactly(Timeframe.M5);
    }

    @Test
    void maxDurationsAreConservativeByMostStepsPerDayAndOnlyForAvailableTimeframes() {
        archive.intervals = Set.of(60);
        archive.maxPerDay = Map.of(60, 815, 300, 163, 600, 82, 900, 55, 3600, 14, 86400, 1);
        var limits = planner.maxDurations(null);
        assertThat(limits.keySet()).containsExactly(Timeframe.values());
        assertThat(limits)
                .containsEntry(Timeframe.M1, 6)
                .containsEntry(Timeframe.M5, 30)
                .containsEntry(Timeframe.M10, 60)
                .containsEntry(Timeframe.M15, 90)
                .containsEntry(Timeframe.H1, 357)
                .containsEntry(Timeframe.D1, 5000);
        // граница: ровно 5000 шагов укладываются, 5001 — нет
        archive.maxPerDay = Map.of(60, 100, 300, 100, 600, 100, 900, 100, 3600, 100, 86400, 3);
        assertThat(planner.maxDurations(null)).containsEntry(Timeframe.D1, 1666).containsEntry(Timeframe.M1, 50);
        // один проход на источник свечей и все шаги сразу
        assertThat(archive.maxPerDayCalls).isEqualTo(2);
        assertThat(archive.lastMaxPerDaySource).isEqualTo(60);
        assertThat(archive.lastMaxPerDaySteps).containsExactlyInAnyOrder(60, 300, 600, 900, 3600, 86400);

        // недоступные шаги не попадают; готовые дневные свечи — отдельный источник
        archive.intervals = Set.of(86400, 3600);
        archive.maxPerDay = Map.of(3600, 14, 86400, 1);
        assertThat(planner.maxDurations(null).keySet()).containsExactly(Timeframe.H1, Timeframe.D1);
        // нет данных по шагу за день — лимит, а не деление на ноль
        archive.maxPerDay = Map.of();
        assertThat(planner.maxDurations(null)).containsEntry(Timeframe.D1, 5000);
    }

    @Test
    void maxDurationsMatchWhatTheLaunchAccepts() {
        archive.intervals = Set.of(60);
        archive.maxPerDay = Map.of(600, 82);
        int allowed = planner.maxDurations(null).get(Timeframe.M10);
        assertThat(allowed).isEqualTo(60);
        // 60 дней × 82 шага = 4920 укладывается, 120 дней — нет (ранний отсев по нижней границе 53 × 120 = 6360)
        assertThat(allowed * 82).isLessThanOrEqualTo(5000);
        assertThat((allowed + 1) * 82).isGreaterThan(5000);
        var m10 = new LaunchRequest(120, Timeframe.M10, Regime.CALM, StartMode.RANDOM, null, 2, null, CAPITAL);
        assertThat(rejection(planner.plan(m10)).reason()).isEqualTo(LaunchRejectReason.TOO_MANY_BARS);
    }

    @Test
    void contractCodesOfEnums() {
        assertThat(Timeframe.fromCode("15m")).contains(Timeframe.M15);
        assertThat(Timeframe.fromCode("1d")).contains(Timeframe.D1);
        assertThat(Timeframe.fromCode("2d")).isEmpty();
        assertThat(Regime.fromCode("stress")).contains(Regime.STRESS);
        assertThat(Regime.fromCode("STRESS")).isEmpty();
        assertThat(Regime.MEDIUM.code()).isEqualTo("medium");
    }

    @Test
    void noMatchingPeriodWhenArchiveHasNoSuchWindowOrEnoughPapers() {
        worker.onlyCategories = Set.of(PeriodCategory.CALM);
        LaunchRejection rej = rejection(planner.plan(random(Regime.STRESS, 2), null, 1L));
        assertThat(rej.reason()).isEqualTo(LaunchRejectReason.NO_MATCHING_PERIOD);

        // слепых бумаг (с сектором и без сплита) в пуле меньше, чем просят
        worker.onlyCategories = null;
        LaunchPlanner small =
                new DefaultLaunchPlanner(snapshots, archive, worker, Set.of("A", "B", "NOSECTOR"), 5000, () -> 1L);
        // бумаг с сектором меньше, чем просят: смена категории не поможет - INVALID_PARAMS / paperCount
        LaunchRejection few = rejection(small.plan(random(Regime.CALM, 3), null, 1L));
        assertThat(few.reason()).isEqualTo(LaunchRejectReason.INVALID_PARAMS);
        assertThat(few.field()).isEqualTo("paperCount");
        assertThat(small.plan(random(Regime.CALM, 2), null, 1L)).isInstanceOf(LaunchPlan.class);
    }

    @Test
    void snapshotWithoutBenchmarkSecurityFailsClearlyAtLaunch() {
        archive.benchmark = false;
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> planner.plan(random(Regime.CALM, 2), null, 1L))
                .isInstanceOf(ru.tinvest.trainer.api.DependencyUnavailableException.class)
                .hasMessageContaining("старой версией импортёра");
    }

    @Test
    void poolIsConfiguredAndOwnPapersMustBelongToIt() {
        LaunchPlanner small = new DefaultLaunchPlanner(snapshots, archive, worker, Set.of("A", "B"), 5000, () -> 1L);
        assertThat(small.papers(null)).extracting(PlannedPaper::secid).containsExactly("A", "B");
        int i = 30;
        assertThat(rejection(small.plan(custom(regimeAt(i), calendar.get(i), "C")))
                        .field())
                .isEqualTo("paperIds");
    }

    @Test
    void noReadySnapshotIsDependencyUnavailableAndUnknownSnapshotIsNotFound() {
        archive.ready = false;
        assertThatThrownBy(() -> planner.plan(random(Regime.CALM, 2)))
                .isInstanceOf(DependencyUnavailableException.class);
        assertThatThrownBy(() -> planner.papers(null)).isInstanceOf(DependencyUnavailableException.class);
        assertThatThrownBy(() -> planner.plan(random(Regime.CALM, 2), snapshot.id(), 1L))
                .isInstanceOf(NotFoundException.class);
        archive.ready = true;
        assertThatThrownBy(() -> planner.plan(random(Regime.CALM, 2), UUID.randomUUID(), 1L))
                .isInstanceOf(NotFoundException.class);
        assertThat(planner.plan(random(Regime.CALM, 2), snapshot.id(), 1L)).isInstanceOf(LaunchPlan.class);
    }

    @Test
    void logsDoNotLeakDatesTickersOrMetrics() {
        LaunchPlan p = plan(planner.plan(random(Regime.CALM, 2), null, 5L));
        String text =
                p + " " + p.papers() + " " + worker.classifyPeriods("k", 20, calendar.get(0), calendar.get(30), null);
        assertThat(text)
                .doesNotContain(p.startDate().toString())
                .doesNotContain(p.papers().getFirst().secid())
                .doesNotContain(String.valueOf(p.sigma()));
    }

    private Regime regimeAt(int i) {
        return switch (worker.categoryAt(i)) {
            case CALM -> Regime.CALM;
            case MEDIUM -> Regime.MEDIUM;
            case STRESS -> Regime.STRESS;
        };
    }

    /** Окно с началом в индексе i календаря: категория по {@code i % 3}. */
    private final class FakeWorker implements SimulationWorkerClient {
        int calls;
        Set<PeriodCategory> onlyCategories;

        @Override
        public ru.tinvest.trainer.worker.WorkerTypes.ReplayResult replaySession(
                ru.tinvest.trainer.worker.WorkerTypes.Market market,
                java.util.List<ru.tinvest.trainer.worker.WorkerTypes.JournalRecord> journal,
                long upToStep,
                boolean includeHistory,
                boolean blind) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ru.tinvest.trainer.worker.WorkerTypes.ReplayResult replayWithReport(
                ru.tinvest.trainer.worker.WorkerTypes.Market market,
                java.util.List<ru.tinvest.trainer.worker.WorkerTypes.JournalRecord> journal,
                long upToStep) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int tradingDaysAt(ru.tinvest.trainer.worker.WorkerTypes.Market market, long upToStep) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ru.tinvest.trainer.worker.WorkerTypes.EstimateResult estimateOrder(
                ru.tinvest.trainer.worker.WorkerTypes.Market market,
                java.util.List<ru.tinvest.trainer.worker.WorkerTypes.JournalRecord> journal,
                long upToStep,
                ru.tinvest.trainer.worker.WorkerTypes.OrderParams order) {
            throw new UnsupportedOperationException();
        }

        static PeriodCategory category(Regime r) {
            return switch (r) {
                case CALM -> PeriodCategory.CALM;
                case MEDIUM -> PeriodCategory.MEDIUM;
                case STRESS -> PeriodCategory.STRESS;
            };
        }

        PeriodCategory categoryAt(int i) {
            return PeriodCategory.values()[i % 3];
        }

        double sigmaAt(int i) {
            return 0.1 + i / 1000.0;
        }

        @Override
        public String ping() {
            return "fake";
        }

        @Override
        public PeriodClassification classifyPeriods(
                String snapshotKey, int windowLength, LocalDate from, LocalDate to, LocalDate startDate) {
            calls++;
            List<PeriodWindow> windows = new ArrayList<>();
            for (int i = 0; i + windowLength <= calendar.size(); i++) {
                LocalDate start = calendar.get(i);
                if (startDate != null ? !start.equals(startDate) : start.isBefore(from) || start.isAfter(to)) {
                    continue;
                }
                PeriodCategory c = categoryAt(i);
                if (onlyCategories != null && !onlyCategories.contains(c)) {
                    continue;
                }
                windows.add(new PeriodWindow(start, calendar.get(i + windowLength - 1), sigmaAt(i), 0.05, c));
            }
            return new PeriodClassification(windows, 0.15, 0.35);
        }
    }

    private final class FakeArchive implements ArchiveCatalog {
        static final int LATE_FROM = 300;
        static final int SPLIT_AT = 200;

        boolean ready = true;
        boolean benchmark = true;
        Set<Integer> intervals = Set.of(60);
        int lastCandleInterval;
        int steps = 100;
        int lastStepSec;
        List<String> lastStepPapers;
        Map<Integer, Integer> maxPerDay = Map.of();
        int maxPerDayCalls;
        int lastMaxPerDaySource;
        List<Integer> lastMaxPerDaySteps;
        List<LocalDate> lastStepDays;
        int stepCalls;
        java.util.function.Function<List<LocalDate>, Integer> stepsFor;
        final Set<Integer> candleIntervalsRead = new HashSet<>();

        private final Map<String, ArchivePaper> papers = new HashMap<>();
        private final Map<String, Integer> firstDay = new HashMap<>();

        @Override
        public boolean hasBenchmark(String snapshotKey) {
            return benchmark;
        }

        FakeArchive() {
            add("A", "Финансы", 0);
            add("B", "Нефть и газ", 0);
            add("C", "IT", 0);
            add("D", "Металлы и добыча", 0);
            add("E", "Транспорт", 0);
            add("F", "Химия и нефтехимия", 0);
            add("SPLIT", "Потребсектор", 0);
            add("LATE", "Телекоммуникации", LATE_FROM);
            add("NOSECTOR", "", 0);
            add("HOLE", "Банки", 0);
        }

        private void add(String secid, String sector, int first) {
            papers.put(secid, new ArchivePaper(secid, sector, 10, new BigDecimal("0.01")));
            firstDay.put(secid, first);
        }

        @Override
        public boolean isReady(String snapshotKey) {
            return ready;
        }

        @Override
        public List<LocalDate> tradingDays(String snapshotKey) {
            return calendar;
        }

        @Override
        public Set<Integer> intervals(String snapshotKey) {
            return intervals;
        }

        @Override
        public List<ArchivePaper> securities(String snapshotKey) {
            return new ArrayList<>(papers.values());
        }

        @Override
        public Map<String, Set<LocalDate>> candleDays(String snapshotKey, int intervalSec) {
            lastCandleInterval = intervalSec;
            candleIntervalsRead.add(intervalSec);
            Map<String, Set<LocalDate>> result = new HashMap<>();
            for (var e : firstDay.entrySet()) {
                Set<LocalDate> dates = new HashSet<>();
                for (int i = e.getValue(); i < calendar.size(); i++) {
                    if (!(e.getKey().equals("HOLE") && i == 50)) {
                        dates.add(calendar.get(i));
                    }
                }
                if (e.getKey().equals("HOLE")) {
                    dates.add(LocalDate.of(2023, 1, 7)); // суббота: торги в выходной
                }
                result.put(e.getKey(), dates);
            }
            return result;
        }

        @Override
        public Map<String, List<LocalDate>> corporateActions(String snapshotKey) {
            return Map.of("SPLIT", List.of(calendar.get(SPLIT_AT)));
        }

        @Override
        public int sessionSteps(
                String snapshotKey,
                int sourceIntervalSec,
                int stepSec,
                Collection<String> secids,
                List<LocalDate> windowDays) {
            lastStepSec = stepSec;
            lastStepPapers = new ArrayList<>(secids);
            lastStepDays = windowDays;
            stepCalls++;
            return stepsFor != null ? stepsFor.apply(windowDays) : steps;
        }

        @Override
        public Map<Integer, Integer> maxStepsPerDay(String snapshotKey, int sourceIntervalSec, List<Integer> stepSecs) {
            maxPerDayCalls++;
            lastMaxPerDaySource = sourceIntervalSec;
            lastMaxPerDaySteps = stepSecs;
            return maxPerDay;
        }
    }

    @Test
    void manyPapersAreAcceptedInCustomAndRandomModes() {
        List<String> extra = new ArrayList<>();
        for (int idx = 1; idx <= 30; idx++) {
            String secid = "SEC" + idx;
            archive.add(secid, "Сектор" + (idx % 5), 0);
            extra.add(secid);
        }
        int i = 30;
        LaunchPlan customPlan = plan(planner.plan(custom(regimeAt(i), calendar.get(i), extra.toArray(String[]::new))));
        assertThat(customPlan.papers()).hasSize(30);

        LaunchPlan randomPlan = plan(planner.plan(random(Regime.CALM, 30), null, 1L));
        assertThat(randomPlan.papers()).hasSize(30);
    }

    @Test
    void archiveDateRangeReturnsFirstAndLastTradingDayOrNullWhenEmpty() {
        LaunchPlanner.DateRange range = planner.archiveDateRange(null);
        assertThat(range).isNotNull();
        assertThat(range.from()).isEqualTo(calendar.getFirst());
        assertThat(range.to()).isEqualTo(calendar.getLast());
        assertThat(range.from()).isBeforeOrEqualTo(range.to());

        calendar.clear();
        assertThat(planner.archiveDateRange(null)).isNull();
    }
}
