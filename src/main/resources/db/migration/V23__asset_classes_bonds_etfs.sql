-- Справочник облигаций MOEX (ОФЗ и корпоративные выпуски) и БПИФ/ETF, валют и драгметаллов (backend#90, backend#92).
-- Денежные и процентные величины хранятся как NUMERIC (INV-6).

CREATE TABLE marketdata.bond_spec (
    ticker               TEXT           PRIMARY KEY,
    name                 TEXT           NOT NULL,
    issuer               TEXT           NOT NULL,
    bond_type            TEXT           NOT NULL CHECK (bond_type IN ('OFZ', 'CORPORATE')),
    face_value_rub       NUMERIC(14, 2) NOT NULL CHECK (face_value_rub > 0),
    coupon_rate_pct      NUMERIC(8, 4)  NOT NULL CHECK (coupon_rate_pct >= 0),
    coupon_value_rub     NUMERIC(12, 4) NOT NULL CHECK (coupon_value_rub >= 0),
    coupon_period_days   INTEGER        NOT NULL CHECK (coupon_period_days > 0),
    next_coupon_date     DATE           NOT NULL,
    maturity_date        DATE           NOT NULL,
    offer_date           DATE,
    accrued_interest_rub NUMERIC(12, 4) NOT NULL CHECK (accrued_interest_rub >= 0),
    ytm_pct              NUMERIC(8, 4)  NOT NULL,
    duration_years       NUMERIC(8, 4)  NOT NULL CHECK (duration_years >= 0),
    amortized            BOOLEAN        NOT NULL DEFAULT FALSE,
    price_pct            NUMERIC(10, 4) NOT NULL CHECK (price_pct > 0),
    currency             TEXT           NOT NULL DEFAULT 'RUB',
    CONSTRAINT chk_bond_spec_maturity_after_coupon CHECK (maturity_date >= next_coupon_date)
);

CREATE INDEX idx_bond_spec_type_maturity
    ON marketdata.bond_spec (bond_type, maturity_date);

INSERT INTO marketdata.bond_spec (
    ticker, name, issuer, bond_type, face_value_rub, coupon_rate_pct, coupon_value_rub,
    coupon_period_days, next_coupon_date, maturity_date, offer_date,
    accrued_interest_rub, ytm_pct, duration_years, amortized, price_pct, currency
) VALUES
-- ОФЗ (постоянный купон и флоатеры)
('SU26238RMFS4', 'ОФЗ 26238', 'Минфин РФ', 'OFZ', 1000.00, 7.1000, 35.4000, 182, '2027-06-02', '2041-05-15', NULL, 34.4300, 15.6000, 7.8500, FALSE, 54.8000, 'RUB'),
('SU26243RMFS4', 'ОФЗ 26243', 'Минфин РФ', 'OFZ', 1000.00, 9.8000, 48.8700, 182, '2027-06-02', '2038-05-19', NULL, 47.5300, 15.8500, 6.7200, FALSE, 68.4000, 'RUB'),
('SU26244RMFS2', 'ОФЗ 26244', 'Минфин РФ', 'OFZ', 1000.00, 11.2500, 56.1000, 182, '2027-03-24', '2034-03-15', NULL, 20.0400, 16.1000, 5.1800, FALSE, 79.2000, 'RUB'),
('SU26248RMFS3', 'ОФЗ 26248', 'Минфин РФ', 'OFZ', 1000.00, 12.2500, 61.0800, 182, '2027-06-02', '2040-05-16', NULL, 59.4000, 15.9500, 6.8400, FALSE, 80.5000, 'RUB'),
('SU26247RMFS5', 'ОФЗ 26247', 'Минфин РФ', 'OFZ', 1000.00, 12.2500, 61.0800, 182, '2027-05-26', '2039-05-11', NULL, 0.6700, 15.9000, 6.6500, FALSE, 81.1000, 'RUB'),
('SU26233RMFS5', 'ОФЗ 26233', 'Минфин РФ', 'OFZ', 1000.00, 6.1000, 30.4200, 182, '2027-01-27', '2035-07-18', NULL, 20.2200, 15.7500, 6.1000, FALSE, 56.9000, 'RUB'),
('SU26241RMFS8', 'ОФЗ 26241', 'Минфин РФ', 'OFZ', 1000.00, 9.5000, 47.3700, 182, '2027-05-26', '2032-11-17', NULL, 0.5200, 16.1500, 4.5500, FALSE, 76.8000, 'RUB'),
('SU26240RMFS0', 'ОФЗ 26240', 'Минфин РФ', 'OFZ', 1000.00, 7.0000, 34.9000, 182, '2027-02-10', '2036-07-30', NULL, 20.5200, 15.7000, 6.3500, FALSE, 59.4000, 'RUB'),
('SU29014RMFS6', 'ОФЗ 29014 (RUONIA)', 'Минфин РФ', 'OFZ', 1000.00, 18.5000, 46.1200, 91, '2027-03-24', '2028-03-22', NULL, 33.4500, 19.2000, 0.2500, FALSE, 99.6000, 'RUB'),
('SU29024RMFS5', 'ОФЗ 29024 (RUONIA)', 'Минфин РФ', 'OFZ', 1000.00, 18.6000, 46.3700, 91, '2027-01-27', '2035-04-18', NULL, 15.2900, 19.4000, 0.2500, FALSE, 97.9000, 'RUB'),
-- Корпоративные облигации (включая выпуски с офертой и амортизацией)
('RU000A107HG1', 'Сбербанк 001P-SBER46', 'ПАО Сбербанк', 'CORPORATE', 1000.00, 15.8000, 78.7800, 182, '2027-06-22', '2028-12-19', NULL, 67.5300, 17.4000, 1.8200, FALSE, 98.1000, 'RUB'),
('RU000A106T36', 'Газпром Капитал БО-002Р-07', 'Газпром Капитал', 'CORPORATE', 1000.00, 14.5000, 36.1500, 91, '2027-02-16', '2029-08-14', NULL, 4.3700, 17.6500, 2.2500, FALSE, 94.3000, 'RUB'),
('RU000A108EH4', 'РЖД 001P-28R', 'ОАО РЖД', 'CORPORATE', 1000.00, 16.2000, 13.3200, 30, '2026-12-15', '2029-05-10', NULL, 5.3300, 17.9000, 2.1000, FALSE, 98.8000, 'RUB'),
('RU000A105WV0', 'МТС 002P-01', 'ПАО МТС', 'CORPORATE', 1000.00, 15.1000, 37.6500, 91, '2027-03-04', '2028-03-02', '2027-09-02', 35.1700, 18.2000, 1.1800, FALSE, 97.4000, 'RUB'),
('RU000A107D58', 'Роснефть 002P-14', 'ПАО НК Роснефть', 'CORPORATE', 1000.00, 15.4000, 76.7900, 182, '2027-05-29', '2028-11-28', NULL, 75.9500, 17.5000, 1.7600, FALSE, 97.6000, 'RUB'),
('RU000A106UW3', 'ЛУКОЙЛ ЗО-31', 'ПАО ЛУКОЙЛ', 'CORPORATE', 1000.00, 13.9000, 69.3100, 182, '2027-05-06', '2031-05-06', NULL, 8.3800, 16.9500, 3.4500, FALSE, 91.5000, 'RUB'),
('RU000A107E81', 'Яндекс Финтех 001P-01', 'Яндекс', 'CORPORATE', 1000.00, 16.5000, 13.5600, 30, '2026-12-18', '2028-06-15', NULL, 4.0700, 18.4500, 0.9200, TRUE, 98.9000, 'RUB'),
('RU000A1084E6', 'Самолет БО-П14', 'ГК Самолет', 'CORPORATE', 1000.00, 19.2500, 15.8200, 30, '2026-12-20', '2029-02-20', '2027-08-20', 3.6900, 22.8000, 1.1500, TRUE, 94.2000, 'RUB');

CREATE TABLE marketdata.etf_spec (
    ticker            TEXT           PRIMARY KEY,
    name              TEXT           NOT NULL,
    asset_class       TEXT           NOT NULL CHECK (asset_class IN ('ETF', 'CURRENCY', 'METAL')),
    category          TEXT           NOT NULL CHECK (category IN ('LIQUIDITY', 'EQUITY_INDEX', 'BOND_INDEX', 'GOLD', 'CURRENCY')),
    underlying_index  TEXT           NOT NULL,
    tracks_ruonia     BOOLEAN        NOT NULL DEFAULT FALSE,
    expense_ratio_pct NUMERIC(6, 4)  NOT NULL CHECK (expense_ratio_pct >= 0),
    nav_price_rub     NUMERIC(14, 4) NOT NULL CHECK (nav_price_rub > 0),
    currency          TEXT           NOT NULL DEFAULT 'RUB',
    description       TEXT           NOT NULL
);

CREATE INDEX idx_etf_spec_class_category
    ON marketdata.etf_spec (asset_class, category);

INSERT INTO marketdata.etf_spec (
    ticker, name, asset_class, category, underlying_index,
    tracks_ruonia, expense_ratio_pct, nav_price_rub, currency, description
) VALUES
-- Фонды ликвидности / денежного рынка (под ставку RUONIA / RUSFAR)
('LQDT', 'ВИМ — Ликвидность', 'ETF', 'LIQUIDITY', 'RUSFAR / RUONIA', TRUE, 0.3900, 1.5420, 'RUB', 'Крупнейший БПИФ денежного рынка на сделки обратного РЕПО с ЦК; доходность близка к ставке RUONIA.'),
('SBMM', 'Первая — Фонд Сберегательный', 'ETF', 'LIQUIDITY', 'RUSFAR / RUONIA', TRUE, 0.2990, 15.8400, 'RUB', 'Фонд ликвидности УК «Первая» с ежедневным начислением доходности по ставкам РЕПО / RUONIA.'),
('AKMM', 'Альфа-Капитал Денежный рынок', 'ETF', 'LIQUIDITY', 'RUSFAR / RUONIA', TRUE, 0.6900, 142.3000, 'RUB', 'БПИФ денежного рынка УК «Альфа-Капитал» на краткосрочное РЕПО с КСУ под RUONIA.'),
('TMON', 'Т-Капитал Денежный рынок', 'ETF', 'LIQUIDITY', 'RUSFAR / RUONIA', TRUE, 1.0900, 146.1500, 'RUB', 'БПИФ денежного рынка Т-Капитал для парковки кэша под ставку овернайт RUONIA.'),
-- Индексные БПИФ акций MOEX
('TMOS', 'Т-Капитал Индекс МосБиржи', 'ETF', 'EQUITY_INDEX', 'IMOEX', FALSE, 0.7900, 6.8200, 'RUB', 'БПИФ на широкий рынок российских акций, повторяющий индекс МосБиржи полной доходности.'),
('SBMX', 'Первая — Топ Российских акций', 'ETF', 'EQUITY_INDEX', 'MCFTR', FALSE, 0.8000, 21.4500, 'RUB', 'БПИФ на индекс МосБиржи брутто (MCFTR) с реинвестированием дивидендов.'),
('EQMX', 'ВИМ — Индекс МосБиржи', 'ETF', 'EQUITY_INDEX', 'MCFTR', FALSE, 0.6700, 138.9000, 'RUB', 'Индексный БПИФ на акции крупных российских компаний с низкой комиссией управления.'),
-- БПИФ облигаций
('OBLG', 'Первая — Российские облигации', 'ETF', 'BOND_INDEX', 'RUCBTRNS', FALSE, 0.7500, 168.4000, 'RUB', 'БПИФ на диверсифицированный портфель корпоративных облигаций российских эмитентов.'),
('TBRU', 'Т-Капитал Российские облигации', 'ETF', 'BOND_INDEX', 'RUCBTRNS', FALSE, 0.9900, 7.1200, 'RUB', 'БПИФ на рублёвые корпоративные облигации с регулярным реинвестированием купонов.'),
('SGBB', 'Первая — Государственные облигации', 'ETF', 'BOND_INDEX', 'RGBITR', FALSE, 0.6000, 12.6500, 'RUB', 'БПИФ на индекс государственных облигаций РФ (ОФЗ) полной доходности RGBITR.'),
-- Золото (БПИФ и биржевой спот)
('TGLD', 'Т-Капитал Золото', 'ETF', 'GOLD', 'RUGOLD', FALSE, 0.7400, 12.8500, 'RUB', 'БПИФ на физическое золото в хранилищах РФ с расчётами в рублях по индексу RUGOLD.'),
('GOLD', 'ВИМ — Золото', 'ETF', 'GOLD', 'RUGOLD', FALSE, 0.6600, 2.4800, 'RUB', 'БПИФ на золото, отслеживающий биржевую цену драгоценного металла в рублях.'),
('GLDRUB_TOM', 'Золото (спот MOEX, 1 г)', 'METAL', 'GOLD', 'GLDRUB_TOM', FALSE, 0.0000, 8420.5000, 'RUB', 'Биржевой инструмент на физическое золото на валютном рынке и рынке драгметаллов MOEX (лот 1 г).'),
-- Валютная секция MOEX
('CNYRUB_TOM', 'Китайский юань / Рубль (TOM)', 'CURRENCY', 'CURRENCY', 'CNY/RUB', FALSE, 0.0000, 13.4500, 'RUB', 'Биржевая валютная пара CNY/RUB с расчётами завтра (TOM) на валютной секции MOEX.');
