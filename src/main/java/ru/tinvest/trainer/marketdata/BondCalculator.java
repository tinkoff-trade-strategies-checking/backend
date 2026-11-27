package ru.tinvest.trainer.marketdata;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Точный расчёт параметров облигации на {@link BigDecimal} (INV-4, INV-6): чистая и грязная цена, НКД, текущая
 * доходность и эффективная доходность к погашению (YTM) методом бисекции на оставшихся денежных потоках с учётом
 * амортизации номинала.
 */
@Service
public class BondCalculator {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal DAYS_IN_YEAR = new BigDecimal("365");
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final BigDecimal YTM_LOW = new BigDecimal("-0.90");
    private static final BigDecimal YTM_HIGH = new BigDecimal("5.00");
    private static final int BISECTION_ITERATIONS = 64;
    private static final BigDecimal SERIES_EPSILON = new BigDecimal("1E-22");

    public BondCalculation calculate(BondSpec bond, BigDecimal pricePct, LocalDate asOfDate) {
        if (pricePct == null || pricePct.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("pricePct must be positive");
        }
        if (bond.couponPeriodDays() <= 0) {
            throw new IllegalArgumentException("couponPeriodDays must be positive");
        }

        BigDecimal cleanPriceRub = bond.faceValueRub().multiply(pricePct, MC).divide(HUNDRED, 2, RoundingMode.HALF_UP);

        BigDecimal annualCoupon =
                bond.faceValueRub().multiply(bond.couponRatePct(), MC).divide(HUNDRED, MC);
        BigDecimal currentYieldPct = cleanPriceRub.compareTo(BigDecimal.ZERO) > 0
                ? annualCoupon.multiply(HUNDRED, MC).divide(cleanPriceRub, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        if (!asOfDate.isBefore(bond.maturityDate())) {
            BigDecimal zero = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            return new BondCalculation(
                    bond.ticker(),
                    pricePct.setScale(2, RoundingMode.HALF_UP),
                    asOfDate,
                    cleanPriceRub,
                    zero,
                    cleanPriceRub,
                    currentYieldPct,
                    zero,
                    0,
                    zero,
                    bond.maturityDate(),
                    bond.couponValueRub().setScale(2, RoundingMode.HALF_UP),
                    bond.amortized());
        }

        LocalDate firstCoupon =
                alignNextCouponDate(bond.nextCouponDate(), bond.maturityDate(), bond.couponPeriodDays(), asOfDate);
        LocalDate prevCoupon = firstCoupon.minusDays(bond.couponPeriodDays());
        long accruedDays =
                Math.max(0L, Math.min(bond.couponPeriodDays(), ChronoUnit.DAYS.between(prevCoupon, asOfDate)));

        BigDecimal accruedInterestRub = bond.couponValueRub()
                .multiply(BigDecimal.valueOf(accruedDays), MC)
                .divide(BigDecimal.valueOf(bond.couponPeriodDays()), 2, RoundingMode.HALF_UP);

        BigDecimal dirtyPriceRub = cleanPriceRub.add(accruedInterestRub).setScale(2, RoundingMode.HALF_UP);

        List<LocalDate> couponDates =
                buildRemainingCouponDates(firstCoupon, bond.maturityDate(), bond.couponPeriodDays());
        int remainingCouponsCount = couponDates.size();

        long daysToMaturity = ChronoUnit.DAYS.between(asOfDate, bond.maturityDate());
        BigDecimal yearsToMaturity = BigDecimal.valueOf(daysToMaturity).divide(DAYS_IN_YEAR, 2, RoundingMode.HALF_UP);

        List<Cashflow> cashflows = buildCashflows(bond, couponDates, asOfDate);
        BigDecimal ytmDecimal = solveYtm(cashflows, dirtyPriceRub);
        BigDecimal ytmPct = ytmDecimal.multiply(HUNDRED, MC).setScale(2, RoundingMode.HALF_UP);

        return new BondCalculation(
                bond.ticker(),
                pricePct.setScale(2, RoundingMode.HALF_UP),
                asOfDate,
                cleanPriceRub,
                accruedInterestRub,
                dirtyPriceRub,
                currentYieldPct,
                ytmPct,
                remainingCouponsCount,
                yearsToMaturity,
                firstCoupon,
                bond.couponValueRub().setScale(2, RoundingMode.HALF_UP),
                bond.amortized());
    }

    private static LocalDate alignNextCouponDate(
            LocalDate seedNextCoupon, LocalDate maturityDate, int periodDays, LocalDate asOfDate) {
        LocalDate current = seedNextCoupon;
        while (!current.isAfter(asOfDate) && current.isBefore(maturityDate)) {
            LocalDate next = current.plusDays(periodDays);
            current = next.isAfter(maturityDate) ? maturityDate : next;
        }
        while (current.minusDays(periodDays).isAfter(asOfDate)) {
            current = current.minusDays(periodDays);
        }
        return current.isAfter(maturityDate) ? maturityDate : current;
    }

    private static List<LocalDate> buildRemainingCouponDates(
            LocalDate firstCoupon, LocalDate maturityDate, int periodDays) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate current = firstCoupon;
        int minFinalGap = Math.max(1, periodDays / 2);
        while (current.isBefore(maturityDate) && ChronoUnit.DAYS.between(current, maturityDate) >= minFinalGap) {
            dates.add(current);
            LocalDate next = current.plusDays(periodDays);
            if (!next.isAfter(current)) {
                break;
            }
            current = next;
        }
        dates.add(maturityDate);
        return dates;
    }

