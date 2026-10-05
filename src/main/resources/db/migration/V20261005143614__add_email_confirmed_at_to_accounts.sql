-- When the Account's owner proved the email theirs; null until then. Nullable, so no existing row needs a value.
ALTER TABLE accounts ADD COLUMN email_confirmed_at TIMESTAMPTZ;
