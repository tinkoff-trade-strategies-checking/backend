package ru.tinvest.trainer.e2e;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Рекурсивная проверка INV-7: в ответе слепой сессии до её завершения нет названия, тикера, шага цены и лота,
 * реальных дат и времени, рублёвых сумм. Ищет по значениям (тикеры, названия, даты, цены архива, капитал) и по именам
 * полей раскрытого вида (деньги, {@code ticker}, {@code openedAt}, …). Служебные {@code createdAt} и заголовок
 * «Слепая сессия от ДД.ММ.ГГГГ» разрешены (INV-7, уточнение 18.11.2026).
 */
final class LeakScanner {

    /** Вид утечки; положительный контроль на неслепой сессии требует, чтобы нашлись все виды. */
    enum Kind {
        TICKER,
        NAME,
        DATE,
        TIME,
        PRICE,
        CAPITAL,
        FIELD
    }

    record Leak(Kind kind, String path, String value) {}

    /** Поля раскрытого вида: рубли, бумага, реальное время. */
    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
            "ticker",
            "name",
            "lotsize",
            "pricestep",
            "secid",
            "paperid",
            "key",
            "openedat",
            "executedat",
            "period",
            "startdate",
            "enddate",
            "equity",
            "cash",
            "invested",
            "pnl",
            "costs",
            "cost",
            "amount",
            "marketvalue",
            "commission",
            "exchangefee",
            "spread",
            "slippage",
            "initialcapital",
            "finalcapital");

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}|\\d{2}\\.\\d{2}\\.\\d{4}");
    private static final Pattern TIME = Pattern.compile("\\d{2}:\\d{2}(:\\d{2})?");
    private static final Set<String> SERVICE_FIELDS = Set.of("createdat", "title");

    private final List<String> tickers;
    private final List<String> names;
    private final Set<BigDecimal> prices = new HashSet<>();
    private final Set<BigDecimal> capitals;

    LeakScanner(MarketFixture fixture, Set<BigDecimal> capitals) {
        this.tickers = new ArrayList<>(
                MarketFixture.PAPERS.stream().map(MarketFixture.Paper::secid).toList());
        this.tickers.add(MarketFixture.INDEX);
        this.names =
                MarketFixture.PAPERS.stream().map(MarketFixture.Paper::name).toList();
        fixture.allPrices().forEach(p -> prices.add(p.stripTrailingZeros()));
        this.capitals = new HashSet<>();
        capitals.forEach(c -> this.capitals.add(c.stripTrailingZeros()));
    }

    /** Все утечки в одном ответе; {@code where} — для сообщений об ошибке. */
    List<Leak> scan(String where, JsonNode node) {
        List<Leak> leaks = new ArrayList<>();
        walk(where, node, null, leaks);
        return leaks;
    }

    List<Leak> scanAll(List<ApiClient.Response> responses) {
        List<Leak> leaks = new ArrayList<>();
        for (ApiClient.Response response : responses) {
            if (response.json() != null) {
                walk(response.method() + " " + response.path(), response.json(), null, leaks);
            } else {
                // не JSON (например, страница ошибки): ищем по тексту целиком
                scanText(response.method() + " " + response.path(), response.body(), leaks);
            }
        }
        return leaks;
    }

    static Set<Kind> kinds(List<Leak> leaks) {
        Set<Kind> kinds = EnumSet.noneOf(Kind.class);
        leaks.forEach(l -> kinds.add(l.kind()));
        return kinds;
    }

    private void walk(String path, JsonNode node, String field, List<Leak> leaks) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                String name = e.getKey();
                String lower = name.toLowerCase(Locale.ROOT);
                if (FORBIDDEN_FIELDS.contains(lower) && !e.getValue().isNull()) {
                    leaks.add(
                            new Leak(Kind.FIELD, path + "." + name, e.getValue().toString()));
                }
                // ключи словарей по бумаге в раскрытом виде — UUID; в слепом — только цифры
                walk(path + "." + name, e.getValue(), lower, leaks);
            }
        } else if (node.isArray()) {
            int i = 0;
            for (JsonNode item : node) {
                walk(path + "[" + i++ + "]", item, field, leaks);
            }
        } else if (node.isNumber()) {
            checkNumber(path, node.decimalValue(), leaks);
        } else if (node.isString()) {
            String text = node.asString();
            if (field != null && SERVICE_FIELDS.contains(field)) {
                return;
            }
            scanText(path, text, leaks);
            if (isDecimal(text)) {
                checkNumber(path, new BigDecimal(text), leaks);
            }
        }
    }

    private void scanText(String path, String text, List<Leak> leaks) {
        String upper = text.toUpperCase(Locale.ROOT);
        for (String ticker : tickers) {
            if (Pattern.compile("(^|[^A-Z0-9])" + ticker + "([^A-Z0-9]|$)")
                    .matcher(upper)
                    .find()) {
                leaks.add(new Leak(Kind.TICKER, path, text));
            }
        }
        for (String name : names) {
            if (text.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT))) {
                leaks.add(new Leak(Kind.NAME, path, text));
            }
        }
        if (DATE.matcher(text).find()) {
            leaks.add(new Leak(Kind.DATE, path, text));
        }
        if (TIME.matcher(text).find()) {
            leaks.add(new Leak(Kind.TIME, path, text));
        }
    }

    private void checkNumber(String path, BigDecimal value, List<Leak> leaks) {
        BigDecimal normal = value.stripTrailingZeros();
        if (prices.contains(normal)) {
            leaks.add(new Leak(Kind.PRICE, path, value.toPlainString()));
        }
        if (capitals.contains(normal)) {
            leaks.add(new Leak(Kind.CAPITAL, path, value.toPlainString()));
        }
    }

    private static boolean isDecimal(String text) {
        return text.matches("-?\\d+(\\.\\d+)?");
    }
}
