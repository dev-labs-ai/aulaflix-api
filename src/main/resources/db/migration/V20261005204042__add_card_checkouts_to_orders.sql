-- A card Order is paid on an Asaas Checkout: the Order keeps the Checkout's id, by which Asaas's charges name it in
-- checkoutSession, and the link the Student pays at. The charge's id arrives with the payment, in asaas_payment_id, and
-- installments with it: how many the Student chose on Asaas's page. All nullable, since a Pix Order has none of them and
-- a card Order has none until Asaas answers, so no existing row needs a value.
ALTER TABLE orders ADD COLUMN asaas_checkout_id VARCHAR(64);
ALTER TABLE orders ADD COLUMN checkout_url TEXT;
ALTER TABLE orders ADD COLUMN installments INTEGER;
-- Asaas offers 1 to 21 installments on a Checkout; every existing row is NULL, which passes
ALTER TABLE orders ADD CONSTRAINT ck_orders_installments CHECK (installments BETWEEN 1 AND 21);

-- The webhook worker finds the card Order of the charge it re-read, or of the Checkout an event names
CREATE INDEX idx_orders_asaas_checkout_id ON orders (asaas_checkout_id);

-- A Checkout event names a Checkout, not a charge: a pending one carries either. The new check only widens the old
-- one, so every existing row passes it.
ALTER TABLE webhook_events ADD COLUMN checkout_id VARCHAR(64);
ALTER TABLE webhook_events DROP CONSTRAINT ck_webhook_events_pending_charge;
ALTER TABLE webhook_events ADD CONSTRAINT ck_webhook_events_pending_subject
    CHECK (state <> 'PENDING' OR charge_id IS NOT NULL OR checkout_id IS NOT NULL);
