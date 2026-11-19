-- Плеер и поток событий (backend#25).

-- Состояние плеера сессии: играет ли время и на какой скорости. Таймер живёт только в памяти процесса, поэтому после
-- перезапуска приложение ставит все сессии на паузу (призрачных таймеров нет). total_steps — число шагов шкалы:
-- шкала сессии неизменна, значение узнаётся у воркера при первой команде плеера.
CREATE TABLE sessions.player_state (
    session_id  UUID        PRIMARY KEY REFERENCES sessions.session (id),
    playing     BOOLEAN     NOT NULL DEFAULT FALSE,
    speed       INTEGER     NOT NULL DEFAULT 1 CHECK (speed IN (1, 5, 10, 100)),
    total_steps BIGINT      CHECK (total_steps > 0),
    -- день и номер свечи в дне на шаге cursor_step: ответы pause и speed не зависят от воркера; значения верны, только
    -- пока cursor_step = current_step сессии, иначе код берёт их у воркера и записывает заново
    cursor_step   BIGINT   CHECK (cursor_step >= 0),
    cursor_day    INTEGER,
    cursor_candle INTEGER,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- События потока SSE. event_id возрастает в рамках сессии: его выдаёт код под блокировкой строки сессии, поэтому
-- порядок фиксации совпадает с порядком номеров и догон по Last-Event-ID не видит «дыр». Тело уже обезличено
-- (слепая сессия — только через BlindMasking, INV-7) и не содержит данных дальше курсора (INV-1).
CREATE TABLE sessions.session_event (
    session_id UUID        NOT NULL REFERENCES sessions.session (id),
    event_id   BIGINT      NOT NULL CHECK (event_id > 0),
    type       TEXT        NOT NULL CHECK (type IN ('step', 'order_updated', 'paused', 'player', 'finished', 'report_ready')),
    payload    JSONB       NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (session_id, event_id)
);

-- События не меняются; удалять их можно только у завершённой сессии (фоновая очистка старше срока хранения).
CREATE FUNCTION sessions.guard_session_event_change() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'INV-8: UPDATE в sessions.session_event запрещён'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF EXISTS (SELECT 1 FROM sessions.session WHERE id = OLD.session_id AND status = 'running') THEN
        RAISE EXCEPTION 'События идущей сессии не удаляются'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER session_event_guard
    BEFORE UPDATE OR DELETE ON sessions.session_event
    FOR EACH ROW EXECUTE FUNCTION sessions.guard_session_event_change();

CREATE TRIGGER session_event_no_truncate
    BEFORE TRUNCATE ON sessions.session_event
    FOR EACH STATEMENT EXECUTE FUNCTION sessions.reject_truncate();
