-- Мягкое удаление авторских стратегий в Мастерской с сохранением неизменяемых версий (INV-5).
-- Триггеры strategy_no_delete, strategy_no_truncate (V16) и strategy_version_immutable (V2) сохраняются.

ALTER TABLE workshop.strategy
    DROP CONSTRAINT IF EXISTS strategy_visibility_check,
    ADD CONSTRAINT strategy_visibility_check CHECK (visibility IN ('public', 'hidden', 'deleted'));

DROP INDEX IF EXISTS workshop.strategy_owner_name_key;

CREATE UNIQUE INDEX strategy_owner_name_key
    ON workshop.strategy (COALESCE(owner_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(name))
    WHERE visibility <> 'deleted';
