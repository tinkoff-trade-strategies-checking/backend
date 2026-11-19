-- Учётные записи заводит команда (регистрации нет). Роль одна — USER, в таблице не хранится.
-- Почта хранится в нижнем регистре; уникальность — без учёта регистра.
CREATE TABLE identity.user_account (
    id            UUID PRIMARY KEY,
    email         TEXT        NOT NULL,
    password_hash TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX user_account_email_lower_key ON identity.user_account (lower(email));
