package ru.tinvest.trainer.marketdata;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;

/**
 * Расчёт теоретической цены и греков опциона по модели Блэка — Шоулза — Мертона (Black-Scholes-Merton, backend#91).
 * Все денежные, ценовые и безразмерные результаты возвращаются как {@link BigDecimal} (INV-6).
 */
@Service
public class OptionGreeksCalculator {

    private static final double DAYS_IN_YEAR = 365.0;
    private static final double INV_SQRT_2PI = 1.0 / Math.sqrt(2.0 * Math.PI);
    private static final double SQRT_2 = Math.sqrt(2.0);

    public OptionGreeksCalculation calculate(
            OptionSpec option,
            BigDecimal spot,
            BigDecimal impliedVolPct,
            BigDecimal riskFreeRatePct,
            LocalDate asOfDate) {
        if (spot == null || spot.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("spot must be positive");
        }
        if (impliedVolPct == null || impliedVolPct.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("impliedVolPct must be positive");
        }
        if (riskFreeRatePct == null || riskFreeRatePct.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("riskFreeRatePct must be non-negative");
        }
        if (option.strike().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("strike must be positive");
        }
        boolean isCall = "CALL".equals(option.optionType());
        if (!isCall && !"PUT".equals(option.optionType())) {
            throw new IllegalArgumentException("optionType must be CALL or PUT");
        }

        long days = ChronoUnit.DAYS.between(asOfDate, option.expirationDate());
        int daysToExpiration = (int) Math.max(0L, days);
        boolean expired = days <= 0;

        BigDecimal intrinsic = isCall
                ? spot.subtract(option.strike()).max(BigDecimal.ZERO)
                : option.strike().subtract(spot).max(BigDecimal.ZERO);
        BigDecimal intrinsicScaled = intrinsic.setScale(2, RoundingMode.HALF_UP);

        if (expired) {
            BigDecimal expiredDelta;
            int cmp = spot.compareTo(option.strike());
            if (isCall) {
                expiredDelta = cmp > 0
                        ? new BigDecimal("1.0000")
                        : (cmp == 0 ? new BigDecimal("0.5000") : new BigDecimal("0.0000"));
            } else {
                expiredDelta = cmp < 0
                        ? new BigDecimal("-1.0000")
                        : (cmp == 0 ? new BigDecimal("-0.5000") : new BigDecimal("0.0000"));
            }
            return new OptionGreeksCalculation(
                    option.ticker(),
                    option.optionType(),
                    spot,
                    option.strike(),
                    impliedVolPct.setScale(2, RoundingMode.HALF_UP),
                    riskFreeRatePct.setScale(2, RoundingMode.HALF_UP),
                    asOfDate,
                    option.expirationDate(),
                    0,
                    true,
                    intrinsicScaled,
                    intrinsicScaled,
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    expiredDelta,
                    BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP),
                    BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
                    BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
                    BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
        }

        double s = spot.doubleValue();
        double k = option.strike().doubleValue();
        double sigma = impliedVolPct.doubleValue() / 100.0;
        double r = riskFreeRatePct.doubleValue() / 100.0;
        double t = daysToExpiration / DAYS_IN_YEAR;
        double sqrtT = Math.sqrt(t);

        double d1 = (Math.log(s / k) + (r + 0.5 * sigma * sigma) * t) / (sigma * sqrtT);
        double d2 = d1 - sigma * sqrtT;
        double disc = Math.exp(-r * t);
        double pdfD1 = normalPdf(d1);

        double premiumVal;
        double deltaVal;
        double thetaAnnual;
        double rhoVal;
        if (isCall) {
            double cdfD1 = normalCdf(d1);
            double cdfD2 = normalCdf(d2);
            premiumVal = Math.max(0.0, s * cdfD1 - k * disc * cdfD2);
            deltaVal = cdfD1;
            thetaAnnual = -(s * pdfD1 * sigma) / (2.0 * sqrtT) - r * k * disc * cdfD2;
            rhoVal = (k * t * disc * cdfD2) / 100.0;
        } else {
            double cdfNegD1 = normalCdf(-d1);
            double cdfNegD2 = normalCdf(-d2);
            premiumVal = Math.max(0.0, k * disc * cdfNegD2 - s * cdfNegD1);
            deltaVal = normalCdf(d1) - 1.0;
            thetaAnnual = -(s * pdfD1 * sigma) / (2.0 * sqrtT) + r * k * disc * cdfNegD2;
            rhoVal = -(k * t * disc * cdfNegD2) / 100.0;
        }

        double gammaVal = pdfD1 / (s * sigma * sqrtT);
        double thetaDaily = thetaAnnual / DAYS_IN_YEAR;
        double vegaVal = (s * pdfD1 * sqrtT) / 100.0;

        BigDecimal premium = toScaled(premiumVal, 2);
        BigDecimal timeValue = premium.subtract(intrinsicScaled).setScale(2, RoundingMode.HALF_UP);

        return new OptionGreeksCalculation(
                option.ticker(),
                option.optionType(),
                spot,
                option.strike(),
                impliedVolPct.setScale(2, RoundingMode.HALF_UP),
                riskFreeRatePct.setScale(2, RoundingMode.HALF_UP),
                asOfDate,
                option.expirationDate(),
                daysToExpiration,
                false,
                premium,
                intrinsicScaled,
                timeValue,
                toScaled(deltaVal, 4),
                toScaled(gammaVal, 6),
                toScaled(thetaDaily, 4),
                toScaled(vegaVal, 4),
                toScaled(rhoVal, 4));
    }

