-- Версия формата сохранённого отчёта (backend#37). Отчёты до core#54 хранят null вместо производных величин и id
-- исполнений по старой формуле, поэтому очищаются: развёрнутых стендов с данными нет, отчёт пересоберётся при первом
-- GET report. Дальше версия входит в ключ: отчёт другой версии при чтении считается отсутствующим, новый пишется
-- отдельной строкой (старая неизменяема, INV-5). Триггеры запрета на время очистки отключаются и включаются снова.
ALTER TABLE analysis.session_report DISABLE TRIGGER session_report_immutable;
ALTER TABLE analysis.session_report DISABLE TRIGGER session_report_no_truncate;
DELETE FROM analysis.session_report;
ALTER TABLE analysis.session_report ENABLE TRIGGER session_report_immutable;
ALTER TABLE analysis.session_report ENABLE TRIGGER session_report_no_truncate;

ALTER TABLE analysis.session_report ADD COLUMN format_version INTEGER NOT NULL;
ALTER TABLE analysis.session_report DROP CONSTRAINT session_report_pkey;
ALTER TABLE analysis.session_report ADD PRIMARY KEY (session_id, format_version);
