-- Прогрев по умолчанию увеличен со 100 до 200 баров (decisions.md, «Данные», documentation#34).
-- Существующие сессии сохраняют свой warmup_bars (INV-8); меняется только значение по умолчанию для новых строк.
ALTER TABLE sessions.session ALTER COLUMN warmup_bars SET DEFAULT 200;
