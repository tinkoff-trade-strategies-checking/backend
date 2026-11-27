-- Лимит дневного убытка (Kill-Switch, backend#89): необязательный порог дневного убытка в % от капитала начала дня
-- и признак срабатывания для ручных сессий и алгоритмических прогонов (INV-5, INV-6).

ALTER TABLE sessions.session
    ADD COLUMN daily_loss_limit_pct  NUMERIC(5, 2) NULL CHECK (daily_loss_limit_pct > 0 AND daily_loss_limit_pct <= 100),
    ADD COLUMN kill_switch_triggered BOOLEAN       NOT NULL DEFAULT FALSE;

CREATE OR REPLACE FUNCTION sessions.reject_session_plan_change() RETURNS trigger AS $$
BEGIN
    IF (to_jsonb(NEW) - 'status' - 'current_step' - 'kill_switch_triggered')
       IS DISTINCT FROM
       (to_jsonb(OLD) - 'status' - 'current_step' - 'kill_switch_triggered') THEN
        RAISE EXCEPTION 'INV-5: параметры и план сессии неизменяемы'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.status <> 'running' AND NEW.status IS DISTINCT FROM OLD.status THEN
        RAISE EXCEPTION 'Статус завершённой сессии не меняется (% -> %)', OLD.status, NEW.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.status <> 'running' AND NEW.kill_switch_triggered IS DISTINCT FROM OLD.kill_switch_triggered THEN
        RAISE EXCEPTION 'Состояние kill-switch завершённой сессии не меняется'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.current_step < OLD.current_step THEN
        RAISE EXCEPTION 'Курсор сессии не убывает (% -> %)', OLD.current_step, NEW.current_step
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

ALTER TABLE simulation.run
    ADD COLUMN daily_loss_limit_pct  NUMERIC(5, 2) NULL CHECK (daily_loss_limit_pct > 0 AND daily_loss_limit_pct <= 100),
    ADD COLUMN kill_switch_triggered BOOLEAN       NOT NULL DEFAULT FALSE;

CREATE OR REPLACE FUNCTION simulation.reject_run_plan_change() RETURNS trigger AS $$
BEGIN
    IF OLD.status NOT IN ('queued', 'running') THEN
        RAISE EXCEPTION 'INV-5: завершённый прогон неизменяем (% -> %)', OLD.status, NEW.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF (to_jsonb(NEW) - 'status' - 'done_steps' - 'total_steps' - 'last_step' - 'played_days'
                      - 'digest' - 'final_level' - 'net_return_pct' - 'final_equity' - 'error_code'
                      - 'finished_at' - 'kill_switch_triggered')
       IS DISTINCT FROM
       (to_jsonb(OLD) - 'status' - 'done_steps' - 'total_steps' - 'last_step' - 'played_days'
                      - 'digest' - 'final_level' - 'net_return_pct' - 'final_equity' - 'error_code'
                      - 'finished_at' - 'kill_switch_triggered') THEN
        RAISE EXCEPTION 'INV-5: параметры и план прогона неизменяемы'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