    static double normalPdf(double x) {
        return INV_SQRT_2PI * Math.exp(-0.5 * x * x);
    }

    /** Функция распределения стандартного нормального закона через аппроксимацию erf (Abramowitz & Stegun 7.1.26). */
    static double normalCdf(double x) {
        return 0.5 * (1.0 + erf(x / SQRT_2));
    }

    private static double erf(double z) {
        double sign = z < 0 ? -1.0 : 1.0;
        double x = Math.abs(z);
        double t = 1.0 / (1.0 + 0.3275911 * x);
        double poly = ((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t;
        double y = 1.0 - poly * Math.exp(-x * x);
        return sign * y;
    }

    private static BigDecimal toScaled(double value, int scale) {
        if (!Double.isFinite(value)) {
            return BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP);
        }
        BigDecimal scaled = BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
        return scaled.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP) : scaled;
    }

    public record OptionGreeksCalculation(
            String ticker,
            String optionType,
            BigDecimal spot,
            BigDecimal strike,
            BigDecimal impliedVolPct,
            BigDecimal riskFreeRatePct,
            LocalDate asOfDate,
            LocalDate expirationDate,
            int daysToExpiration,
            boolean expired,
            BigDecimal premium,
            BigDecimal intrinsicValue,
            BigDecimal timeValue,
            BigDecimal delta,
            BigDecimal gamma,
            BigDecimal theta,
            BigDecimal vega,
            BigDecimal rho) {

        /** Представление по схеме {@code OptionGreeksResponse} контракта {@code api/openapi.yaml} (INV-6). */
        public OptionGreeksResponse toResponse() {
            return new OptionGreeksResponse(
                    ticker,
                    optionType,
                    FuturesSpec.formatPrice(spot),
                    FuturesSpec.formatPrice(strike),
                    impliedVolPct.toPlainString(),
                    riskFreeRatePct.toPlainString(),
                    asOfDate,
                    expirationDate,
                    daysToExpiration,
                    expired,
                    premium.toPlainString(),
                    intrinsicValue.toPlainString(),
                    timeValue.toPlainString(),
                    delta.toPlainString(),
                    gamma.toPlainString(),
                    theta.toPlainString(),
                    vega.toPlainString(),
                    rho.toPlainString());
        }
    }

    public record OptionGreeksResponse(
            String ticker,
            String optionType,
            String spot,
            String strike,
            String impliedVolPct,
            String riskFreeRatePct,
            LocalDate asOfDate,
            LocalDate expirationDate,
            int daysToExpiration,
            boolean expired,
            String premium,
            String intrinsicValue,
            String timeValue,
            String delta,
            String gamma,
            String theta,
            String vega,
            String rho) {}
}
