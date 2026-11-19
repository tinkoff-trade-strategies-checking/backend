-- Прогон хранит ID неизменяемых версий стратегии и снапшота (INV-5).
-- Внешних ключей в чужие схемы нет: схемы принадлежат модулям, существование версий
-- проверяет код через публичные API workshop и marketdata; сами версии удалить нельзя (триггеры).
CREATE TABLE simulation.run (
    id                  UUID PRIMARY KEY,
    strategy_version_id UUID        NOT NULL,
    snapshot_id         UUID        NOT NULL,
    repeat_of_run_id    UUID REFERENCES simulation.run (id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Ссылки прогона на версии не меняются после создания (INV-5).
CREATE FUNCTION simulation.reject_run_reference_change() RETURNS trigger AS $$
BEGIN
    IF NEW.strategy_version_id IS DISTINCT FROM OLD.strategy_version_id
        OR NEW.snapshot_id IS DISTINCT FROM OLD.snapshot_id
        OR NEW.repeat_of_run_id IS DISTINCT FROM OLD.repeat_of_run_id THEN
        RAISE EXCEPTION 'INV-5: ссылки прогона на версии стратегии и снапшота неизменяемы'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER run_references_immutable
    BEFORE UPDATE ON simulation.run
    FOR EACH ROW EXECUTE FUNCTION simulation.reject_run_reference_change();
