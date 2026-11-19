-- Загруженный снапшот данных неизменяем (INV-5): UPDATE и DELETE запрещены триггером.
-- snapshot_key — значение snapshot_id в ClickHouse.
CREATE TABLE marketdata.snapshot (
    id           UUID PRIMARY KEY,
    snapshot_key TEXT        NOT NULL UNIQUE,
    loaded_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    description  TEXT        NOT NULL DEFAULT ''
);

CREATE FUNCTION marketdata.reject_snapshot_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'INV-5: загруженный снапшот неизменяем (% snapshot)', TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER snapshot_immutable
    BEFORE UPDATE OR DELETE ON marketdata.snapshot
    FOR EACH ROW EXECUTE FUNCTION marketdata.reject_snapshot_change();

-- TRUNCATE не вызывает row-триггеры, нужен отдельный statement-триггер.
CREATE TRIGGER snapshot_no_truncate
    BEFORE TRUNCATE ON marketdata.snapshot
    FOR EACH STATEMENT EXECUTE FUNCTION marketdata.reject_snapshot_change();
