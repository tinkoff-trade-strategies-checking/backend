package ru.tinvest.trainer.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import ru.tinvest.trainer.e2e.MarketFixture.Paper;
import tools.jackson.databind.JsonNode;

/**
 * Сверка денег Java и C++ (INV-6, НФТ-4): одни и те же случайные сделки (фиксированное зерно) идут через HTTP API в
 * настоящий воркер и независимо считаются здесь в {@link BigDecimal} по ценам фикстуры и правилам {@code decisions.md}
 * ({@link MoneyModel}). После каждого шага совпадают до копейки: цена и сумма каждого исполнения, свободные деньги и
 * стоимость портфеля; в отчёте — издержки по видам и итоговый капитал; воспроизведение журнала даёт то же самое.
 */
class MoneyReconciliationE2eTest extends E2eBase {

    private static final int START = 105;
    private static final BigDecimal CAPITAL = new BigDecimal(Flows.CAPITAL);

    @Test
    void randomTradesMatchTheBigDecimalModelToTheKopeck() {
        ApiClient api = newUser();
        JsonNode catalog = api.get("/api/v1/papers").ok();
        Map<String, String> idByTicker = new LinkedHashMap<>();
        catalog.forEach(
                p -> idByTicker.put(p.get("ticker").asString(), p.get("id").asString()));
        Map<String, String> tickerById = new HashMap<>();
        idByTicker.forEach((ticker, id) -> tickerById.put(id, ticker));
        assertThat(idByTicker.keySet()).containsExactlyInAnyOrder("SBER", "GAZP", "LKOH");

        String id = Flows.createOpen(api, day(START).toString(), new ArrayList<>(idByTicker.values()));
        JsonNode start = Flows.snapshot(api, id);
        long totalSteps = start.get("totalSteps").asLong();
        assertThat(totalSteps).isEqualTo(20);
        assertThat(start.get("portfolio").get("cash").asString()).isEqualTo(Flows.CAPITAL);
        assertThat(start.get("portfolio").get("equity").asString()).isEqualTo(Flows.CAPITAL);

        MoneyModel model = new MoneyModel(CAPITAL);
        Random random = new Random(20261120L);
        List<String> tickers = List.copyOf(idByTicker.keySet());
        Map<Long, BigDecimal> equityAtStep = new HashMap<>();
        Map<Long, BigDecimal> cashAtStep = new HashMap<>();
        int fillsExpected = 0;

        for (long cursor = 0; cursor < totalSteps - 1; cursor++) {
            // шаг 0 — первый бар периода (START); заявка при курсоре k исполняется на баре шага k + 1, его open — база
            // цены
            LocalDate execDay = day(START + (int) cursor + 1);
            List<Order> orders = new ArrayList<>();
            int count = random.nextInt(3);
            for (int i = 0; i < count; i++) {
                String ticker = tickers.get(random.nextInt(tickers.size()));
                long held = model.position(ticker) - pendingSells(orders, ticker);
                boolean buy = held <= 0 || random.nextInt(10) < 6;
                long lots = buy ? 1 + random.nextInt(4) : 1 + random.nextInt((int) held);
                JsonNode result = Flows.place(
                        api,
                        id,
                        Flows.order(
                                "\"paperId\":\"" + idByTicker.get(ticker) + "\"",
                                cursor,
                                buy ? "buy" : "sell",
                                "market",
                                lots,
                                null));
                assertThat(result.get("accepted").asBoolean())
                        .as(result.toString())
                        .isTrue();
                orders.add(new Order(ticker, buy, lots));
            }

            JsonNode stepped = Flows.step(api, id);
            long step = cursor + 1;
            assertThat(stepped.get("step").asLong()).isEqualTo(step);
            JsonNode result = stepped.get("stepResult");

            // ожидаемые исполнения: по порядку подачи
            List<MoneyModel.Fill> expected = new ArrayList<>();
            for (Order order : orders) {
                expected.add(model.market(
                        order.ticker(),
                        order.buy(),
                        order.lots(),
                        E2eEnvironment.FIXTURE.bar(order.ticker(), execDay)));
            }
            List<JsonNode> fills = new ArrayList<>();
            result.get("fills").forEach(fills::add);
            assertThat(fills).as("исполнения на шаге " + step).hasSize(expected.size());
            for (int i = 0; i < expected.size(); i++) {
                MoneyModel.Fill want = expected.get(i);
                JsonNode got = fills.get(i);
                assertThat(tickerById.get(got.get("paperId").asString())).isEqualTo(want.secid());
                assertThat(got.get("step").asLong()).isEqualTo(step);
                assertThat(got.get("side").asString()).isEqualTo(want.buy() ? "buy" : "sell");
                assertThat(got.get("lots").asLong()).isEqualTo(want.lots());
                assertThat(new BigDecimal(got.get("price").asString()))
                        .as("цена исполнения " + want.secid() + " шаг " + step)
                        .isEqualByComparingTo(want.price());
                assertThat(new BigDecimal(got.get("amount").asString()).abs())
                        .as("сумма исполнения " + want.secid() + " шаг " + step)
                        .isEqualByComparingTo(want.value());
            }
            fillsExpected += expected.size();

            Map<String, BigDecimal> closes = new HashMap<>();
            for (Paper paper : MarketFixture.PAPERS) {
                closes.put(
                        paper.secid(),
                        E2eEnvironment.FIXTURE.bar(paper.secid(), execDay).close());
            }
            BigDecimal wantCash = model.cash;
            BigDecimal wantEquity = model.equity(closes);
            JsonNode portfolio = result.get("portfolio");
            assertThat(new BigDecimal(portfolio.get("cash").asString()))
                    .as("свободные деньги, шаг " + step)
                    .isEqualByComparingTo(wantCash);
            assertThat(new BigDecimal(portfolio.get("equity").asString()))
                    .as("стоимость портфеля, шаг " + step)
                    .isEqualByComparingTo(wantEquity);
            // снимок арены (восстановление из журнала, INV-8) даёт то же
            JsonNode snapshot = Flows.snapshot(api, id);
            assertThat(snapshot.get("portfolio")).isEqualTo(portfolio);
            assertThat(snapshot.get("fills")).hasSize(fillsExpected);
            equityAtStep.put(step, wantEquity);
            cashAtStep.put(step, wantCash);
        }
        assertThat(fillsExpected).as("в сценарии есть сделки").isGreaterThan(5);

        // последний шаг завершает сессию; отчёт
        assertThat(Flows.snapshot(api, id).get("finished").asBoolean()).isTrue();
        JsonNode report = Flows.report(api, id);
        assertThat(report.get("status").asString()).isEqualTo("READY");
        JsonNode metrics = report.get("report").get("humanMetrics");
        assertThat(new BigDecimal(metrics.get("initialCapital").asString())).isEqualByComparingTo(CAPITAL);
        assertThat(new BigDecimal(metrics.get("finalCapital").asString()))
                .as("итоговый капитал")
                .isEqualByComparingTo(equityAtStep.get(totalSteps - 1));
        JsonNode costs = metrics.get("costs");
        assertThat(new BigDecimal(costs.get("commission").asString()))
                .as("комиссия")
                .isEqualByComparingTo(model.commissions);
        assertThat(new BigDecimal(costs.get("exchangeFee").asString()))
                .as("биржевой сбор")
                .isEqualByComparingTo(model.fees);
        assertThat(new BigDecimal(costs.get("spread").asString())).as("спред").isEqualByComparingTo(model.spreadCosts);
        assertThat(new BigDecimal(costs.get("slippage").asString()))
                .as("проскальзывание")
                .isEqualByComparingTo(model.slippageCosts);

        // воспроизведение журнала на любом шаге даёт ту же стоимость и деньги (INV-8, INV-6)
        for (long step : List.of(1L, 7L, 13L, totalSteps - 1)) {
            JsonNode replay =
                    api.get("/api/v1/sessions/" + id + "/replay?step=" + step).ok();
            assertThat(replay.get("status").asString()).isEqualTo("READY");
            JsonNode portfolio = replay.get("snapshot").get("portfolio");
            assertThat(new BigDecimal(portfolio.get("cash").asString())).isEqualByComparingTo(cashAtStep.get(step));
            assertThat(new BigDecimal(portfolio.get("equity").asString())).isEqualByComparingTo(equityAtStep.get(step));
        }
    }

    private record Order(String ticker, boolean buy, long lots) {}

    /** Лоты, уже заявленные к продаже на этом шаге: не продаём больше позиции на начало шага. */
    private static long pendingSells(List<Order> orders, String ticker) {
        long total = 0;
        for (Order order : orders) {
            if (order.ticker().equals(ticker) && !order.buy()) {
                total += order.lots();
            }
        }
        return total;
    }
}
