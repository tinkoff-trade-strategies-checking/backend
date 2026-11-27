package ru.tinvest.trainer.marketdata;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Публичный API модуля {@code marketdata}: чтение point-in-time фундаментальных показателей бумаг MOEX (INV-1, INV-9).
 */
@Service
public class SecurityFundamentals {

    private static final String COLUMNS = "ticker, report_date, available_at, period_type, currency, "
            + "market_cap_rub, pe_ratio, pb_ratio, ps_ratio, ev_ebitda, "
            + "roe, roa, gross_margin, operating_margin, net_margin, revenue_growth_yoy, eps_growth_yoy, "
            + "debt_to_equity, current_ratio, eps, book_value_per_share, "
            + "dividend_yield, payout_ratio, free_float, beta";

    private final JdbcClient jdbc;

    SecurityFundamentals(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Последний доступный срез фундаментальных показателей по тикеру (для экрана запуска / каталога бумаг). */
    public Optional<SecurityFundamental> findLatest(String ticker) {
        String normalized = normalizeTicker(ticker);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM marketdata.security_fundamentals "
                        + "WHERE ticker = :ticker "
                        + "ORDER BY available_at DESC, report_date DESC "
                        + "LIMIT 1")
                .param("ticker", normalized)
                .query(SecurityFundamentals::mapRow)
                .optional();
    }

    /** Последний доступный срез фундаментальных показателей по всем тикерам в базе (для скринера MOEX). */
    public Map<String, SecurityFundamental> findAllLatest() {
        List<SecurityFundamental> rows = jdbc.sql("SELECT DISTINCT ON (ticker) " + COLUMNS
                        + " FROM marketdata.security_fundamentals "
                        + "ORDER BY ticker, available_at DESC, report_date DESC")
                .query(SecurityFundamentals::mapRow)
                .list();
        Map<String, SecurityFundamental> byTicker = new LinkedHashMap<>();
        for (SecurityFundamental row : rows) {
            byTicker.put(row.ticker(), row);
        }
        return byTicker;
    }

    /**
     * Point-in-time срезы на дату {@code asOfDate} для набора тикеров (INV-1): для каждого тикера выбирается самый
     * свежий срез с {@code available_at <= asOfDate}. Будущая отчётность никогда не возвращается.
     */
    public Map<String, SecurityFundamental> findLatestAsOf(Collection<String> tickers, LocalDate asOfDate) {
        List<String> normalized = tickers.stream()
                .map(SecurityFundamentals::normalizeTicker)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
        if (normalized.isEmpty()) {
            return Map.of();
        }
        List<SecurityFundamental> rows = jdbc.sql("SELECT DISTINCT ON (ticker) " + COLUMNS
                        + " FROM marketdata.security_fundamentals "
                        + "WHERE ticker IN (:tickers) AND available_at <= :asOfDate "
                        + "ORDER BY ticker, available_at DESC, report_date DESC")
                .param("tickers", normalized)
                .param("asOfDate", asOfDate)
                .query(SecurityFundamentals::mapRow)
                .list();
        Map<String, SecurityFundamental> byTicker = new LinkedHashMap<>();
        for (SecurityFundamental row : rows) {
            byTicker.put(row.ticker(), row);
        }
        return byTicker;
    }

    public static String normalizeTicker(String ticker) {
        return ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT);
    }

    private static SecurityFundamental mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new SecurityFundamental(
                rs.getString("ticker"),
                rs.getObject("report_date", LocalDate.class),
                rs.getObject("available_at", LocalDate.class),
                rs.getString("period_type"),
                rs.getString("currency"),
                rs.getBigDecimal("market_cap_rub"),
                rs.getBigDecimal("pe_ratio"),
                rs.getBigDecimal("pb_ratio"),
                rs.getBigDecimal("ps_ratio"),
                rs.getBigDecimal("ev_ebitda"),
                rs.getBigDecimal("roe"),
                rs.getBigDecimal("roa"),
                rs.getBigDecimal("gross_margin"),
                rs.getBigDecimal("operating_margin"),
                rs.getBigDecimal("net_margin"),
                rs.getBigDecimal("revenue_growth_yoy"),
                rs.getBigDecimal("eps_growth_yoy"),
                rs.getBigDecimal("debt_to_equity"),
                rs.getBigDecimal("current_ratio"),
                rs.getBigDecimal("eps"),
                rs.getBigDecimal("book_value_per_share"),
                rs.getBigDecimal("dividend_yield"),
                rs.getBigDecimal("payout_ratio"),
                rs.getBigDecimal("free_float"),
                rs.getBigDecimal("beta"));
    }
}
