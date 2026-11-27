package ru.tinvest.trainer.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import ru.tinvest.trainer.api.InvalidRequestException;
import ru.tinvest.trainer.api.NotFoundException;

/**
 * Модульные тесты {@link BondCalculator}, {@link InstrumentsController} и миграции
 * {@code V23__asset_classes_bonds_etfs.sql} (работают без Docker, INV-4, INV-6).
 */
class BondCalculatorTest {

    private final BondCalculator calculator = new BondCalculator();

    private static BondSpec sampleOfz(boolean amortized) {
        return new BondSpec(
                "SU26248RMFS3",
                "ОФЗ 26248",
                "Минфин РФ",
                "OFZ",
                new BigDecimal("1000.00"),
                new BigDecimal("10.0000"),
                new BigDecimal("50.0000"),
                182,
                LocalDate.of(2027, 7, 2),
                LocalDate.of(2030, 1, 1),
                null,
                new BigDecimal("0.00"),
                new BigDecimal("10.25"),
                new BigDecimal("2.65"),
                amortized,
                new BigDecimal("100.00"),
                "RUB");
    }

    @Test
    void calculatesParBondCleanDirtyNkdAndEffectiveYtm() {
        BondSpec bond = sampleOfz(false);
        // ровно в начале купонного периода (за 182 дня до следующего купона 2027-07-02 -> 2027-01-01)
        LocalDate asOf = LocalDate.of(2027, 1, 1);

        BondCalculator.BondCalculation calc = calculator.calculate(bond, new BigDecimal("100.00"), asOf);

        assertThat(calc.cleanPriceRub()).isEqualByComparingTo("1000.00");
        assertThat(calc.accruedInterestRub()).isEqualByComparingTo("0.00");
        assertThat(calc.dirtyPriceRub()).isEqualByComparingTo("1000.00");
        assertThat(calc.currentYieldPct()).isEqualByComparingTo("10.00");
        // Полугодовой купон 50 на номинал 1000 -> эффективная годовая доходность ~ (1.05)^2 - 1 = 10.25%
        assertThat(calc.ytmPct()).isBetween(new BigDecimal("10.20"), new BigDecimal("10.35"));
        assertThat(calc.remainingCouponsCount()).isEqualTo(6);
        assertThat(calc.yearsToMaturity()).isEqualByComparingTo("3.00");
        assertThat(calc.nextCouponDate()).isEqualTo(LocalDate.of(2027, 7, 2));
        assertThat(calc.amortized()).isFalse();
    }

    @Test
    void calculatesAccruedInterestMidCouponPeriodAndHigherYtmAtDiscount() {
        BondSpec bond = sampleOfz(false);
        // ровно половина купонного периода: 91 день после 2027-01-01 -> 2027-04-02
        LocalDate asOf = LocalDate.of(2027, 4, 2);

        BondCalculator.BondCalculation discount = calculator.calculate(bond, new BigDecimal("85.50"), asOf);
        BondCalculator.BondCalculation premium = calculator.calculate(bond, new BigDecimal("110.00"), asOf);

        assertThat(discount.cleanPriceRub()).isEqualByComparingTo("855.00");
        assertThat(discount.accruedInterestRub()).isEqualByComparingTo("25.00");
        assertThat(discount.dirtyPriceRub()).isEqualByComparingTo("880.00");
        assertThat(discount.currentYieldPct()).isEqualByComparingTo("11.70");
        assertThat(discount.ytmPct()).isGreaterThan(new BigDecimal("16.00"));
        assertThat(premium.ytmPct()).isLessThan(new BigDecimal("7.00"));
        assertThat(discount.ytmPct()).isGreaterThan(premium.ytmPct());
    }

    @Test
    void amortizedBondAtDiscountHasHigherYtmThanBulletDueToEarlierPrincipalReturn() {
        LocalDate asOf = LocalDate.of(2027, 1, 1);
        BondCalculator.BondCalculation bullet = calculator.calculate(sampleOfz(false), new BigDecimal("85.00"), asOf);
        BondCalculator.BondCalculation amort = calculator.calculate(sampleOfz(true), new BigDecimal("85.00"), asOf);

        assertThat(amort.amortized()).isTrue();
        // При дисконте более ранний возврат номинала увеличивает эффективную доходность YTM
        assertThat(amort.ytmPct()).isGreaterThan(bullet.ytmPct());
    }

