-- Сохранённый отчёт завершённой сессии (backend#26): строится один раз в фоне после завершения и дальше только читается,
-- числа отчёта, истории и выгрузки не пересчитываются (decisions.md, «Отчёт и история: реализация»). Внешнего ключа на
-- схему sessions нет: сессию проверяет код. report — тело SessionReport контракта, played_days и net_return_pct — для
-- истории без разбора JSON.
CREATE TABLE analysis.session_report (
    session_id     UUID PRIMARY KEY,
    report         JSONB       NOT NULL,
    played_days    INTEGER     NOT NULL CHECK (played_days >= 0),
    net_return_pct TEXT        NOT NULL,
    built_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Сохранённый отчёт не меняется (INV-5, INV-8): повторная сборка даёт тот же результат, хранится первый.
CREATE FUNCTION analysis.reject_report_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'INV-5: % в analysis.% запрещён', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER session_report_immutable
    BEFORE UPDATE OR DELETE ON analysis.session_report
    FOR EACH ROW EXECUTE FUNCTION analysis.reject_report_change();

CREATE TRIGGER session_report_no_truncate
    BEFORE TRUNCATE ON analysis.session_report
    FOR EACH STATEMENT EXECUTE FUNCTION analysis.reject_report_change();
