-- Фоновый алгоритм ручной сессии и его параметры (ФТ-18…20, INV-5): фиксируются при создании.
-- Существующие строки получают algo_version_id = NULL и algo_params = '{}'; триггер V6/V7 запрещает их изменение.
ALTER TABLE sessions.session
    ADD COLUMN algo_version_id UUID,
    ADD COLUMN algo_params     JSONB NOT NULL DEFAULT '{}'::jsonb;
