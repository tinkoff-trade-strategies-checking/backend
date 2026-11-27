package ru.tinvest.trainer.marketdata;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Публичный API модуля {@code marketdata}: справочник облигаций (ОФЗ и корпоративные) и БПИФ/ETF, валют и
 * драгметаллов MOEX (backend#90, backend#92).
 */
@Service
public class InstrumentsCatalog {

    private static final String BOND_COLUMNS = "ticker, name, issuer, bond_type, face_value_rub, coupon_rate_pct, "
            + "coupon_value_rub, coupon_period_days, next_coupon_date, maturity_date, offer_date, "
            + "accrued_interest_rub, ytm_pct, duration_years, amortized, price_pct, currency";

    private static final String ETF_COLUMNS = "ticker, name, asset_class, category, underlying_index, "
            + "tracks_ruonia, expense_ratio_pct, nav_price_rub, currency, description";

    private static final String FUTURES_COLUMNS = "ticker, name, underlying, contract_size, price_step, "
            + "step_price_rub, initial_margin_rub, last_price, expiration_date, settlement_type, currency";

    private static final String OPTION_COLUMNS = "ticker, name, underlying, option_type, strike, expiration_date, "
            + "underlying_price, implied_vol_pct, risk_free_rate_pct, contract_size, initial_margin_rub";

    private static final String DIVIDEND_COLUMNS = "id, ticker, issuer_name, record_date, ex_dividend_date, "
            + "last_buy_date, payment_date, dividend_per_share_rub, yield_pct, estimated_gap_pct, currency";

    private static final String MARKET_EVENT_COLUMNS =
            "id, event_date, event_type, ticker, title, importance, description";

    private static final long CACHE_TTL_NANOS = Duration.ofMinutes(5).toNanos();

    private record CachedValue(Object value, long expiresAtNanos) {}

    private final JdbcClient jdbc;
    private final Map<String, CachedValue> cache = new ConcurrentHashMap<>();

    InstrumentsCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @SuppressWarnings("unchecked")
    private <T> T cached(String key, Supplier<T> loader) {
        long now = System.nanoTime();
        CachedValue entry = cache.get(key);
        if (entry != null && entry.expiresAtNanos() > now) {
            return (T) entry.value();
        }
        T loaded = loader.get();
        cache.put(key, new CachedValue(loaded, now + CACHE_TTL_NANOS));
        return loaded;
    }

    public List<BondSpec> listBonds() {
        return cached(
                "bonds",
                () -> jdbc.sql("SELECT " + BOND_COLUMNS
                                + " FROM marketdata.bond_spec ORDER BY bond_type DESC, maturity_date ASC, ticker ASC")
                        .query(InstrumentsCatalog::mapBond)
                        .list());
    }

    public Optional<BondSpec> findBond(String ticker) {
        String normalized = SecurityFundamentals.normalizeTicker(ticker);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT " + BOND_COLUMNS + " FROM marketdata.bond_spec WHERE ticker = :ticker")
                .param("ticker", normalized)
                .query(InstrumentsCatalog::mapBond)
                .optional();
    }

    public List<EtfSpec> listEtfs() {
        return cached(
                "etfs",
                () -> jdbc.sql(
                                "SELECT " + ETF_COLUMNS
                                        + " FROM marketdata.etf_spec ORDER BY tracks_ruonia DESC, asset_class ASC, category ASC, ticker ASC")
                        .query(InstrumentsCatalog::mapEtf)
                        .list());
    }

    public List<FuturesSpec> listFutures() {
        return cached(
                "futures",
                () -> jdbc.sql("SELECT " + FUTURES_COLUMNS
                                + " FROM marketdata.futures_spec ORDER BY expiration_date ASC, ticker ASC")
                        .query(InstrumentsCatalog::mapFutures)
                        .list());
    }

    public Optional<FuturesSpec> findFutures(String ticker) {
        String normalized = SecurityFundamentals.normalizeTicker(ticker);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT " + FUTURES_COLUMNS + " FROM marketdata.futures_spec WHERE UPPER(ticker) = :ticker")
                .param("ticker", normalized)
                .query(InstrumentsCatalog::mapFutures)
                .optional();
    }

    public List<OptionSpec> listOptions() {
        return cached(
                "options",
                () -> jdbc.sql(
                                "SELECT " + OPTION_COLUMNS
                                        + " FROM marketdata.option_spec ORDER BY underlying ASC, option_type ASC, strike ASC, ticker ASC")
                        .query(InstrumentsCatalog::mapOption)
                        .list());
    }

    public Optional<OptionSpec> findOption(String ticker) {
        String normalized = SecurityFundamentals.normalizeTicker(ticker);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT " + OPTION_COLUMNS + " FROM marketdata.option_spec WHERE UPPER(ticker) = :ticker")
                .param("ticker", normalized)
                .query(InstrumentsCatalog::mapOption)
                .optional();
    }

    public List<DividendEventSpec> listDividends() {
        return cached(
                "dividends",
                () -> jdbc.sql("SELECT " + DIVIDEND_COLUMNS
                                + " FROM marketdata.dividend_event ORDER BY record_date DESC, ticker ASC, id ASC")
                        .query(InstrumentsCatalog::mapDividend)
                        .list());
    }

    public List<DividendEventSpec> listDividendsByTicker(String ticker) {
        String normalized = SecurityFundamentals.normalizeTicker(ticker);
        if (normalized.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + DIVIDEND_COLUMNS
                        + " FROM marketdata.dividend_event WHERE UPPER(ticker) = :ticker"
                        + " ORDER BY record_date DESC, id ASC")
                .param("ticker", normalized)
                .query(InstrumentsCatalog::mapDividend)
                .list();
    }

    public Optional<DividendEventSpec> findLatestDividend(String ticker) {
        String normalized = SecurityFundamentals.normalizeTicker(ticker);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT " + DIVIDEND_COLUMNS
                        + " FROM marketdata.dividend_event WHERE UPPER(ticker) = :ticker"
                        + " ORDER BY record_date DESC, id DESC LIMIT 1")
                .param("ticker", normalized)
                .query(InstrumentsCatalog::mapDividend)
                .optional();
    }

    public List<MarketEventSpec> listMarketEvents(@Nullable String eventType, @Nullable String ticker) {
        if ((eventType == null || eventType.isBlank()) && (ticker == null || ticker.isBlank())) {
            return cached(
                    "market_events_all",
                    () -> jdbc.sql("SELECT " + MARKET_EVENT_COLUMNS
                                    + " FROM marketdata.market_event ORDER BY event_date ASC, id ASC")
                            .query(InstrumentsCatalog::mapMarketEvent)
                            .list());
        }
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(MARKET_EVENT_COLUMNS)
                .append(" FROM marketdata.market_event WHERE 1=1");
        if (eventType != null && !eventType.isBlank()) {
            sql.append(" AND event_type = :eventType");
        }
        if (ticker != null && !ticker.isBlank()) {
            sql.append(" AND UPPER(ticker) = :ticker");
        }
        sql.append(" ORDER BY event_date ASC, id ASC");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString());
        if (eventType != null && !eventType.isBlank()) {
            spec = spec.param("eventType", eventType);
        }
        if (ticker != null && !ticker.isBlank()) {
            spec = spec.param("ticker", ticker);
        }
        return spec.query(InstrumentsCatalog::mapMarketEvent).list();
    }

    private static BondSpec mapBond(ResultSet rs, int rowNum) throws SQLException {
        return new BondSpec(
                rs.getString("ticker"),
                rs.getString("name"),
                rs.getString("issuer"),
                rs.getString("bond_type"),
                rs.getBigDecimal("face_value_rub"),
                rs.getBigDecimal("coupon_rate_pct"),
                rs.getBigDecimal("coupon_value_rub"),
                rs.getInt("coupon_period_days"),
                rs.getObject("next_coupon_date", LocalDate.class),
                rs.getObject("maturity_date", LocalDate.class),
                rs.getObject("offer_date", LocalDate.class),
                rs.getBigDecimal("accrued_interest_rub"),
                rs.getBigDecimal("ytm_pct"),
                rs.getBigDecimal("duration_years"),
                rs.getBoolean("amortized"),
                rs.getBigDecimal("price_pct"),
                rs.getString("currency"));
    }

    private static EtfSpec mapEtf(ResultSet rs, int rowNum) throws SQLException {
        return new EtfSpec(
                rs.getString("ticker"),
                rs.getString("name"),
                rs.getString("asset_class"),
                rs.getString("category"),
                rs.getString("underlying_index"),
                rs.getBoolean("tracks_ruonia"),
                rs.getBigDecimal("expense_ratio_pct"),
                rs.getBigDecimal("nav_price_rub"),
                rs.getString("currency"),
                rs.getString("description"));
    }

    private static FuturesSpec mapFutures(ResultSet rs, int rowNum) throws SQLException {
        return new FuturesSpec(
                rs.getString("ticker"),
                rs.getString("name"),
                rs.getString("underlying"),
                rs.getInt("contract_size"),
                rs.getBigDecimal("price_step"),
                rs.getBigDecimal("step_price_rub"),
                rs.getBigDecimal("initial_margin_rub"),
                rs.getBigDecimal("last_price"),
                rs.getObject("expiration_date", LocalDate.class),
                rs.getString("settlement_type"),
                rs.getString("currency"));
    }

    private static OptionSpec mapOption(ResultSet rs, int rowNum) throws SQLException {
        return new OptionSpec(
                rs.getString("ticker"),
                rs.getString("name"),
                rs.getString("underlying"),
                rs.getString("option_type"),
                rs.getBigDecimal("strike"),
                rs.getObject("expiration_date", LocalDate.class),
                rs.getBigDecimal("underlying_price"),
                rs.getBigDecimal("implied_vol_pct"),
                rs.getBigDecimal("risk_free_rate_pct"),
                rs.getInt("contract_size"),
                rs.getBigDecimal("initial_margin_rub"));
    }

    private static DividendEventSpec mapDividend(ResultSet rs, int rowNum) throws SQLException {
        return new DividendEventSpec(
                rs.getLong("id"),
                rs.getString("ticker"),
                rs.getString("issuer_name"),
                rs.getObject("record_date", LocalDate.class),
                rs.getObject("ex_dividend_date", LocalDate.class),
                rs.getObject("last_buy_date", LocalDate.class),
                rs.getObject("payment_date", LocalDate.class),
                rs.getBigDecimal("dividend_per_share_rub"),
                rs.getBigDecimal("yield_pct"),
                rs.getBigDecimal("estimated_gap_pct"),
                rs.getString("currency"));
    }

    private static MarketEventSpec mapMarketEvent(ResultSet rs, int rowNum) throws SQLException {
        return new MarketEventSpec(
                rs.getLong("id"),
                rs.getObject("event_date", LocalDate.class),
                rs.getString("event_type"),
                rs.getString("ticker"),
                rs.getString("title"),
                rs.getString("importance"),
                rs.getString("description"));
    }
}
