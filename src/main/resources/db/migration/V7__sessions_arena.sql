-- Арена (backend#24): курсор сессии, причина отказа в журнале, идентификаторы заявок и ключи идемпотентности.

-- Текущий шаг курсора (виртуальное время). Пока его двигает только плеер (backend#25); чтение снимка курсор не меняет.
-- Курсор не убывает; остальные параметры и план сессии по-прежнему неизменяемы (INV-5).
ALTER TABLE sessions.session ADD COLUMN current_step BIGINT NOT NULL DEFAULT 0 CHECK (current_step >= 0);

CREATE OR REPLACE FUNCTION sessions.reject_session_plan_change() RETURNS trigger AS $$
BEGIN
    IF (to_jsonb(NEW) - 'status' - 'current_step') IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'current_step') THEN
        RAISE EXCEPTION 'INV-5: параметры и план сессии неизменяемы'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.status <> 'running' AND NEW.status IS DISTINCT FROM OLD.status THEN
        RAISE EXCEPTION 'Статус завершённой сессии не меняется (% -> %)', OLD.status, NEW.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.current_step < OLD.current_step THEN
        RAISE EXCEPTION 'Курсор сессии не убывает (% -> %)', OLD.current_step, NEW.current_step
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Поле отказа воркера (invalid_field) рядом с причиной; в журнале только дописывается (триггеры V6 остаются).
ALTER TABLE sessions.command_journal ADD COLUMN reject_field TEXT;

-- Идентификатор заявки наружу — UUID v7 (контракт), в ядре — номер seq * 2 + нога. Соответствие создаётся при первом
-- показе заявки и не меняется; состояния заявки оно не хранит (INV-8).
CREATE TABLE sessions.order_ref (
    session_id    UUID   NOT NULL REFERENCES sessions.session (id),
    core_order_id BIGINT NOT NULL CHECK (core_order_id >= 0),
    id            UUID   NOT NULL UNIQUE,
    PRIMARY KEY (session_id, core_order_id)
);

CREATE TRIGGER order_ref_immutable
    BEFORE UPDATE OR DELETE ON sessions.order_ref
    FOR EACH ROW EXECUTE FUNCTION sessions.reject_immutable_change();

CREATE TRIGGER order_ref_no_truncate
    BEFORE TRUNCATE ON sessions.order_ref
    FOR EACH STATEMENT EXECUTE FUNCTION sessions.reject_truncate();

-- Ключи идемпотентности (Idempotency-Key): область — сессия. status = in_progress, пока команда выполняется;
-- done — response хранит ответ клиенту (для слепой сессии он уже замаскирован), повтор отдаёт его без записи в журнал.
CREATE TABLE sessions.idempotency_key (
    session_id   UUID        NOT NULL REFERENCES sessions.session (id),
    key          UUID        NOT NULL,
    operation    TEXT        NOT NULL,
    request_hash TEXT        NOT NULL,
    status       TEXT        NOT NULL CHECK (status IN ('in_progress', 'done')),
    response     JSONB,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (session_id, key)
);
