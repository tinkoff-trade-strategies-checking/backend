package ru.tinvest.trainer.marketdata;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;

/**
 * Точный серверный налоговый калькулятор РФ на {@link BigDecimal} (INV-4, INV-6, backend#94):
 * прогрессивная шкала НДФЛ (13% до 2 400 000 ₽, 15% свыше), сальдирование убытков прошлых лет,
 * льготы ИИС-А (вычет на взнос до 400 000 ₽) и ИИС-Б / ИИС-3 (освобождение торговой прибыли от НДФЛ;
 * дивиденды облагаются НДФЛ на всех типах счетов в соответствии с НК РФ).
 */
@Service
public class TaxCalculator {

    static final BigDecimal NDFL_THRESHOLD_RUB = new BigDecimal("2400000.00");
    static final BigDecimal RATE_BASE = new BigDecimal("0.13");
    static final BigDecimal RATE_HIGH = new BigDecimal("0.15");
    static final BigDecimal IIS_A_MAX_CONTRIBUTION_RUB = new BigDecimal("400000.00");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal ZERO_RUB = new BigDecimal("0.00");
    private static final MathContext MC = MathContext.DECIMAL128;

    public enum AccountType {
        BROKERAGE,
        IIS_A,
        IIS_B,
        IIS_3
    }

    public TaxCalculation calculate(
            BigDecimal grossProfitRub,
            BigDecimal priorLossesRub,
            BigDecimal dividendsRub,
            AccountType accountType,
            BigDecimal annualContributionsRub) {
        if (grossProfitRub == null
                || priorLossesRub == null
                || dividendsRub == null
                || annualContributionsRub == null) {
            throw new IllegalArgumentException("Monetary inputs must not be null");
        }
        if (accountType == null) {
            throw new IllegalArgumentException("accountType must not be null");
        }
        if (priorLossesRub.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("priorLossesRub must be >= 0");
        }
        if (dividendsRub.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("dividendsRub must be >= 0");
        }
        if (annualContributionsRub.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("annualContributionsRub must be >= 0");
        }

        BigDecimal profit = grossProfitRub.setScale(2, RoundingMode.HALF_UP);
        BigDecimal losses = priorLossesRub.setScale(2, RoundingMode.HALF_UP);
        BigDecimal dividends = dividendsRub.setScale(2, RoundingMode.HALF_UP);
        BigDecimal contributions = annualContributionsRub.setScale(2, RoundingMode.HALF_UP);

        BigDecimal lossOffsetUsedRub;
        BigDecimal taxableProfitRub;
        if (profit.compareTo(BigDecimal.ZERO) > 0) {
            lossOffsetUsedRub = profit.min(losses).setScale(2, RoundingMode.HALF_UP);
            taxableProfitRub = profit.subtract(lossOffsetUsedRub).setScale(2, RoundingMode.HALF_UP);
        } else {
            lossOffsetUsedRub = ZERO_RUB;
            taxableProfitRub = ZERO_RUB;
        }

        BigDecimal tradingTaxRub;
        BigDecimal dividendTaxRub;
        BigDecimal totalTaxRub;
        if (accountType == AccountType.IIS_B || accountType == AccountType.IIS_3) {
            tradingTaxRub = ZERO_RUB;
            dividendTaxRub = progressiveNdfl(dividends);
            totalTaxRub = dividendTaxRub;
        } else {
            tradingTaxRub = progressiveNdfl(taxableProfitRub);
            BigDecimal combinedTax = progressiveNdfl(taxableProfitRub.add(dividends));
            dividendTaxRub = combinedTax.subtract(tradingTaxRub).setScale(2, RoundingMode.HALF_UP);
            totalTaxRub = combinedTax;
        }

        BigDecimal iisDeductionRefundRub;
        if (accountType == AccountType.IIS_A) {
            BigDecimal deductionBase = contributions.min(IIS_A_MAX_CONTRIBUTION_RUB);
            BigDecimal effectiveMarginalRate =
                    taxableProfitRub.add(dividends).compareTo(NDFL_THRESHOLD_RUB) > 0 ? RATE_HIGH : RATE_BASE;
            iisDeductionRefundRub =
                    deductionBase.multiply(effectiveMarginalRate, MC).setScale(2, RoundingMode.HALF_UP);
        } else {
            iisDeductionRefundRub = ZERO_RUB;
        }

        BigDecimal totalIncome = profit.add(dividends);
        BigDecimal netAfterTaxRub =
                totalIncome.subtract(totalTaxRub).add(iisDeductionRefundRub).setScale(2, RoundingMode.HALF_UP);

        BigDecimal effectiveTaxRatePct = totalIncome.compareTo(BigDecimal.ZERO) > 0
                ? totalTaxRub.multiply(HUNDRED, MC).divide(totalIncome, 2, RoundingMode.HALF_UP)
                : ZERO_RUB;

        return new TaxCalculation(
                accountType,
                taxableProfitRub,
                lossOffsetUsedRub,
                tradingTaxRub,
                dividendTaxRub,
                totalTaxRub,
                iisDeductionRefundRub,
                netAfterTaxRub,
                effectiveTaxRatePct);
    }

    static BigDecimal progressiveNdfl(BigDecimal taxableBase) {
        if (taxableBase.compareTo(BigDecimal.ZERO) <= 0) {
            return ZERO_RUB;
        }
        if (taxableBase.compareTo(NDFL_THRESHOLD_RUB) <= 0) {
            return taxableBase.multiply(RATE_BASE, MC).setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal basePart = NDFL_THRESHOLD_RUB.multiply(RATE_BASE, MC);
        BigDecimal highPart = taxableBase.subtract(NDFL_THRESHOLD_RUB).multiply(RATE_HIGH, MC);
        return basePart.add(highPart, MC).setScale(2, RoundingMode.HALF_UP);
    }

    public record TaxCalculation(
            AccountType accountType,
            BigDecimal taxableProfitRub,
            BigDecimal lossOffsetUsedRub,
            BigDecimal tradingTaxRub,
            BigDecimal dividendTaxRub,
            BigDecimal totalTaxRub,
            BigDecimal iisDeductionRefundRub,
            BigDecimal netAfterTaxRub,
            BigDecimal effectiveTaxRatePct) {

        /** Представление по схеме {@code TaxCalculationResponse} контракта {@code api/openapi.yaml} (INV-6). */
        public TaxCalculationResponse toResponse() {
            return new TaxCalculationResponse(
                    accountType.name(),
                    taxableProfitRub.toPlainString(),
                    lossOffsetUsedRub.toPlainString(),
                    tradingTaxRub.toPlainString(),
                    dividendTaxRub.toPlainString(),
                    totalTaxRub.toPlainString(),
                    iisDeductionRefundRub.toPlainString(),
                    netAfterTaxRub.toPlainString(),
                    effectiveTaxRatePct.toPlainString());
        }
    }

    public record TaxCalculationResponse(
            String accountType,
            String taxableProfitRub,
            String lossOffsetUsedRub,
            String tradingTaxRub,
            String dividendTaxRub,
            String totalTaxRub,
            String iisDeductionRefundRub,
            String netAfterTaxRub,
            String effectiveTaxRatePct) {}
}