    private static List<Cashflow> buildCashflows(BondSpec bond, List<LocalDate> couponDates, LocalDate asOfDate) {
        int n = couponDates.size();
        List<Cashflow> cashflows = new ArrayList<>(n);
        if (!bond.amortized() || n == 1) {
            for (int i = 0; i < n; i++) {
                LocalDate date = couponDates.get(i);
                BigDecimal tYears = BigDecimal.valueOf(ChronoUnit.DAYS.between(asOfDate, date))
                        .divide(DAYS_IN_YEAR, MC);
                BigDecimal amount =
                        i == n - 1 ? bond.couponValueRub().add(bond.faceValueRub(), MC) : bond.couponValueRub();
                cashflows.add(new Cashflow(tYears, amount));
            }
        } else {
            BigDecimal nBd = BigDecimal.valueOf(n);
            BigDecimal principalStep = bond.faceValueRub().divide(nBd, MC);
            for (int i = 0; i < n; i++) {
                LocalDate date = couponDates.get(i);
                BigDecimal tYears = BigDecimal.valueOf(ChronoUnit.DAYS.between(asOfDate, date))
                        .divide(DAYS_IN_YEAR, MC);
                BigDecimal remainingFactor = BigDecimal.valueOf(n - i).divide(nBd, MC);
                BigDecimal couponForStep = bond.couponValueRub().multiply(remainingFactor, MC);
                cashflows.add(new Cashflow(tYears, couponForStep.add(principalStep, MC)));
            }
        }
        return cashflows;
    }

    private static BigDecimal solveYtm(List<Cashflow> cashflows, BigDecimal targetDirtyPrice) {
        BigDecimal low = YTM_LOW;
        BigDecimal high = YTM_HIGH;
        for (int i = 0; i < BISECTION_ITERATIONS; i++) {
            BigDecimal mid = low.add(high, MC).multiply(HALF, MC);
            BigDecimal pv = presentValue(cashflows, mid);
            int cmp = pv.compareTo(targetDirtyPrice);
            if (cmp > 0) {
                low = mid;
            } else if (cmp < 0) {
                high = mid;
            } else {
                return mid;
            }
        }
        return low.add(high, MC).multiply(HALF, MC);
    }

    private static BigDecimal presentValue(List<Cashflow> cashflows, BigDecimal ytm) {
        BigDecimal onePlusY = BigDecimal.ONE.add(ytm, MC);
        BigDecimal lnOnePlusY = ln(onePlusY);
        BigDecimal sum = BigDecimal.ZERO;
        for (Cashflow cf : cashflows) {
            // (1 + y)^(-t) = exp(-t * ln(1 + y))
            BigDecimal discount = exp(lnOnePlusY.multiply(cf.years(), MC).negate());
            sum = sum.add(cf.amount().multiply(discount, MC), MC);
        }
        return sum;
    }

