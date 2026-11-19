-- Прогон хранит ID неизменяемых версий стратегии и снапшота (INV-5).
-- Внешних ключей в чужие схемы нет: схемы принадлежат модулям, существование версий
-- проверяет код через публичные API workshop и marketdata; сами версии удалить нельзя (триггеры).
CREATE TABLE simulation.run (
    id                  UUID PRIMARY KEY,
    strategy_version_id UUID        NOT NULL,
    snapshot_id         UUID        NOT NULL,
    repeat_of_run_id    UUID REFERENCES simulation.run (id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
