package ru.tinvest.trainer.e2e;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import ru.tinvest.trainer.e2e.MarketFixture.Bar;
import ru.tinvest.trainer.e2e.MarketFixture.Paper;

/**
 * Независимый расчёт денег по правилам {@code decisions.md} («Исполнение», «Деньги и округление») в {@link BigDecimal}:
 * цена рыночной заявки с проскальзыванием и спредом, сумма сделки, комиссия и сбор, остаток денег, стоимость
 * портфеля. Ничего не берёт из ответов воркера: только данные фикстуры и правила. Сверка INV-6: сравнение с ответами
 * воркера до копейки.
 */
final class MoneyModel {

    static final BigDecimal COMMISSION_RATE = new BigDecimal("0.003");
    static final BigDecimal EXCHANGE_FEE_RATE = new BigDecimal("0.0001");
    static final BigDecimal SPREAD = new BigDecimal("0.001");
    static final BigDecimal KAPPA = new BigDecimal("0.1");

    /** Исполнение рыночной заявки. */
    record Fill(
            String secid,
            boolean buy,
            long lots,
            BigDecimal price,
            BigDecimal value,
            BigDecimal commission,
            BigDecimal fee) {}

    BigDecimal cash;
    final Map<String, Long> positions = new HashMap<>();
    BigDecimal commissions = BigDecimal.ZERO;
    BigDecimal fees = BigDecimal.ZERO;
    BigDecimal spreadCosts = BigDecimal.ZERO;
    BigDecimal slippageCosts = BigDecimal.ZERO;

    MoneyModel(BigDecimal capital) {
        this.cash = capital;
    }

    /** Рыночная заявка исполняется от {@code open} бара шага исполнения; объём бара в лотах — целочисленное деление. */
    Fill market(String secid, boolean buy, long lots, Bar bar) {
        Paper paper = MarketFixture.paper(secid);
        long barVolumeLots = bar.volume() / paper.lotSize();
        BigDecimal price = fillPrice(buy, bar.open(), lots, barVolumeLots, paper.priceStep());
        BigDecimal value = price.multiply(BigDecimal.valueOf(lots))
                .multiply(BigDecimal.valueOf(paper.lotSize()))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal commission = value.multiply(COMMISSION_RATE).setScale(2, RoundingMode.CEILING);
        BigDecimal fee = value.multiply(EXCHANGE_FEE_RATE).setScale(2, RoundingMode.CEILING);
        if (buy) {
            cash = cash.subtract(value).subtract(commission).subtract(fee);
            positions.merge(secid, lots, Long::sum);
        } else {
            cash = cash.add(value).subtract(commission).subtract(fee);
            positions.merge(secid, -lots, Long::sum);
        }
        commissions = commissions.add(commission);
        fees = fees.add(fee);
        // разбивка разницы цены и базы: спред одним округлением, проскальзывание — остаток
        BigDecimal lotsTimesSize = BigDecimal.valueOf(lots).multiply(BigDecimal.valueOf(paper.lotSize()));
        BigDecimal spread =
                bar.open().multiply(lotsTimesSize).multiply(SPREAD).divide(BigDecimal.TWO, 2, RoundingMode.HALF_UP);
        BigDecimal total =
                price.subtract(bar.open()).abs().multiply(lotsTimesSize).setScale(2, RoundingMode.HALF_UP);
        spreadCosts = spreadCosts.add(spread);
        slippageCosts = slippageCosts.add(total.subtract(spread));
        return new Fill(secid, buy, lots, price, value, commission, fee);
    }

    /**
     * Цена исполнения: {@code frac = κ·lots / V} до 9 знаков; покупка {@code 1 + s/2 + frac}, продажа
     * {@code 1 − s/2 − frac}; {@code base·m} до 9 знаков и до шага цены одним режимом (покупка вверх, продажа вниз);
     * цена продажи не ниже шага.
     */
    static BigDecimal fillPrice(boolean buy, BigDecimal base, long lots, long barVolumeLots, BigDecimal step) {
        BigDecimal frac = barVolumeLots <= 0
                ? BigDecimal.ZERO
                : KAPPA.multiply(BigDecimal.valueOf(lots))
                        .divide(BigDecimal.valueOf(barVolumeLots), 9, RoundingMode.HALF_UP);
        BigDecimal half = SPREAD.divide(BigDecimal.TWO, 9, RoundingMode.HALF_UP);
        BigDecimal multiplier = buy
                ? BigDecimal.ONE.add(half).add(frac)
                : BigDecimal.ONE.subtract(half).subtract(frac);
        RoundingMode mode = buy ? RoundingMode.CEILING : RoundingMode.FLOOR;
        BigDecimal exact = base.multiply(multiplier).setScale(9, mode);
        BigDecimal price = exact.divide(step, 0, mode).multiply(step);
        return !buy && price.compareTo(step) < 0 ? step : price;
    }

    /** Стоимость портфеля: деньги плюс позиции по close последней свечи (до копейки). */
    BigDecimal equity(Map<String, BigDecimal> closes) {
        BigDecimal total = cash;
        for (Map.Entry<String, Long> e : positions.entrySet()) {
            if (e.getValue() != 0) {
                total = total.add(marketValue(e.getKey(), e.getValue(), closes.get(e.getKey())));
            }
        }
        return total;
    }

    static BigDecimal marketValue(String secid, long lots, BigDecimal close) {
        return close.multiply(BigDecimal.valueOf(lots))
                .multiply(BigDecimal.valueOf(MarketFixture.paper(secid).lotSize()))
                .setScale(2, RoundingMode.HALF_UP);
    }

    long position(String secid) {
        return positions.getOrDefault(secid, 0L);
    }
}
