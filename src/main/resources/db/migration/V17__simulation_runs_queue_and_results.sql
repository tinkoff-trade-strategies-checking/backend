-- Очередь прогонов алгоритмов, бумаги, неизменяемые результаты и поток SSE-событий (UC-14, UC-18, INV-4, INV-5, INV-7).
-- Совместима с V4 (триггер run_references_immutable и базовый INSERT). Внешних ключей в чужие схемы нет.

ALTER TABLE simulation.run
    ADD COLUMN user_id           UUID             NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000'::uuid,
    ADD COLUMN blind             BOOLEAN          NOT NULL DEFAULT FALSE,
    ADD COLUMN status            TEXT             NOT NULL DEFAULT 'queued'
        CHECK (status IN ('queued', 'running', 'completed', 'bankrupt', 'invalid', 'failed')),
    ADD COLUMN duration_days     INTEGER          NOT NULL DEFAULT 60 CHECK (duration_days > 0),
    ADD COLUMN timeframe         TEXT             NOT NULL DEFAULT '1d'
        CHECK (timeframe IN ('1m', '5m', '10m', '15m', '1h', '1d')),
    ADD COLUMN regime            TEXT             NOT NULL DEFAULT 'medium'
        CHECK (regime IN ('calm', 'medium', 'stress')),
    ADD COLUMN start_mode        TEXT             NOT NULL DEFAULT 'random'
        CHECK (start_mode IN ('random', 'custom')),
    ADD COLUMN period_start      DATE             NOT NULL DEFAULT DATE '2020-01-01',
    ADD COLUMN period_end        DATE             NOT NULL DEFAULT DATE '2020-03-31',
    ADD COLUMN trading_days      INTEGER          NOT NULL DEFAULT 0,
    ADD COLUMN bars_count        INTEGER          NOT NULL DEFAULT 0,
    ADD COLUMN sigma             DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN mdd               DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN p33               DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN p80               DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN initial_capital   NUMERIC(20, 2)   NOT NULL DEFAULT 1000000.00 CHECK (initial_capital > 0),
    ADD COLUMN warmup_bars       INTEGER          NOT NULL DEFAULT 200 CHECK (warmup_bars >= 0),
    ADD COLUMN commission_rate   NUMERIC(12, 8)   NOT NULL DEFAULT 0.003,
    ADD COLUMN exchange_fee_rate NUMERIC(12, 8)   NOT NULL DEFAULT 0.0001,
    ADD COLUMN spread            NUMERIC(12, 8)   NOT NULL DEFAULT 0.001,
    ADD COLUMN slippage_kappa    NUMERIC(12, 8)   NOT NULL DEFAULT 0.1,
    ADD COLUMN liquidity_share   NUMERIC(12, 8)   NOT NULL DEFAULT 0.1,
    ADD COLUMN param_overrides     JSONB            NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN done_steps        BIGINT           NOT NULL DEFAULT 0 CHECK (done_steps >= 0),
    ADD COLUMN total_steps       BIGINT           CHECK (total_steps IS NULL OR total_steps >= 0),
    ADD COLUMN last_step         BIGINT,
    ADD COLUMN played_days       INTEGER          CHECK (played_days IS NULL OR played_days >= 0),
    ADD COLUMN digest            BIGINT,
    ADD COLUMN final_level       TEXT             CHECK (final_level IS NULL OR final_level IN ('OK', 'WARNING', 'ERROR')),
    ADD COLUMN net_return_pct    TEXT,
    ADD COLUMN final_equity      NUMERIC(20, 2),
    ADD COLUMN error_code        TEXT,
    ADD COLUMN finished_at       TIMESTAMPTZ;

CREATE INDEX run_user_created_idx ON simulation.run (user_id, created_at DESC, id DESC);

CREATE INDEX run_active_idx ON simulation.run (status, created_at ASC) WHERE status IN ('queued', 'running');

-- Бумаги прогона: paper_index — индекс инструмента в прогоне (порядок для blind не по тикеру, INV-7).
CREATE TABLE simulation.run_paper (
    run_id      UUID           NOT NULL REFERENCES simulation.run (id),
    paper_index INTEGER        NOT NULL CHECK (paper_index >= 0),
    paper_id    UUID           NOT NULL,
    secid       TEXT           NOT NULL,
    sector      TEXT           NOT NULL,
    lot_size    BIGINT         NOT NULL CHECK (lot_size > 0),
    price_step  NUMERIC(18, 6) NOT NULL CHECK (price_step > 0),
    name        TEXT           NOT NULL DEFAULT '',
    PRIMARY KEY (run_id, paper_index),
    UNIQUE (run_id, paper_id)
);

