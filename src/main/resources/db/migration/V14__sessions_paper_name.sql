-- Название бумаги из каталога архива на момент запуска (securities.name). Пусто — названия нет, наружу идёт тикер.
-- Название раскрывается только в раскрытом виде (INV-7).
ALTER TABLE sessions.session_paper ADD COLUMN name TEXT NOT NULL DEFAULT '';
