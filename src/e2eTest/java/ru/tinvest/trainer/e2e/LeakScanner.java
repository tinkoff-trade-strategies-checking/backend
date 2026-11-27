package ru.tinvest.trainer.e2e;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
        FIELD,
        RUBLE
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
    /** Служебные даты и заголовок слепой сессии допустимы только в своём виде (INV-7, уточнение 18.11.2026). */
    private static final Pattern BLIND_TITLE = Pattern.compile("Слепая сессия от \\d{2}\\.\\d{2}\\.\\d{4}");

    private static final Pattern OTHER_DATE =
            Pattern.compile("\\d{4}/\\d{2}/\\d{2}|\\b20\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\b");
    private static final Pattern MONTH_WORD = Pattern.compile("(?i)(январ|феврал|март|апрел|мая|июн|июл|август|сентябр"
            + "|октябр|ноябр|декабр|jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[а-яa-z]*\\.?[\\s,]*(\\d{1,2}[\\s,]+)?20\\d{2}"
            + "|\\d{1,2}\\s+(января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)");
    private static final Pattern RUBLE_TEXT = Pattern.compile("(?i)₽|руб");

    /** Поля, где число бывает большим и не деньгами. */
    private static final Set<String> COUNT_FIELDS = Set.of(
            "lots",
            "filledlots",
            "maxlots",
            "pct25",
            "pct50",
            "pct100",
            "barscount",
            "maxbars",
            "step",
            "totalsteps",
            "warmupsteps",
            "eventid",
            "lasteventid",
            "day",
            "totaldays",
            "playeddays",
            "candleinday",
            "finishedatstep",
            "finishedatday");

    /** Слепое число не бывает больше этого: цены от 100, проценты, шаги, дни; больше — похоже на рубли. */
    private static final BigDecimal RUBLE_THRESHOLD = new BigDecimal("1000");

    private final List<String> tickers;
    private final List<String> names;
    private final Set<BigDecimal> prices = new HashSet<>();
    private final Set<BigDecimal> capitals;
    private final LocalDate firstDay;
    private final LocalDate lastDay;
    private final Set<Long> epochs = new HashSet<>();

    LeakScanner(MarketFixture fixture, Set<BigDecimal> capitals) {
        this.tickers = new ArrayList<>(
                MarketFixture.PAPERS.stream().map(MarketFixture.Paper::secid).toList());
        this.tickers.add(MarketFixture.INDEX);
        List<String> all = new ArrayList<>(
                MarketFixture.PAPERS.stream().map(MarketFixture.Paper::name).toList());
        all.add("Индекс МосБиржи");
        this.names = all;
        this.firstDay = fixture.days().getFirst();
        this.lastDay = fixture.days().getLast();
        // epoch секунды и миллисекунды любого момента дней фикстуры ±1 сутки: любой отсчёт времени бара
        for (LocalDate d = firstDay.minusDays(2); !d.isAfter(lastDay.plusDays(2)); d = d.plusDays(1)) {
            epochs.add(d.atStartOfDay(ZoneOffset.UTC).toEpochSecond() / 86_400L);
        }
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

    /** Ответы сессии: каталог запуска и бумаг открыт всем и не относится к слепой сессии, его не проверяем. */
    List<Leak> scanAll(List<ApiClient.Response> responses) {
        return scanAll(responses, false);
    }

    /** {@code withCatalog} — вместе с открытым каталогом (положительный контроль). */
    List<Leak> scanAll(List<ApiClient.Response> responses, boolean withCatalog) {
        List<Leak> leaks = new ArrayList<>();
        for (ApiClient.Response response : responses) {
            if (!withCatalog
                    && (response.path().startsWith("/api/v1/launch/")
                            || response.path().startsWith("/api/v1/papers"))) {
                continue;
            }
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
            checkNumber(path, field, node.decimalValue(), leaks);
        } else if (node.isString()) {
            String text = node.asString();
            if ("title".equals(field) && BLIND_TITLE.matcher(text).matches()) {
                return;
            }
            if ("createdat".equals(field)) {
                checkCreatedAt(path, text, leaks);
                return;
            }
            scanText(path, text, leaks);
            if (isDecimal(text)) {
                checkNumber(path, field, new BigDecimal(text), leaks);
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
        if (OTHER_DATE.matcher(text).find() || MONTH_WORD.matcher(text).find()) {
            leaks.add(new Leak(Kind.DATE, path, text));
        }
        if (RUBLE_TEXT.matcher(text).find()) {
            leaks.add(new Leak(Kind.RUBLE, path, text));
        }
    }

    /** {@code createdAt} — служебное время создания (сейчас); дата внутри архива — утечка периода. */
    private void checkCreatedAt(String path, String text, List<Leak> leaks) {
        if (text.length() >= 10 && DATE.matcher(text.substring(0, 10)).matches()) {
            LocalDate date = LocalDate.parse(text.substring(0, 10));
            if (!date.isBefore(firstDay.minusDays(1)) && !date.isAfter(lastDay.plusDays(1))) {
                leaks.add(new Leak(Kind.DATE, path, text));
            }
        }
    }

    private void checkNumber(String path, String field, BigDecimal value, List<Leak> leaks) {
        BigDecimal normal = value.stripTrailingZeros();
        boolean integer = normal.scale() <= 0;
        if (integer && value.abs().compareTo(new BigDecimal("1000000000")) > 0) {
            // epoch: секунды (10 цифр) или миллисекунды (13 цифр) момента внутри архива
            long raw = value.longValue();
            long seconds = raw > 100_000_000_000L ? raw / 1000 : raw;
            if (epochs.contains(seconds / 86_400L)) {
                leaks.add(new Leak(Kind.DATE, path, value.toPlainString()));
            }
        }
        if (value.abs().compareTo(RUBLE_THRESHOLD) >= 0 && (field == null || !COUNT_FIELDS.contains(field))) {
            leaks.add(new Leak(Kind.RUBLE, path, value.toPlainString()));
        }
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