    /** Натуральный логарифм для x > 0 полностью на BigDecimal через редукцию аргумента и ряд atanh. */
    static BigDecimal ln(BigDecimal x) {
        if (x.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("ln argument must be positive");
        }
        int reductions = 0;
        BigDecimal v = x;
        while (v.compareTo(TWO) > 0) {
            v = v.sqrt(MC);
            reductions++;
        }
        while (v.compareTo(HALF) < 0) {
            v = v.sqrt(MC);
            reductions++;
        }
        BigDecimal u = v.subtract(BigDecimal.ONE, MC).divide(v.add(BigDecimal.ONE, MC), MC);
        BigDecimal u2 = u.multiply(u, MC);
        BigDecimal term = u;
        BigDecimal sum = u;
        for (int k = 3; k < 200; k += 2) {
            term = term.multiply(u2, MC);
            BigDecimal delta = term.divide(BigDecimal.valueOf(k), MC);
            sum = sum.add(delta, MC);
            if (delta.abs().compareTo(SERIES_EPSILON) < 0) {
                break;
            }
        }
        BigDecimal result = sum.multiply(TWO, MC);
        for (int i = 0; i < reductions; i++) {
            result = result.multiply(TWO, MC);
        }
        return result;
    }

    /** Экспонента полностью на BigDecimal через масштабирование z / 2^m и ряд Тейлора. */
    static BigDecimal exp(BigDecimal z) {
        int halvings = 0;
        BigDecimal scaled = z;
        while (scaled.abs().compareTo(HALF) > 0) {
            scaled = scaled.multiply(HALF, MC);
            halvings++;
        }
        BigDecimal sum = BigDecimal.ONE;
        BigDecimal term = BigDecimal.ONE;
        for (int k = 1; k < 120; k++) {
            term = term.multiply(scaled, MC).divide(BigDecimal.valueOf(k), MC);
            sum = sum.add(term, MC);
            if (term.abs().compareTo(SERIES_EPSILON) < 0) {
                break;
            }
        }
        for (int i = 0; i < halvings; i++) {
            sum = sum.multiply(sum, MC);
        }
        return sum;
    }

    private record Cashflow(BigDecimal years, BigDecimal amount) {}

    public record BondCalculation(
            String ticker,
            BigDecimal pricePct,
            LocalDate asOfDate,
            BigDecimal cleanPriceRub,
            BigDecimal accruedInterestRub,
            BigDecimal dirtyPriceRub,
            BigDecimal currentYieldPct,
            BigDecimal ytmPct,
            int remainingCouponsCount,
            BigDecimal yearsToMaturity,
            LocalDate nextCouponDate,
            BigDecimal couponValueRub,
            boolean amortized) {

        /** Представление по схеме {@code BondCalculationResponse} контракта {@code api/openapi.yaml} (INV-6). */
        public BondCalculationResponse toResponse() {
            return new BondCalculationResponse(
                    ticker,
                    pricePct.toPlainString(),
                    asOfDate,
                    cleanPriceRub.toPlainString(),
                    accruedInterestRub.toPlainString(),
                    dirtyPriceRub.toPlainString(),
                    currentYieldPct.toPlainString(),
                    ytmPct.toPlainString(),
                    remainingCouponsCount,
                    yearsToMaturity.toPlainString(),
                    nextCouponDate,
                    couponValueRub.toPlainString(),
                    amortized);
        }
    }

    public record BondCalculationResponse(
            String ticker,
            String pricePct,
            LocalDate asOfDate,
            String cleanPriceRub,
            String accruedInterestRub,
            String dirtyPriceRub,
            String currentYieldPct,
            String ytmPct,
            int remainingCouponsCount,
            String yearsToMaturity,
            LocalDate nextCouponDate,
            String couponValueRub,
            boolean amortized) {}
}
