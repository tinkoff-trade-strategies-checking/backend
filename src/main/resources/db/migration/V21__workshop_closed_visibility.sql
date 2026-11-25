-- Три уровня видимости алгоритмов в Мастерской:
--   public  — публичный (видят все, исходный код открыт)
--   closed  — закрытый код (видят и запускают все, исходный код скрыт от других; автор видит и редактирует)
--   hidden  — приватный (видит только автор)
--   deleted — мягко удалён (INV-5)
-- Системные алгоритмы («Эталон платформы») всегда остаются public (ограничение из V16).

ALTER TABLE workshop.strategy
    DROP CONSTRAINT IF EXISTS strategy_visibility_check,
    ADD CONSTRAINT strategy_visibility_check CHECK (visibility IN ('public', 'closed', 'hidden', 'deleted'));
