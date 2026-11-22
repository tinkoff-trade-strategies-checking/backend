package ru.tinvest.trainer.marketdata.importer;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Клиент MOEX ISS ({@code https://iss.moex.com/iss}, JSON): постраничная выгрузка с паузой и повтором. */
final class IssClient {

    private static final Logger log = LoggerFactory.getLogger(IssClient.class);

    /** Размер страницы {@code candles.json} в ISS (проверено 19.11.2026). */
    static final int DEFAULT_CANDLES_PAGE = 500;

    private final HttpFetcher fetcher;
    private final String baseUrl;
    private final int emptyRetries;
    private final int candlesPage;

    IssClient(HttpFetcher fetcher, String baseUrl, int emptyRetries, int candlesPage) {
        this.candlesPage = candlesPage;
        this.fetcher = fetcher;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.emptyRetries = emptyRetries;
    }

    /** Одна страница блока; пустая после повторов — пустая таблица. */
    IssTable page(String path, Map<String, String> query, String block) {
        Map<String, String> q = new LinkedHashMap<>(query);
        q.put("iss.meta", "off");
        q.put("iss.only", block + "," + block + ".cursor");
        return fetcher.get(
                uri(path, q),
                body -> IssParsers.table(body, block),
                // Пустой ответ с явным PREV_DATE (неторговый день в analytics, TOTAL=0) — не сбой, повторы не нужны;
                // при TOTAL > 0 на торговом дне или отсутствии PREV_DATE повторяем emptyRetries раз.
                t -> t.isEmpty() && (t.prevDate() == null || (t.total() != null && t.total() > 0)),
                emptyRetries);
    }

    /**
     * Все страницы блока. Следующая страница нужна, пока набрано меньше {@code TOTAL} из курсора, а без курсора — пока
     * последняя страница полная ({@code pageSize}; {@code pageSize = 0} без курсора — ровно одна страница). Если
     * {@code TOTAL} известен, пустая страница (после повторов в {@link #page}) при недоборе и итог не равный
     * {@code TOTAL} — {@link ImportException}: усечённые данные не принимаются за конец.
     */
    IssTable all(String path, Map<String, String> query, String block, int pageSize) {
        List<List<tools.jackson.databind.JsonNode>> rows = new ArrayList<>();
        List<String> columns = List.of();
        Long total = null;
        int start = 0;
        while (true) {
            Map<String, String> q = new LinkedHashMap<>(query);
            q.put("start", Integer.toString(start));
            IssTable p = page(path, q, block);
            if (p.total() != null) {
                total = p.total();
            }
            if (p.isEmpty()) {
                if (total != null && start < total) {
                    throw new ImportException(
                            "ISS отдал пустую страницу " + path + " при start=" + start + " из TOTAL=" + total);
                }
                break;
            }
            columns = p.columns();
            rows.addAll(p.rows());
            start += p.size();
            boolean more = total != null ? start < total : pageSize > 0 && p.size() >= pageSize;
            if (!more) {
                break;
            }
        }
        if (total != null && start != total) {
            throw new ImportException("ISS: " + path + " получено " + start + " строк, TOTAL=" + total);
        }
        return new IssTable(columns, rows, total, null);
    }

    /** Минутные (или иные) свечи акции на основной доске TQBR за календарные даты {@code from}…{@code till}. */
    List<Candle> shareCandles(String secid, int issInterval, LocalDate from, LocalDate till) {
        IssTable t = all(
                "/engines/stock/markets/shares/boards/TQBR/securities/" + secid + "/candles",
                Map.of("interval", Integer.toString(issInterval), "from", from.toString(), "till", till.toString()),
                "candles",
                candlesPage);
        return IssParsers.candles(t);
    }

    /** Дневные свечи индекса (доска SNDX). */
    List<Candle> indexDailyCandles(String indexId, LocalDate from, LocalDate till) {
        return indexCandles(indexId, 24, from, till);
    }

    /** Свечи индекса (доска SNDX); {@code issInterval} — 10 (мин), 24 (сутки). */
    List<Candle> indexCandles(String indexId, int issInterval, LocalDate from, LocalDate till) {
        IssTable t = all(
                "/engines/stock/markets/index/boards/SNDX/securities/" + indexId + "/candles",
                Map.of("interval", Integer.toString(issInterval), "from", from.toString(), "till", till.toString()),
                "candles",
                candlesPage);
        return IssParsers.candles(t);
    }

    /** Период торгов бумаги на TQBR по данным ISS ({@code listed_from}, {@code listed_till}); нет данных — пусто. */
    Optional<Listing> listing(String secid) {
        IssTable t =
                page("/securities/" + secid, Map.of("boards.columns", "boardid,listed_from,listed_till"), "boards");
        for (List<tools.jackson.databind.JsonNode> r : t.rows()) {
            if ("TQBR".equals(t.get(r, "boardid").asString())
                    && !t.get(r, "listed_from").isNull()
                    && !t.get(r, "listed_till").isNull()) {
                return Optional.of(new Listing(
                        LocalDate.parse(t.get(r, "listed_from").asString()),
                        LocalDate.parse(t.get(r, "listed_till").asString())));
            }
        }
        return Optional.empty();
    }

    record Listing(LocalDate from, LocalDate till) {}

    /** Параметры акции на TQBR: лот, шаг цены, названия; пустая таблица — бумаги нет на доске. */
    IssTable securityInfo(String secid) {
        return page(
                "/engines/stock/markets/shares/boards/TQBR/securities/" + secid,
                Map.of("securities.columns", "SECID,LOTSIZE,MINSTEP,SHORTNAME,SECNAME"),
                "securities");
    }

    /** Состав индекса на дату (или ближайшую предыдущую, если ISS отдал пустой ответ с PREV_DATE). */
    IndexComposition composition(String indexId, LocalDate date) {
        LocalDate d = date;
        for (int i = 0; i < 7; i++) {
            String path = "/statistics/engines/stock/markets/index/analytics/" + indexId;
            Map<String, String> q = Map.of("date", d.toString());
            IssTable first = page(path, q, "analytics");
            if (!first.isEmpty()) {
                IssTable full = all(path, q, "analytics", 0);
                List<String> secids = new ArrayList<>();
                Map<String, BigDecimal> weights = new LinkedHashMap<>();
                for (List<tools.jackson.databind.JsonNode> r : full.rows()) {
                    String secid = full.get(r, "secids").asString();
                    secids.add(secid);
                    tools.jackson.databind.JsonNode w = full.get(r, "weight");
                    if (w == null || w.isNull()) {
                        log.warn(
                                "{}: в составе {} на {} нет веса {}, вес 0",
                                indexId,
                                secid,
                                d,
                                "(формат ISS изменился?)");
                    }
                    BigDecimal weight = w == null || w.isNull() ? BigDecimal.ZERO : new BigDecimal(w.asString());
                    weights.merge(secid, weight, BigDecimal::max);
                }
                return new IndexComposition(d, secids, weights);
            }
            if (first.prevDate() == null) {
                break;
            }
            d = LocalDate.parse(first.prevDate());
        }
        log.warn("Состав индекса {} на {} не найден", indexId, date);
        return new IndexComposition(date, List.of(), Map.of());
    }

    /** Сплиты и консолидации: {@code tradedate}, {@code secid}, {@code before}, {@code after}. */
    IssTable splits() {
        return all("/statistics/engines/stock/splits", Map.of(), "splits", 0);
    }

    private URI uri(String path, Map<String, String> query) {
        StringBuilder sb = new StringBuilder(baseUrl).append(path).append(".json");
        char sep = '?';
        for (Map.Entry<String, String> e : query.entrySet()) {
            sb.append(sep)
                    .append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            sep = '&';
        }
        return URI.create(sb.toString());
    }

    /** {@code weights} — вес бумаги в индексе, %; бумага без веса в ответе — 0. */
    record IndexComposition(LocalDate date, List<String> secids, Map<String, BigDecimal> weights) {}
}
