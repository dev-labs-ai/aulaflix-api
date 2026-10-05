-- A cancelled Order whose failed placement may have left a charge at Asaas under its code: the placement could not
-- delete it, or could only search for it, and a charge whose creation timed out may reach Asaas after that search.
-- Reconciliation searches once more after its delay, deletes what it finds, and clears the flag. A constant default
-- rewrites no row, and no deployed release has placed an Order yet, so no existing row needs it set.
ALTER TABLE orders ADD COLUMN charges_to_delete BOOLEAN NOT NULL DEFAULT FALSE;

-- The jobs' queues: the Orders awaiting payment, by when they expire, which the expiry job and reconciliation read
-- every run; and the few cancelled ones with charges to delete
CREATE INDEX idx_orders_awaiting_expires_at ON orders (expires_at) WHERE status = 'AWAITING_PAYMENT';
CREATE INDEX idx_orders_charges_to_delete ON orders (created_at) WHERE charges_to_delete;
