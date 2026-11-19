-- Опубликованная версия стратегии неизменяема (INV-5): UPDATE и DELETE запрещены триггером.
CREATE TABLE workshop.strategy_version (
    id           UUID PRIMARY KEY,
    strategy_id  UUID        NOT NULL,
    version      INTEGER     NOT NULL CHECK (version > 0),
    source       TEXT        NOT NULL,
    params       JSONB       NOT NULL DEFAULT '{}'::jsonb,
    published_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (strategy_id, version)
);

CREATE FUNCTION workshop.reject_strategy_version_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'INV-5: опубликованная версия стратегии неизменяема (% strategy_version)', TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER strategy_version_immutable
    BEFORE UPDATE OR DELETE ON workshop.strategy_version
    FOR EACH ROW EXECUTE FUNCTION workshop.reject_strategy_version_change();

-- TRUNCATE не вызывает row-триггеры, нужен отдельный statement-триггер.
CREATE TRIGGER strategy_version_no_truncate
    BEFORE TRUNCATE ON workshop.strategy_version
    FOR EACH STATEMENT EXECUTE FUNCTION workshop.reject_strategy_version_change();
