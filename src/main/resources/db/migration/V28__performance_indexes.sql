-- Индексы производительности для истории сессий, батчевой загрузки бумаг и каталога Мастерской (backend#104).

CREATE INDEX IF NOT EXISTS session_user_created_id_idx
    ON sessions.session (user_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS session_paper_session_covering_idx
    ON sessions.session_paper (session_id, paper_index)
    INCLUDE (paper_id, secid, sector, lot_size, price_step, name);

CREATE INDEX IF NOT EXISTS strategy_visibility_active_idx
    ON workshop.strategy (visibility, owner_id, updated_at DESC)
    WHERE visibility <> 'deleted';
