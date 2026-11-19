-- Ручная сессия: параметры запуска и план (снапшот, период, категория, бумаги, капитал) не меняются после создания
-- (INV-5, воспроизводимость НФТ-3); меняется только статус. Внешних ключей в чужие схемы нет: пользователя и снапшот
-- проверяет код через публичные API identity и marketdata.
CREATE TABLE sessions.session (
    id              UUID PRIMARY KEY,
    user_id         UUID             NOT NULL,
    blind           BOOLEAN          NOT NULL,
    status          TEXT             NOT NULL DEFAULT 'running'
        CHECK (status IN ('running', 'finished_end', 'finished_early', 'bankrupt')),
    trader_kind     TEXT             NOT NULL CHECK (trader_kind = 'human'),
    duration_days   INTEGER          NOT NULL CHECK (duration_days > 0),
    timeframe       TEXT             NOT NULL CHECK (timeframe IN ('1m', '5m', '10m', '15m', '1h', '1d')),
    regime          TEXT             NOT NULL CHECK (regime IN ('calm', 'medium', 'stress')),
    start_mode      TEXT             NOT NULL CHECK (start_mode IN ('random', 'custom')),
    snapshot_id     UUID             NOT NULL,
    period_start    DATE             NOT NULL,
    period_end      DATE             NOT NULL,
    trading_days    INTEGER          NOT NULL,
    bars_count      INTEGER          NOT NULL,
    -- служебные метрики окна: клиенту до конца сессии не отдаются (INV-1)
    sigma           DOUBLE PRECISION NOT NULL,
    mdd             DOUBLE PRECISION NOT NULL,
    p33             DOUBLE PRECISION NOT NULL,
    p80             DOUBLE PRECISION NOT NULL,
    initial_capital NUMERIC(20, 2)   NOT NULL CHECK (initial_capital > 0),
    created_at      TIMESTAMPTZ      NOT NULL DEFAULT now()
);

CREATE INDEX session_user_created_idx ON sessions.session (user_id, created_at DESC);

-- Бумаги сессии: paper_index — индекс бумаги в сессии (instrument_index ядра, MarketSpec.instruments); порядок случайный,
-- не по тикеру. Для слепого вида (INV-7) наружу идёт только индекс.
CREATE TABLE sessions.session_paper (
    session_id  UUID    NOT NULL REFERENCES sessions.session (id),
    paper_index INTEGER NOT NULL CHECK (paper_index >= 0),
    paper_id    UUID    NOT NULL,
    secid       TEXT    NOT NULL,
    sector      TEXT    NOT NULL,
    lot_size    BIGINT  NOT NULL CHECK (lot_size > 0),
    price_step  NUMERIC(18, 6) NOT NULL CHECK (price_step > 0),
    PRIMARY KEY (session_id, paper_index),
    UNIQUE (session_id, paper_id)
);

-- Журнал команд (INV-8): состояние сессии восстанавливается проигрыванием журнала. Заполняют задачи про заявки.
-- command — рублёвая команда в JSON (PlaceOrder, CancelOrder, ReplaceOrder); цены слепого режима в журнал не пишутся.
CREATE TABLE sessions.command_journal (
    session_id    UUID        NOT NULL REFERENCES sessions.session (id),
    seq           BIGINT      NOT NULL CHECK (seq >= 0),
    step          BIGINT      NOT NULL,
    seen_step     BIGINT      NOT NULL,
    command_type  TEXT        NOT NULL CHECK (command_type IN ('place', 'cancel', 'replace')),
    command       JSONB       NOT NULL,
    accepted      BOOLEAN     NOT NULL,
    reject_reason TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (session_id, seq)
);

-- Параметры и план сессии не меняются: допустим только переход статуса.
CREATE FUNCTION sessions.reject_session_plan_change() RETURNS trigger AS $$
BEGIN
    IF (to_jsonb(NEW) - 'status') IS DISTINCT FROM (to_jsonb(OLD) - 'status') THEN
        RAISE EXCEPTION 'INV-5: параметры и план сессии неизменяемы'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER session_plan_immutable
    BEFORE UPDATE ON sessions.session
    FOR EACH ROW EXECUTE FUNCTION sessions.reject_session_plan_change();

CREATE FUNCTION sessions.reject_immutable_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'INV-5/INV-8: % в sessions.% запрещён', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER session_paper_immutable
    BEFORE UPDATE OR DELETE ON sessions.session_paper
    FOR EACH ROW EXECUTE FUNCTION sessions.reject_immutable_change();

-- Журнал только дописывается.
CREATE TRIGGER command_journal_append_only
    BEFORE UPDATE OR DELETE ON sessions.command_journal
    FOR EACH ROW EXECUTE FUNCTION sessions.reject_immutable_change();
