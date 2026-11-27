package ru.tinvest.trainer.marketdata;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Серверный калькулятор доходности портфеля при регулярных пополнениях и снятиях (DCA, backend#94, INV-4, INV-6):
 * совокупные вложения, чистая прибыль, простая доходность, годовая денежно-взвешенная доходность (XIRR, actual/365)
 * и взвешенная по времени доходность (TWR — цепная при наличии промежуточных оценок портфеля или Modified Dietz).
 */
@Service
public class DcaCalculator {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal ZERO_2 = new BigDecimal("0.00");
    private static final BigDecimal MINUS_HUNDRED = new BigDecimal("-100.00");
    private static final double DAYS_PER_YEAR = 365.0;
    private static final double XIRR_TOLERANCE = 1e-10;
    private static final int MAX_NEWTON_ITERATIONS = 100;
    private static final int MAX_BISECTION_ITERATIONS = 200;

    public record Cashflow(
            LocalDate date, BigDecimal amountRub, @Nullable BigDecimal portfolioValueBeforeRub) {}

    public DcaCalculation calculate(List<Cashflow> cashflows, BigDecimal finalValueRub, LocalDate finalDate) {
        if (cashflows == null || cashflows.isEmpty() || cashflows.size() > 120) {
            throw new IllegalArgumentException("cashflows must contain 1..120 items");
        }
        if (finalValueRub == null || finalValueRub.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("finalValueRub must be >= 0");
        }
        if (finalDate == null) {
            throw new IllegalArgumentException("finalDate must not be null");
        }

        List<Cashflow> sorted =
                cashflows.stream().sorted(Comparator.comparing(Cashflow::date)).toList();

        for (Cashflow cf : sorted) {
            if (cf.date() == null || cf.amountRub() == null) {
                throw new IllegalArgumentException("Cashflow date and amountRub must not be null");
            }
            if (cf.amountRub().compareTo(BigDecimal.ZERO) == 0) {
                throw new IllegalArgumentException("Cashflow amountRub must be non-zero");
            }
            if (cf.date().isAfter(finalDate)) {
                throw new IllegalArgumentException("Cashflow date must not be after finalDate");
            }
            if (cf.portfolioValueBeforeRub() != null
                    && cf.portfolioValueBeforeRub().compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("portfolioValueBeforeRub must be >= 0");
            }
        }

        BigDecimal totalContributed = BigDecimal.ZERO;
        BigDecimal totalWithdrawn = BigDecimal.ZERO;
        for (Cashflow cf : sorted) {
            if (cf.amountRub().compareTo(BigDecimal.ZERO) > 0) {
                totalContributed = totalContributed.add(cf.amountRub());
            } else {
                totalWithdrawn = totalWithdrawn.add(cf.amountRub().abs());
            }
        }

        BigDecimal totalContributedRub = totalContributed.setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalWithdrawnRub = totalWithdrawn.setScale(2, RoundingMode.HALF_UP);
        BigDecimal netInvestedRub =
                totalContributedRub.subtract(totalWithdrawnRub).setScale(2, RoundingMode.HALF_UP);
        BigDecimal finalVal = finalValueRub.setScale(2, RoundingMode.HALF_UP);
        BigDecimal netProfitRub = finalVal.subtract(netInvestedRub).setScale(2, RoundingMode.HALF_UP);

        BigDecimal simpleReturnPct = totalContributedRub.compareTo(BigDecimal.ZERO) > 0
                ? netProfitRub.multiply(HUNDRED, MC).divide(totalContributedRub, 2, RoundingMode.HALF_UP)
                : ZERO_2;

        LocalDate firstDate = sorted.getFirst().date();
        int holdingDays = (int) Math.max(0L, ChronoUnit.DAYS.between(firstDate, finalDate));

        BigDecimal xirrPct = computeXirrPct(sorted, finalVal, firstDate, finalDate, holdingDays, simpleReturnPct);
        BigDecimal twrPct = computeTwrPct(sorted, finalVal, finalDate, holdingDays, netProfitRub, simpleReturnPct);

        return new DcaCalculation(
                totalContributedRub,
                totalWithdrawnRub,
                netInvestedRub,
                finalVal,
                netProfitRub,
                simpleReturnPct,
                xirrPct,
                twrPct,
                holdingDays,
                sorted.size());
    }

    private static BigDecimal computeXirrPct(
            List<Cashflow> sorted,
            BigDecimal finalVal,
            LocalDate firstDate,
            LocalDate finalDate,
            int holdingDays,
            BigDecimal simpleReturnPct) {
        if (holdingDays == 0) {
            return simpleReturnPct;
        }

        int n = sorted.size();
        double[] amounts = new double[n + 1];
        double[] years = new double[n + 1];
        boolean hasPositive = false;
        boolean hasNegative = false;

        for (int i = 0; i < n; i++) {
            Cashflow cf = sorted.get(i);
            // Для инвестора пополнение (> 0) — отток (-amount), снятие (< 0) — приток (+|amount|).
            double amt = -cf.amountRub().doubleValue();
            amounts[i] = amt;
            years[i] = ChronoUnit.DAYS.between(firstDate, cf.date()) / DAYS_PER_YEAR;
            if (amt > 0.0) {
                hasPositive = true;
            } else if (amt < 0.0) {
                hasNegative = true;
            }
        }
        double terminalAmt = finalVal.doubleValue();
        amounts[n] = terminalAmt;
        years[n] = ChronoUnit.DAYS.between(firstDate, finalDate) / DAYS_PER_YEAR;
        if (terminalAmt > 0.0) {
            hasPositive = true;
        }

        if (!hasPositive && hasNegative) {
            return MINUS_HUNDRED;
        }
        if (!hasPositive || !hasNegative) {
            return ZERO_2;
        }

        double rate = solveXirr(amounts, years);
        if (!Double.isFinite(rate)) {
            return simpleReturnPct;
        }
        return BigDecimal.valueOf(rate * 100.0).setScale(2, RoundingMode.HALF_UP);
    }

    private static double solveXirr(double[] amounts, double[] years) {
        double r = 0.10;
        for (int i = 0; i < MAX_NEWTON_ITERATIONS; i++) {
            double f = npv(amounts, years, r);
            if (Math.abs(f) < XIRR_TOLERANCE) {
                return r;
            }
            double df = dNpv(amounts, years, r);
            if (!Double.isFinite(df) || Math.abs(df) < 1e-14) {
                break;
            }
            double next = r - f / df;
            if (!Double.isFinite(next) || next <= -0.999999 || next > 1000.0) {
                break;
            }
            if (Math.abs(next - r) < XIRR_TOLERANCE) {
                return next;
            }
            r = next;
        }

        double low = -0.9999;
        double high = 10.0;
        double fLow = npv(amounts, years, low);
        double fHigh = npv(amounts, years, high);
        int expand = 0;
        while (fLow * fHigh > 0.0 && expand < 12) {
            high *= 2.0;
            fHigh = npv(amounts, years, high);
            expand++;
        }
        if (fLow * fHigh > 0.0) {
            return Double.NaN;
        }

        for (int i = 0; i < MAX_BISECTION_ITERATIONS; i++) {
            double mid = 0.5 * (low + high);
            double fMid = npv(amounts, years, mid);
            if (Math.abs(fMid) < XIRR_TOLERANCE || (high - low) < XIRR_TOLERANCE) {
                return mid;
            }
            if (fLow * fMid <= 0.0) {
                high = mid;
            } else {
                low = mid;
                fLow = fMid;
            }
        }
        return 0.5 * (low + high);
    }

    private static double npv(double[] amounts, double[] years, double rate) {
        double base = 1.0 + rate;
        double sum = 0.0;
        for (int i = 0; i < amounts.length; i++) {
            sum += amounts[i] / Math.pow(base, years[i]);
        }
        return sum;
    }

    private static double dNpv(double[] amounts, double[] years, double rate) {
        double base = 1.0 + rate;
        double sum = 0.0;
        for (int i = 0; i < amounts.length; i++) {
            if (years[i] != 0.0) {
                sum -= years[i] * amounts[i] / Math.pow(base, years[i] + 1.0);
            }
        }
        return sum;
    }

    private static BigDecimal computeTwrPct(
            List<Cashflow> sorted,
            BigDecimal finalVal,
            LocalDate finalDate,
            int holdingDays,
            BigDecimal netProfitRub,
            BigDecimal simpleReturnPct) {
        boolean hasAllIntermediateValuations = true;
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).portfolioValueBeforeRub() == null) {
                hasAllIntermediateValuations = false;
                break;
            }
        }

        if (hasAllIntermediateValuations) {
            BigDecimal factor = BigDecimal.ONE;
            BigDecimal initialBefore = sorted.getFirst().portfolioValueBeforeRub();
            BigDecimal startVal = (initialBefore != null ? initialBefore : BigDecimal.ZERO)
                    .add(sorted.getFirst().amountRub());
            for (int i = 1; i < sorted.size(); i++) {
                BigDecimal valBefore = sorted.get(i).portfolioValueBeforeRub();
                if (valBefore == null) {
                    break;
                }
                if (startVal.compareTo(BigDecimal.ZERO) > 0) {
                    factor = factor.multiply(valBefore.divide(startVal, MC), MC);
                }
                startVal = valBefore.add(sorted.get(i).amountRub());
            }
            if (startVal.compareTo(BigDecimal.ZERO) > 0) {
                factor = factor.multiply(finalVal.divide(startVal, MC), MC);
            }
            return factor.subtract(BigDecimal.ONE, MC).multiply(HUNDRED, MC).setScale(2, RoundingMode.HALF_UP);
        }

        if (holdingDays == 0) {
            return simpleReturnPct;
        }

        BigDecimal totalDaysBd = BigDecimal.valueOf(holdingDays);
        BigDecimal weightedCapital = BigDecimal.ZERO;
        for (Cashflow cf : sorted) {
            long daysRemaining = ChronoUnit.DAYS.between(cf.date(), finalDate);
            BigDecimal weight = BigDecimal.valueOf(daysRemaining).divide(totalDaysBd, MC);
            weightedCapital = weightedCapital.add(cf.amountRub().multiply(weight, MC), MC);
        }
        if (weightedCapital.compareTo(BigDecimal.ZERO) <= 0) {
            return simpleReturnPct;
        }
        return netProfitRub.multiply(HUNDRED, MC).divide(weightedCapital, 2, RoundingMode.HALF_UP);
    }

    public record DcaCalculation(
            BigDecimal totalContributedRub,
            BigDecimal totalWithdrawnRub,
            BigDecimal netInvestedRub,
            BigDecimal finalValueRub,
            BigDecimal netProfitRub,
            BigDecimal simpleReturnPct,
            BigDecimal xirrPct,
            BigDecimal twrPct,
            int holdingDays,
            int cashflowsCount) {

        /** Представление по схеме {@code DcaCalculationResponse} контракта {@code api/openapi.yaml} (INV-6). */
        public DcaCalculationResponse toResponse() {
            return new DcaCalculationResponse(
                    totalContributedRub.toPlainString(),
                    totalWithdrawnRub.toPlainString(),
                    netInvestedRub.toPlainString(),
                    finalValueRub.toPlainString(),
                    netProfitRub.toPlainString(),
                    simpleReturnPct.toPlainString(),
                    xirrPct.toPlainString(),
                    twrPct.toPlainString(),
                    holdingDays,
                    cashflowsCount);
        }
    }

    public record DcaCalculationResponse(
            String totalContributedRub,
            String totalWithdrawnRub,
            String netInvestedRub,
            String finalValueRub,
            String netProfitRub,
            String simpleReturnPct,
            String xirrPct,
            String twrPct,
            int holdingDays,
            int cashflowsCount) {}
}
