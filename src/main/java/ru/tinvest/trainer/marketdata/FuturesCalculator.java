package ru.tinvest.trainer.marketdata;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;

/**
 * Точный расчёт параметров позиции по фьючерсу ФОРТС на {@link BigDecimal} (INV-4, INV-6): вариационная маржа,
 * совокупное гарантийное обеспечение (ГО), номинальная стоимость позиции, доходность на ГО (ROE) и эффективное плечо.
 */
@Service
public class FuturesCalculator {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public FuturesCalculation calculate(
            FuturesSpec futures, BigDecimal entryPrice, BigDecimal currentPrice, int contracts, LocalDate asOfDate) {
        if (entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("entryPrice must be positive");
        }
        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("currentPrice must be positive");
        }
        if (contracts == 0) {
            throw new IllegalArgumentException("contracts must be non-zero");
        }
        if (futures.priceStep().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("priceStep must be positive");
        }
        if (futures.stepPriceRub().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("stepPriceRub must be positive");
        }
        if (futures.initialMarginRub().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("initialMarginRub must be positive");
        }

        BigDecimal contractsBd = BigDecimal.valueOf(contracts);
        BigDecimal absContractsBd = BigDecimal.valueOf(Math.abs((long) contracts));

        BigDecimal variationMarginRub = currentPrice
                .subtract(entryPrice, MC)
                .divide(futures.priceStep(), MC)
                .multiply(futures.stepPriceRub(), MC)
                .multiply(contractsBd, MC)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal initialMarginTotalRub =
                futures.initialMarginRub().multiply(absContractsBd, MC).setScale(2, RoundingMode.HALF_UP);

        BigDecimal notionalRub = currentPrice
                .divide(futures.priceStep(), MC)
                .multiply(futures.stepPriceRub(), MC)
                .multiply(absContractsBd, MC)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal roeOnMarginPct =
                variationMarginRub.multiply(HUNDRED, MC).divide(initialMarginTotalRub, 2, RoundingMode.HALF_UP);

        BigDecimal effectiveLeverage = notionalRub.divide(initialMarginTotalRub, 2, RoundingMode.HALF_UP);

        long days = ChronoUnit.DAYS.between(asOfDate, futures.expirationDate());
        int daysToExpiration = (int) Math.max(0L, days);
        boolean expired = !asOfDate.isBefore(futures.expirationDate());

        return new FuturesCalculation(
                futures.ticker(),
                entryPrice,
                currentPrice,
                contracts,
                asOfDate,
                futures.expirationDate(),
                daysToExpiration,
                expired,
                variationMarginRub,
                initialMarginTotalRub,
                notionalRub,
                roeOnMarginPct,
                effectiveLeverage);
    }

    public record FuturesCalculation(
            String ticker,
            BigDecimal entryPrice,
            BigDecimal currentPrice,
            int contracts,
            LocalDate asOfDate,
            LocalDate expirationDate,
            int daysToExpiration,
            boolean expired,
            BigDecimal variationMarginRub,
            BigDecimal initialMarginTotalRub,
            BigDecimal notionalRub,
            BigDecimal roeOnMarginPct,
            BigDecimal effectiveLeverage) {

        /** Представление по схеме {@code FuturesCalculationResponse} контракта {@code api/openapi.yaml} (INV-6). */
        public FuturesCalculationResponse toResponse() {
            return new FuturesCalculationResponse(
                    ticker,
                    FuturesSpec.formatPrice(entryPrice),
                    FuturesSpec.formatPrice(currentPrice),
                    contracts,
                    asOfDate,
                    expirationDate,
                    daysToExpiration,
                    expired,
                    variationMarginRub.toPlainString(),
                    initialMarginTotalRub.toPlainString(),
                    notionalRub.toPlainString(),
                    roeOnMarginPct.toPlainString(),
                    effectiveLeverage.toPlainString());
        }
    }

    public record FuturesCalculationResponse(
            String ticker,
            String entryPrice,
            String currentPrice,
            int contracts,
            LocalDate asOfDate,
            LocalDate expirationDate,
            int daysToExpiration,
            boolean expired,
            String variationMarginRub,
            String initialMarginTotalRub,
            String notionalRub,
            String roeOnMarginPct,
            String effectiveLeverage) {}
}
