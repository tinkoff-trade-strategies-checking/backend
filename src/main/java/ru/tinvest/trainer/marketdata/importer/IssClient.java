package ru.tinvest.trainer.marketdata.importer;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        return fetcher.get(uri(path, q), body -> IssParsers.table(body, block), IssTable::isEmpty, emptyRetries);
    }

    /**
     * Все страницы блока. Следующая страница нужна, пока набрано меньше {@code TOTAL} из курсора, а без курсора — пока
     * последняя страница полная ({@code pageSize}). Пустая страница после повторов — конец данных.
     */
    IssTable all(String path, Map<String, String> query, String block, int pageSize) {
        List<List<tools.jackson.databind.JsonNode>> rows = new ArrayList<>();
        List<String> columns = List.of();
        int start = 0;
        while (true) {
            Map<String, String> q = new LinkedHashMap<>(query);
            q.put("start", Integer.toString(start));
            IssTable p = page(path, q, block);
            if (p.isEmpty()) {
                break;
            }
            columns = p.columns();
            rows.addAll(p.rows());
            start += p.size();
            boolean more = p.total() != null ? start < p.total() : pageSize > 0 && p.size() >= pageSize;
            if (!more) {
                break;
            }
        }
        return new IssTable(columns, rows, null, null);
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
        IssTable t = all(
                "/engines/stock/markets/index/boards/SNDX/securities/" + indexId + "/candles",
                Map.of("interval", "24", "from", from.toString(), "till", till.toString()),
                "candles",
                candlesPage);
        return IssParsers.candles(t);
    }

    /** Параметры акции на TQBR: лот и шаг цены; пустая таблица — бумаги нет на доске. */
    IssTable securityInfo(String secid) {
        return page(
                "/engines/stock/markets/shares/boards/TQBR/securities/" + secid,
                Map.of("securities.columns", "SECID,LOTSIZE,MINSTEP"),
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
                for (List<tools.jackson.databind.JsonNode> r : full.rows()) {
                    secids.add(full.get(r, "secids").asString());
                }
                return new IndexComposition(d, secids);
            }
            if (first.prevDate() == null) {
                break;
            }
            d = LocalDate.parse(first.prevDate());
        }
        log.warn("Состав индекса {} на {} не найден", indexId, date);
        return new IndexComposition(date, List.of());
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

    record IndexComposition(LocalDate date, List<String> secids) {}
}
