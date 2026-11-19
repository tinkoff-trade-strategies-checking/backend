package ru.tinvest.trainer.marketdata.importer;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Дневной ряд RUONIA с сайта Банка России ({@code cbr.ru/hd_base/ruonia/dynamics}, HTML-таблица). В ISS ставки RUONIA
 * нет (проверено 19.11.2026: поиск ISS находит только фьючерсы на RUONIA).
 *
 * <p>Фильтр периода — по «Дате ставки» (первый столбец); {@code UniDbQuery.Posted=True} — только признак отправки
 * формы, без него ЦБ отдаёт период по умолчанию (проверено 19.11.2026). Ставка за дату D публикуется в D+1, поэтому за
 * последние 1–2 рабочих дня перед запуском её может ещё не быть. В таблицу идёт ставка по её дате, без
 * {@code available_at}: {@code core} в день d берёт {@code RUONIA_{d-1}} и сам переносит последнюю известную ставку на
 * выходные и праздники ({@code matchRuoniaRates}), заглядывания вперёд нет. На праздничные даты ЦБ ставку не
 * публикует (например, 30–31.12.2024 и 1–8.01.2025 в ответе нет).
 */
final class CbrRuonia {

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final Pattern ROW =
            Pattern.compile("<tr>\\s*<td>(\\d{2}\\.\\d{2}\\.\\d{4})</td>\\s*<td[^>]*>\\s*(\\d+(?:,\\d+)?)\\s*</td>");

    private final HttpFetcher fetcher;
    private final String baseUrl;
    private final int emptyRetries;

    CbrRuonia(HttpFetcher fetcher, String baseUrl, int emptyRetries) {
        this.fetcher = fetcher;
        this.baseUrl = baseUrl;
        this.emptyRetries = emptyRetries;
    }

    /** Ставки (% годовых, масштаб 6) по датам ставки за период {@code from}…{@code to} включительно. */
    Map<LocalDate, BigDecimal> rates(LocalDate from, LocalDate to) {
        URI uri = URI.create(baseUrl + "?UniDbQuery.Posted=True&UniDbQuery.From=" + DMY.format(from) + "&UniDbQuery.To="
                + DMY.format(to));
        return fetcher.get(uri, CbrRuonia::parse, Map::isEmpty, emptyRetries);
    }

    static Map<LocalDate, BigDecimal> parse(String html) {
        Map<LocalDate, BigDecimal> rates = new TreeMap<>();
        Matcher m = ROW.matcher(html);
        while (m.find()) {
            rates.put(
                    LocalDate.parse(m.group(1), DMY),
                    new BigDecimal(m.group(2).replace(',', '.')).setScale(IssParsers.SCALE));
        }
        if (rates.isEmpty() && !html.contains("class=\"data\"")) {
            throw new ImportException("Страница ЦБ РФ без таблицы ставок");
        }
        return rates;
    }
}