-- Неизменяемый артефакт завершённого прогона (INV-4, INV-5).
CREATE TABLE simulation.run_result (
    run_id       UUID        PRIMARY KEY REFERENCES simulation.run (id),
    report       JSONB       NOT NULL,
    journal      JSONB       NOT NULL DEFAULT '[]'::jsonb,
    diagnostics  JSONB       NOT NULL DEFAULT '[]'::jsonb,
    repeat_match BOOLEAN,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Поток SSE-событий прогона с монотонным event_id для догона по Last-Event-ID.
CREATE TABLE simulation.run_event (
    run_id     UUID        NOT NULL REFERENCES simulation.run (id),
    event_id   BIGINT      NOT NULL CHECK (event_id > 0),
    type       TEXT        NOT NULL CHECK (type IN ('run_progress', 'run_finished', 'run_failed', 'report_ready')),
    payload    JSONB       NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, event_id)
);

-- INV-5: план и параметры прогона неизменяемы; завершённый прогон неизменяем полностью.
CREATE FUNCTION simulation.reject_run_plan_change() RETURNS trigger AS $$
BEGIN
    IF OLD.status NOT IN ('queued', 'running') THEN
        RAISE EXCEPTION 'INV-5: завершённый прогон неизменяем (% -> %)', OLD.status, NEW.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF (to_jsonb(NEW) - 'status' - 'done_steps' - 'total_steps' - 'last_step' - 'played_days'
                      - 'digest' - 'final_level' - 'net_return_pct' - 'final_equity' - 'error_code' - 'finished_at')
       IS DISTINCT FROM
       (to_jsonb(OLD) - 'status' - 'done_steps' - 'total_steps' - 'last_step' - 'played_days'
                      - 'digest' - 'final_level' - 'net_return_pct' - 'final_equity' - 'error_code' - 'finished_at') THEN
        RAISE EXCEPTION 'INV-5: параметры и план прогона неизменяемы'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER run_plan_immutable
    BEFORE UPDATE ON simulation.run
    FOR EACH ROW EXECUTE FUNCTION simulation.reject_run_plan_change();

CREATE FUNCTION simulation.reject_immutable_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'INV-5: % в simulation.% запрещён', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER run_no_delete
    BEFORE DELETE ON simulation.run
    FOR EACH ROW EXECUTE FUNCTION simulation.reject_immutable_change();

CREATE TRIGGER run_paper_immutable
    BEFORE UPDATE OR DELETE ON simulation.run_paper
    FOR EACH ROW EXECUTE FUNCTION simulation.reject_immutable_change();

CREATE TRIGGER run_result_immutable
    BEFORE UPDATE OR DELETE ON simulation.run_result
    FOR EACH ROW EXECUTE FUNCTION simulation.reject_immutable_change();

CREATE FUNCTION simulation.guard_run_event_change() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'INV-5: UPDATE в simulation.run_event запрещён'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF EXISTS (SELECT 1 FROM simulation.run WHERE id = OLD.run_id AND status IN ('queued', 'running')) THEN
        RAISE EXCEPTION 'События активного прогона не удаляются'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER run_event_guard
    BEFORE UPDATE OR DELETE ON simulation.run_event
    FOR EACH ROW EXECUTE FUNCTION simulation.guard_run_event_change();

CREATE FUNCTION simulation.reject_truncate() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'INV-5: TRUNCATE simulation.% запрещён', TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER run_no_truncate
    BEFORE TRUNCATE ON simulation.run
    FOR EACH STATEMENT EXECUTE FUNCTION simulation.reject_truncate();

CREATE TRIGGER run_paper_no_truncate
    BEFORE TRUNCATE ON simulation.run_paper
    FOR EACH STATEMENT EXECUTE FUNCTION simulation.reject_truncate();

CREATE TRIGGER run_result_no_truncate
    BEFORE TRUNCATE ON simulation.run_result
    FOR EACH STATEMENT EXECUTE FUNCTION simulation.reject_truncate();

CREATE TRIGGER run_event_no_truncate
    BEFORE TRUNCATE ON simulation.run_event
    FOR EACH STATEMENT EXECUTE FUNCTION simulation.reject_truncate();
