-- Справочник инструментов срочного рынка MOEX ФОРТС: фьючерсы и опционы (backend#91).
-- Денежные, ценовые и процентные величины хранятся как NUMERIC (INV-6).

CREATE TABLE marketdata.futures_spec (
    ticker             TEXT           PRIMARY KEY,
    name               TEXT           NOT NULL,
    underlying         TEXT           NOT NULL,
    contract_size      INTEGER        NOT NULL CHECK (contract_size > 0),
    price_step         NUMERIC(14, 4) NOT NULL CHECK (price_step > 0),
    step_price_rub     NUMERIC(14, 4) NOT NULL CHECK (step_price_rub > 0),
    initial_margin_rub NUMERIC(14, 2) NOT NULL CHECK (initial_margin_rub > 0),
    last_price         NUMERIC(14, 4) NOT NULL CHECK (last_price > 0),
    expiration_date    DATE           NOT NULL,
    settlement_type    TEXT           NOT NULL CHECK (settlement_type IN ('CASH', 'DELIVERY')),
    currency           TEXT           NOT NULL DEFAULT 'RUB'
);

CREATE UNIQUE INDEX idx_futures_spec_upper_ticker
    ON marketdata.futures_spec (UPPER(ticker));

CREATE INDEX idx_futures_spec_expiration
    ON marketdata.futures_spec (expiration_date, ticker);

INSERT INTO marketdata.futures_spec (
    ticker, name, underlying, contract_size, price_step, step_price_rub,
    initial_margin_rub, last_price, expiration_date, settlement_type, currency
) VALUES
('SiH7', 'Фьючерс на курс USD/RUB (Si-3.27)', 'USD/RUB', 1000, 1.0000, 1.0000, 14500.00, 95400.0000, '2027-03-18', 'CASH', 'RUB'),
('RIH7', 'Фьючерс на индекс РТС (RTS-3.27)', 'RTSI', 1, 10.0000, 19.0800, 28500.00, 102500.0000, '2027-03-18', 'CASH', 'RUB'),
('MXH7', 'Фьючерс на индекс МосБиржи (MIX-3.27)', 'IMOEX', 100, 25.0000, 25.0000, 42000.00, 285400.0000, '2027-03-18', 'CASH', 'RUB'),
('BRF7', 'Фьючерс на нефть Brent (BR-1.27)', 'ICE Brent', 10, 0.0100, 9.5400, 11800.00, 76.4000, '2027-01-04', 'CASH', 'RUB'),
('GDH7', 'Фьючерс на золото (GOLD-3.27)', 'XAU/USD', 1, 0.1000, 9.5400, 24600.00, 2685.5000, '2027-03-19', 'CASH', 'RUB'),
('SRH7', 'Фьючерс на акции Сбербанка (SBRF-3.27)', 'SBER', 100, 1.0000, 1.0000, 4850.00, 28450.0000, '2027-03-18', 'DELIVERY', 'RUB'),
('GZH7', 'Фьючерс на акции Газпрома (GAZR-3.27)', 'GAZP', 100, 1.0000, 1.0000, 2950.00, 13820.0000, '2027-03-18', 'DELIVERY', 'RUB'),
('CRH7', 'Фьючерс на курс CNY/RUB (CNY-3.27)', 'CNY/RUB', 1000, 0.0010, 1.0000, 1950.00, 13.4850, '2027-03-18', 'CASH', 'RUB'),
('NGF7', 'Фьючерс на природный газ (NG-1.27)', 'Henry Hub NG', 100, 0.0010, 9.5400, 6400.00, 3.1850, '2027-01-27', 'CASH', 'RUB'),
('EUH7', 'Фьючерс на курс EUR/RUB (Eu-3.27)', 'EUR/RUB', 1000, 1.0000, 1.0000, 16200.00, 102800.0000, '2027-03-18', 'CASH', 'RUB'),
('LKH7', 'Фьючерс на акции ЛУКОЙЛа (LKOH-3.27)', 'LKOH', 10, 1.0000, 1.0000, 11200.00, 71400.0000, '2027-03-18', 'DELIVERY', 'RUB'),
('SVH7', 'Фьючерс на серебро (SILV-3.27)', 'XAG/USD', 10, 0.0100, 9.5400, 5100.00, 31.4500, '2027-03-19', 'CASH', 'RUB');

CREATE TABLE marketdata.option_spec (
    ticker             TEXT           PRIMARY KEY,
    name               TEXT           NOT NULL,
    underlying         TEXT           NOT NULL,
    option_type        TEXT           NOT NULL CHECK (option_type IN ('CALL', 'PUT')),
    strike             NUMERIC(14, 4) NOT NULL CHECK (strike > 0),
    expiration_date    DATE           NOT NULL,
    underlying_price   NUMERIC(14, 4) NOT NULL CHECK (underlying_price > 0),
    implied_vol_pct    NUMERIC(8, 4)  NOT NULL CHECK (implied_vol_pct > 0),
    risk_free_rate_pct NUMERIC(8, 4)  NOT NULL CHECK (risk_free_rate_pct >= 0),
    contract_size      INTEGER        NOT NULL CHECK (contract_size > 0),
    initial_margin_rub NUMERIC(14, 2) NOT NULL CHECK (initial_margin_rub > 0)
);

CREATE UNIQUE INDEX idx_option_spec_upper_ticker
    ON marketdata.option_spec (UPPER(ticker));

CREATE INDEX idx_option_spec_underlying_type
    ON marketdata.option_spec (underlying, option_type, strike);

INSERT INTO marketdata.option_spec (
    ticker, name, underlying, option_type, strike, expiration_date,
    underlying_price, implied_vol_pct, risk_free_rate_pct, contract_size, initial_margin_rub
) VALUES
('Si96000BC7', 'Call Si 96000 (мар. 2027)', 'SiH7', 'CALL', 96000.0000, '2027-03-18', 95400.0000, 18.5000, 18.0000, 1000, 4200.00),
('Si95000BO7', 'Put Si 95000 (мар. 2027)', 'SiH7', 'PUT', 95000.0000, '2027-03-18', 95400.0000, 19.2000, 18.0000, 1000, 3950.00),
('RI105000BC7', 'Call RTS 105000 (мар. 2027)', 'RIH7', 'CALL', 105000.0000, '2027-03-18', 102500.0000, 26.4000, 18.0000, 1, 7800.00),
('RI100000BO7', 'Put RTS 100000 (мар. 2027)', 'RIH7', 'PUT', 100000.0000, '2027-03-18', 102500.0000, 27.8000, 18.0000, 1, 7200.00),
('MX290000BC7', 'Call MIX 290000 (мар. 2027)', 'MXH7', 'CALL', 290000.0000, '2027-03-18', 285400.0000, 24.1000, 18.0000, 100, 11500.00),
('MX280000BO7', 'Put MIX 280000 (мар. 2027)', 'MXH7', 'PUT', 280000.0000, '2027-03-18', 285400.0000, 25.3000, 18.0000, 100, 10800.00),
('BR78BA7', 'Call Brent 78 (янв. 2027)', 'BRF7', 'CALL', 78.0000, '2027-01-04', 76.4000, 32.5000, 18.0000, 10, 3400.00),
('BR75BM7', 'Put Brent 75 (янв. 2027)', 'BRF7', 'PUT', 75.0000, '2027-01-04', 76.4000, 34.0000, 18.0000, 10, 3250.00),
('GD2700BC7', 'Call Gold 2700 (мар. 2027)', 'GDH7', 'CALL', 2700.0000, '2027-03-19', 2685.5000, 16.8000, 18.0000, 1, 6900.00),
('GD2650BO7', 'Put Gold 2650 (мар. 2027)', 'GDH7', 'PUT', 2650.0000, '2027-03-19', 2685.5000, 17.5000, 18.0000, 1, 6500.00),
('SR29000BC7', 'Call SBER 29000 (мар. 2027)', 'SRH7', 'CALL', 29000.0000, '2027-03-18', 28450.0000, 23.5000, 18.0000, 100, 1650.00),
('SR28000BO7', 'Put SBER 28000 (мар. 2027)', 'SRH7', 'PUT', 28000.0000, '2027-03-18', 28450.0000, 24.6000, 18.0000, 100, 1580.00);
