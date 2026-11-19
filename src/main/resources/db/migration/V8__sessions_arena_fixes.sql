-- Арена, раунд исправлений (backend#24).

-- Прогрев и издержки исполнения фиксируются в сессии при запуске: смена значений по умолчанию не меняет итог уже
-- сыгранных сессий (INV-8). Существующим сессиям — значения, с которыми они играли (100 шагов, decisions.md,
-- «Модель исполнения»). Триггер V6 сравнивает строку целиком, поэтому новые колонки неизменяемы так же, как план.
ALTER TABLE sessions.session
    ADD COLUMN warmup_bars        INTEGER        NOT NULL DEFAULT 100 CHECK (warmup_bars BETWEEN 0 AND 5000),
    ADD COLUMN commission_rate    NUMERIC(12, 9) NOT NULL DEFAULT 0.003,
    ADD COLUMN exchange_fee_rate  NUMERIC(12, 9) NOT NULL DEFAULT 0.0001,
    ADD COLUMN spread             NUMERIC(12, 9) NOT NULL DEFAULT 0.001,
    ADD COLUMN slippage_kappa     NUMERIC(12, 9) NOT NULL DEFAULT 0.1,
    ADD COLUMN liquidity_share    NUMERIC(12, 9) NOT NULL DEFAULT 0.1;

-- INV-2: клиент не может видеть свечу позже курсора на приёме команды.
ALTER TABLE sessions.command_journal ADD CONSTRAINT command_journal_seen_step_check CHECK (seen_step <= step);

-- Ключ идемпотентности: токен захвата. Ответ записывает только тот запрос, который захватил ключ.
ALTER TABLE sessions.idempotency_key ADD COLUMN claim_token UUID NOT NULL DEFAULT gen_random_uuid();
