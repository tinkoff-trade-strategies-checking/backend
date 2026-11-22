-- Каталог стратегий, черновики и метаданные проверок опубликованных версий (UC-12, UC-13, UC-19, UC-20, UC-21, INV-5).
-- Удаление и очистка стратегий запрещены: автор может только скрыть стратегию (visibility = 'hidden').

CREATE TABLE workshop.strategy (
    id                     UUID PRIMARY KEY,
    owner_id               UUID,
    author_name            TEXT        NOT NULL,
    is_system              BOOLEAN     NOT NULL DEFAULT FALSE,
    name                   TEXT        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 80),
    description            TEXT        NOT NULL DEFAULT '' CHECK (char_length(description) <= 2000),
    visibility             TEXT        NOT NULL DEFAULT 'public' CHECK (visibility IN ('public', 'hidden')),
    license                TEXT        NOT NULL DEFAULT 'MIT',
    forked_from_version_id UUID        REFERENCES workshop.strategy_version (id),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (NOT is_system OR visibility = 'public')
);

CREATE UNIQUE INDEX strategy_owner_name_key
    ON workshop.strategy (COALESCE(owner_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(name));

CREATE INDEX strategy_visibility_idx
    ON workshop.strategy (visibility, owner_id);

CREATE FUNCTION workshop.reject_strategy_delete() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'INV-5: стратегию нельзя удалить из БД, допустимо только скрытие (% strategy)', TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER strategy_no_delete
    BEFORE DELETE ON workshop.strategy
    FOR EACH ROW EXECUTE FUNCTION workshop.reject_strategy_delete();

CREATE TRIGGER strategy_no_truncate
    BEFORE TRUNCATE ON workshop.strategy
    FOR EACH STATEMENT EXECUTE FUNCTION workshop.reject_strategy_delete();

CREATE TABLE workshop.strategy_draft (
    strategy_id      UUID PRIMARY KEY REFERENCES workshop.strategy (id),
    source           TEXT        NOT NULL DEFAULT '',
    last_check_level TEXT        CHECK (last_check_level IN ('OK', 'WARNING', 'ERROR')),
    last_diagnostics JSONB       NOT NULL DEFAULT '[]'::jsonb,
    last_params      JSONB       NOT NULL DEFAULT '[]'::jsonb,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE workshop.strategy_version
    ADD COLUMN summary         TEXT  NOT NULL DEFAULT '',
    ADD COLUMN lookahead_level TEXT  NOT NULL DEFAULT 'OK' CHECK (lookahead_level IN ('OK', 'WARNING', 'ERROR')),
    ADD COLUMN diagnostics     JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN param_decls     JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT strategy_version_strategy_fk FOREIGN KEY (strategy_id) REFERENCES workshop.strategy (id);

CREATE INDEX strategy_version_published_idx
    ON workshop.strategy_version (strategy_id, version DESC);

-- Системный алгоритм по умолчанию: «Эталон платформы» (documentation/dsl.md, decisions.md).
INSERT INTO workshop.strategy (id, owner_id, author_name, is_system, name, description, visibility, license)
VALUES (
    '10000000-0000-4000-8000-000000000001'::uuid,
    NULL,
    'Эталон платформы',
    TRUE,
    'Эталон платформы',
    'Эталонная стратегия пересечения SMA(fast, slow) с фильтром RSI, стопом stop_pct и прогревом 200 баров. Для N бумаг размер позиции size_pct = floor(95 / N).',
    'public',
    'MIT'
);

INSERT INTO workshop.strategy_version (id, strategy_id, version, source, params, summary, lookahead_level, diagnostics, param_decls)
VALUES (
    '10000000-0000-4000-8000-000000000002'::uuid,
    '10000000-0000-4000-8000-000000000001'::uuid,
    1,
$dsl$# Эталон платформы: пересечение скользящих средних
param fast = 20 range 5..50 step 5
param slow = 50 range 20..200 step 10
param rsi_n = 14
param rsi_high = 70 range 60..85 step 5
param stop_pct = 5 range 2..10 step 1
param size_pct = 95

rule "вход"
  when crossover(sma(close, fast), sma(close, slow)) and position == 0
  then buy size_pct % cash
  because "SMA({fast}) пересекла SMA({slow}) снизу вверх"

rule "стоп"
  when position > 0
  then sell all stop entry_price * (1 - stop_pct / 100)
  because "защитный стоп {stop_pct}% от цены входа {entry_price}"

rule "перекупленность"
  when position > 0 and rsi(close, rsi_n) > rsi_high
  then sell all
  because "перекупленность, RSI = {rsi(close, rsi_n)}"

rule "выход"
  when position > 0 and crossunder(sma(close, fast), sma(close, slow))
  then sell all
  because "SMA({fast}) опустилась ниже SMA({slow})"$dsl$,
    '{"fast":20,"slow":50,"rsi_n":14,"rsi_high":70,"stop_pct":5,"size_pct":95}'::jsonb,
    'Базовая стратегия платформы без предупреждений линтера и чтения будущего.',
    'OK',
    '[]'::jsonb,
    '[{"name":"fast","defaultValue":20.0,"rangeMin":5.0,"rangeMax":50.0,"step":"5","integer":true},{"name":"slow","defaultValue":50.0,"rangeMin":20.0,"rangeMax":200.0,"step":"10","integer":true},{"name":"rsi_n","defaultValue":14.0,"rangeMin":null,"rangeMax":null,"step":null,"integer":true},{"name":"rsi_high","defaultValue":70.0,"rangeMin":60.0,"rangeMax":85.0,"step":"5","integer":false},{"name":"stop_pct","defaultValue":5.0,"rangeMin":2.0,"rangeMax":10.0,"step":"1","integer":false},{"name":"size_pct","defaultValue":95.0,"rangeMin":null,"rangeMax":null,"step":null,"integer":false}]'::jsonb
);
