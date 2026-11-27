-- Профильные настройки интерфейса (beginner / pro) и гостевой/демо-вход (backend#88).

ALTER TABLE identity.user_account
    ADD COLUMN ui_mode          VARCHAR(16) NOT NULL DEFAULT 'beginner'
        CHECK (ui_mode IN ('beginner', 'pro')),
    ADD COLUMN is_guest         BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN guest_expires_at TIMESTAMPTZ NULL;

CREATE INDEX idx_user_account_guest_expires
    ON identity.user_account (guest_expires_at)
    WHERE is_guest = TRUE;