    @Test
    void fastNewtonYtmMatchesExactBisectionAcrossDiverseBondsAndPrices() {
        LocalDate asOf = LocalDate.of(2027, 3, 1);
        BondSpec longOfz = new BondSpec(
                "SU26238RMFS4",
                "ОФЗ 26238",
                "Минфин РФ",
                "OFZ",
                new BigDecimal("1000.00"),
                new BigDecimal("7.1000"),
                new BigDecimal("35.4000"),
                182,
                LocalDate.of(2027, 6, 2),
                LocalDate.of(2041, 5, 15),
                null,
                new BigDecimal("0.00"),
                new BigDecimal("14.50"),
                new BigDecimal("7.50"),
                false,
                new BigDecimal("58.00"),
                "RUB");
        BondSpec shortBond = new BondSpec(
                "RU000A107HG1",
                "Короткая корпоративная",
                "Эмитент",
                "CORPORATE",
                new BigDecimal("1000.00"),
                new BigDecimal("16.0000"),
                new BigDecimal("40.0000"),
                91,
                LocalDate.of(2027, 5, 1),
                LocalDate.of(2027, 11, 1),
                null,
                new BigDecimal("0.00"),
                new BigDecimal("16.50"),
                new BigDecimal("0.60"),
                false,
                new BigDecimal("99.00"),
                "RUB");

        List<BondSpec> bonds = List.of(sampleOfz(false), sampleOfz(true), longOfz, shortBond);
        List<BigDecimal> prices = List.of(
                new BigDecimal("35.00"),
                new BigDecimal("58.25"),
                new BigDecimal("80.50"),
                new BigDecimal("100.00"),
                new BigDecimal("115.00"),
                new BigDecimal("145.00"));

        for (BondSpec bond : bonds) {
            for (BigDecimal price : prices) {
                BondCalculator.BondCalculation actual = calculator.calculate(bond, price, asOf);
                BigDecimal expectedYtm = referenceBisectionYtmPct(bond, actual.dirtyPriceRub(), asOf);
                assertThat(actual.ytmPct())
                        .as("ticker=%s amortized=%s price=%s", bond.ticker(), bond.amortized(), price)
                        .isEqualByComparingTo(expectedYtm);
            }
        }

        long startNs = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            calculator.calculate(longOfz, new BigDecimal("58.25"), asOf);
        }
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
        assertThat(elapsedMs)
                .as("100 расчётов длинной ОФЗ должны занимать < 1000 мс")
                .isLessThan(1_000L);
    }

    private static BigDecimal referenceBisectionYtmPct(BondSpec bond, BigDecimal dirtyPriceRub, LocalDate asOfDate) {
        MathContext mc = MathContext.DECIMAL128;
        LocalDate firstCoupon = bond.nextCouponDate();
        while (!firstCoupon.isAfter(asOfDate) && firstCoupon.isBefore(bond.maturityDate())) {
            LocalDate next = firstCoupon.plusDays(bond.couponPeriodDays());
            firstCoupon = next.isAfter(bond.maturityDate()) ? bond.maturityDate() : next;
        }
        while (firstCoupon.minusDays(bond.couponPeriodDays()).isAfter(asOfDate)) {
            firstCoupon = firstCoupon.minusDays(bond.couponPeriodDays());
        }
        List<LocalDate> dates = new ArrayList<>();
        LocalDate cur = firstCoupon;
        int minGap = Math.max(1, bond.couponPeriodDays() / 2);
        while (cur.isBefore(bond.maturityDate()) && ChronoUnit.DAYS.between(cur, bond.maturityDate()) >= minGap) {
            dates.add(cur);
            cur = cur.plusDays(bond.couponPeriodDays());
        }
        dates.add(bond.maturityDate());

        int n = dates.size();
        List<BigDecimal> years = new ArrayList<>(n);
        List<BigDecimal> amounts = new ArrayList<>(n);
        BigDecimal daysInYear = new BigDecimal("365");
        for (int i = 0; i < n; i++) {
            years.add(BigDecimal.valueOf(ChronoUnit.DAYS.between(asOfDate, dates.get(i)))
                    .divide(daysInYear, mc));
            if (!bond.amortized() || n == 1) {
                amounts.add(i == n - 1 ? bond.couponValueRub().add(bond.faceValueRub(), mc) : bond.couponValueRub());
            } else {
                BigDecimal nBd = BigDecimal.valueOf(n);
                BigDecimal principalStep = bond.faceValueRub().divide(nBd, mc);
                BigDecimal remaining = BigDecimal.valueOf(n - i).divide(nBd, mc);
                amounts.add(bond.couponValueRub().multiply(remaining, mc).add(principalStep, mc));
            }
        }

        BigDecimal lo = new BigDecimal("-0.90");
        BigDecimal hi = new BigDecimal("5.00");
        BigDecimal half = new BigDecimal("0.5");
        for (int iter = 0; iter < 64; iter++) {
            BigDecimal mid = lo.add(hi, mc).multiply(half, mc);
            BigDecimal ln1y = BondCalculator.ln(BigDecimal.ONE.add(mid, mc));
            BigDecimal pv = BigDecimal.ZERO;
            for (int i = 0; i < n; i++) {
                pv = pv.add(
                        amounts.get(i)
                                .multiply(
                                        BondCalculator.exp(
                                                ln1y.multiply(years.get(i), mc).negate()),
                                        mc),
                        mc);
            }
            int cmp = pv.compareTo(dirtyPriceRub);
            if (cmp > 0) {
                lo = mid;
            } else if (cmp < 0) {
                hi = mid;
            } else {
                lo = mid;
                hi = mid;
                break;
            }
        }
        return lo.add(hi, mc)
                .multiply(half, mc)
                .multiply(new BigDecimal("100"), mc)
                .setScale(2, RoundingMode.HALF_UP);
    }

    @Test
    void maturedBondReturnsZeroNkdZeroRemainingCouponsAndZeroYtm() {
        BondSpec bond = sampleOfz(false);
        BondCalculator.BondCalculation calc =
                calculator.calculate(bond, new BigDecimal("100.00"), LocalDate.of(2030, 1, 1));

        assertThat(calc.remainingCouponsCount()).isZero();
        assertThat(calc.accruedInterestRub()).isEqualByComparingTo("0.00");
        assertThat(calc.yearsToMaturity()).isEqualByComparingTo("0.00");
        assertThat(calc.ytmPct()).isEqualByComparingTo("0.00");
    }

    @Test
    void controllerValidatesParamsAndHandlesNotFound() {
        InstrumentsCatalog catalog = mock(InstrumentsCatalog.class);
        BondSpec bond = sampleOfz(false);
        when(catalog.findBond("SU26248RMFS3")).thenReturn(Optional.of(bond));
        when(catalog.findBond("UNKNOWN")).thenReturn(Optional.empty());
        when(catalog.listBonds()).thenReturn(List.of(bond));

        InstrumentsController controller = new InstrumentsController(
                catalog,
                calculator,
                new FuturesCalculator(),
                new OptionGreeksCalculator(),
                new DividendCalculator(),
                new TaxCalculator(),
                new DcaCalculator());

        assertThat(controller.listBonds()).hasSize(1);
        BondCalculator.BondCalculationResponse resp = controller.calculateBond(" su26248rmfs3 ", "85.50", "2027-04-02");
        assertThat(resp.ticker()).isEqualTo("SU26248RMFS3");
        assertThat(resp.cleanPriceRub()).isEqualTo("855.00");
        assertThat(resp.accruedInterestRub()).isEqualTo("25.00");
        assertThat(resp.dirtyPriceRub()).isEqualTo("880.00");

        assertThatThrownBy(() -> controller.calculateBond("UNKNOWN", "100", "2027-04-02"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> controller.calculateBond("SU26248RMFS3", "-5", "2027-04-02"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> controller.calculateBond("SU26248RMFS3", "not-a-number", "2027-04-02"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> controller.calculateBond("SU26248RMFS3", "100", "bad-date"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> controller.calculateBond("   ", "100", null))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void migrationV23SeedsRealMoexBondsAndEtfs() throws IOException {
        String sql = Files.readString(
                Path.of("src/main/resources/db/migration/V23__asset_classes_bonds_etfs.sql"), StandardCharsets.UTF_8);

        Pattern bondPattern =
                Pattern.compile("\\('((?:SU|RU)[A-Z0-9]+)',\\s*'[^']+',\\s*'[^']+',\\s*'(OFZ|CORPORATE)'");
        Matcher bm = bondPattern.matcher(sql);
        Set<String> bonds = new HashSet<>();
        while (bm.find()) {
            bonds.add(bm.group(1));
        }
        assertThat(bonds).hasSizeGreaterThanOrEqualTo(15);
        assertThat(bonds)
                .contains(
                        "SU26238RMFS4",
                        "SU26243RMFS4",
                        "SU26244RMFS2",
                        "SU26248RMFS3",
                        "SU29014RMFS6",
                        "RU000A107HG1",
                        "RU000A106T36");

        Pattern etfPattern = Pattern.compile(
                "\\('([A-Z0-9_]+)',\\s*'[^']+',\\s*'(ETF|CURRENCY|METAL)',\\s*'(LIQUIDITY|EQUITY_INDEX|BOND_INDEX|GOLD|CURRENCY)'");
        Matcher em = etfPattern.matcher(sql);
        Set<String> etfs = new HashSet<>();
        while (em.find()) {
            etfs.add(em.group(1));
        }
        assertThat(etfs).hasSizeGreaterThanOrEqualTo(12);
        assertThat(etfs).contains("LQDT", "SBMM", "AKMM", "TMON", "TMOS", "SBMX", "TGLD", "GLDRUB_TOM", "CNYRUB_TOM");
    }
}
