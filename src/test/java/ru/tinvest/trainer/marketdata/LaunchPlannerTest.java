package ru.tinvest.trainer.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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
    private final LaunchPlanner planner = new LaunchPlanner(snapshots, archive, worker, Set.of(), 5000, () -> 1L);

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
    void tooManyBarsIsRejectedWithCounts() {
        var r = new LaunchRequest(60, Timeframe.M5, Regime.CALM, StartMode.RANDOM, null, 2, null, CAPITAL);
        LaunchRejection rej = rejection(planner.plan(r));
        assertThat(rej.reason()).isEqualTo(LaunchRejectReason.TOO_MANY_BARS);
        assertThat(rej.barsCount()).isEqualTo(60 * 163);
        assertThat(rej.maxBars()).isEqualTo(5000);
        assertThat(rej.field()).isEqualTo("durationDays");
        // воркер не дёргали
        assertThat(worker.calls).isZero();

        var ok = new LaunchRequest(250, Timeframe.H1, Regime.CALM, StartMode.RANDOM, null, 2, null, CAPITAL);
        assertThat(plan(planner.plan(ok, null, 1L)).barsCount()).isEqualTo(250 * 14);
        var okD1 = new LaunchRequest(250, Timeframe.D1, Regime.CALM, StartMode.RANDOM, null, 2, null, CAPITAL);
        assertThat(plan(planner.plan(okD1, null, 1L)).barsCount()).isEqualTo(250);
    }

    @Test
    void boundaryOfBarsLimitIsInclusive() {
        // 20 × 55 = 1100 ≤ 1100 проходит, 20 × 82 = 1640 — нет
        LaunchPlanner tight = new LaunchPlanner(snapshots, archive, worker, Set.of(), 1100, () -> 1L);
        var m15 = new LaunchRequest(20, Timeframe.M15, Regime.CALM, StartMode.RANDOM, null, 1, null, CAPITAL);
        assertThat(tight.plan(m15, null, 1L)).isInstanceOf(LaunchPlan.class);
        var m10 = new LaunchRequest(20, Timeframe.M10, Regime.CALM, StartMode.RANDOM, null, 1, null, CAPITAL);
        assertThat(rejection(tight.plan(m10, null, 1L)).barsCount()).isEqualTo(1640);
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
        assertThat(rejection(planner.plan(custom(regime, date, "A", "B", "C", "D", "E", "F")))
                        .field())
                .isEqualTo("paperIds");

        // LATE торгуется только с дня LATE_FROM
        LaunchRejection late = rejection(planner.plan(custom(regime, date, "A", "LATE")));
        assertThat(late.reason()).isEqualTo(LaunchRejectReason.NO_MATCHING_PERIOD);
        assertThat(late.field()).isEqualTo("paperIds");

        int s = FakeArchive.SPLIT_AT - 5;
        LaunchRejection split = rejection(planner.plan(custom(regimeAt(s), calendar.get(s), "A", "SPLIT")));
        assertThat(split.reason()).isEqualTo(LaunchRejectReason.CORPORATE_ACTION_ON_PERIOD);
    }

    @Test
    void invalidParamsAreRejected() {
        var duration = new LaunchRequest(30, Timeframe.D1, Regime.CALM, StartMode.RANDOM, null, 2, null, CAPITAL);
        assertThat(rejection(planner.plan(duration)).field()).isEqualTo("durationDays");
        assertThat(rejection(planner.plan(random(Regime.CALM, 0))).field()).isEqualTo("paperCount");
        assertThat(rejection(planner.plan(random(Regime.CALM, 6))).field()).isEqualTo("paperCount");
        var noCount = new LaunchRequest(20, Timeframe.D1, Regime.CALM, StartMode.RANDOM, null, null, null, CAPITAL);
        assertThat(rejection(planner.plan(noCount)).field()).isEqualTo("paperCount");
        var capital =
                new LaunchRequest(20, Timeframe.D1, Regime.CALM, StartMode.RANDOM, null, 2, null, BigDecimal.ZERO);
        assertThat(rejection(planner.plan(capital)).field()).isEqualTo("initialCapital");
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
    void noMatchingPeriodWhenArchiveHasNoSuchWindowOrEnoughPapers() {
        worker.onlyCategories = Set.of(PeriodCategory.CALM);
        LaunchRejection rej = rejection(planner.plan(random(Regime.STRESS, 2), null, 1L));
        assertThat(rej.reason()).isEqualTo(LaunchRejectReason.NO_MATCHING_PERIOD);

        // слепых бумаг (с сектором и без сплита) в пуле меньше, чем просят
        worker.onlyCategories = null;
        LaunchPlanner small =
                new LaunchPlanner(snapshots, archive, worker, Set.of("A", "B", "NOSECTOR"), 5000, () -> 1L);
        assertThat(rejection(small.plan(random(Regime.CALM, 3), null, 1L)).reason())
                .isEqualTo(LaunchRejectReason.NO_MATCHING_PERIOD);
        assertThat(small.plan(random(Regime.CALM, 2), null, 1L)).isInstanceOf(LaunchPlan.class);
    }

    @Test
    void poolIsConfiguredAndOwnPapersMustBelongToIt() {
        LaunchPlanner small = new LaunchPlanner(snapshots, archive, worker, Set.of("A", "B"), 5000, () -> 1L);
        assertThat(small.papers(null)).extracting(PlannedPaper::secid).containsExactly("A", "B");
        int i = 30;
        assertThat(rejection(small.plan(custom(regimeAt(i), calendar.get(i), "C")))
                        .field())
                .isEqualTo("paperIds");
    }

    @Test
    void noReadySnapshotIsNoMatchingPeriodAndUnknownSnapshotIsNotFound() {
        archive.ready = false;
        assertThat(rejection(planner.plan(random(Regime.CALM, 2))).reason())
                .isEqualTo(LaunchRejectReason.NO_MATCHING_PERIOD);
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
        Set<Integer> intervals = Set.of(60);
        int lastCandleInterval;

        private final Map<String, ArchivePaper> papers = new HashMap<>();
        private final Map<String, Integer> firstDay = new HashMap<>();

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
        public Map<String, Integer> daysWithCandles(String snapshotKey, int intervalSec, LocalDate from, LocalDate to) {
            lastCandleInterval = intervalSec;
            Map<String, Integer> result = new HashMap<>();
            for (var e : firstDay.entrySet()) {
                int n = 0;
                for (int i = e.getValue(); i < calendar.size(); i++) {
                    LocalDate d = calendar.get(i);
                    if (!d.isBefore(from) && !d.isAfter(to)) {
                        n++;
                    }
                }
                result.put(e.getKey(), n);
            }
            return result;
        }

        @Override
        public Set<String> withCorporateActions(String snapshotKey, LocalDate from, LocalDate to) {
            LocalDate d = calendar.get(SPLIT_AT);
            return !d.isBefore(from) && !d.isAfter(to) ? Set.of("SPLIT") : Set.of();
        }
    }
}
