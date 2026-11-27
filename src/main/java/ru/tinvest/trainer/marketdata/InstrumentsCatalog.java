package ru.tinvest.trainer.marketdata;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
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

    private final JdbcClient jdbc;

    InstrumentsCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<BondSpec> listBonds() {
        return jdbc.sql("SELECT " + BOND_COLUMNS
                        + " FROM marketdata.bond_spec ORDER BY bond_type DESC, maturity_date ASC, ticker ASC")
                .query(InstrumentsCatalog::mapBond)
                .list();
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
        return jdbc.sql(
                        "SELECT " + ETF_COLUMNS
                                + " FROM marketdata.etf_spec ORDER BY tracks_ruonia DESC, asset_class ASC, category ASC, ticker ASC")
                .query(InstrumentsCatalog::mapEtf)
                .list();
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
}
