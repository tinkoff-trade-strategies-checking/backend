-- Длина почты не больше 254 символов (RFC 5321).
ALTER TABLE identity.user_account
    ADD CONSTRAINT user_account_email_length CHECK (char_length(email) <= 254);
