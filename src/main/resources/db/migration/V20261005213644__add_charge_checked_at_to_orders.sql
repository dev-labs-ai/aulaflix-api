-- When reconciliation last re-read a paid Order's charge, so that a refund made in the Asaas UI, a chargeback or an
-- upheld Pix cautionary block whose webhook was lost still ends access, with each paid Order re-read once per interval
-- rather than on every run. Nullable: until its first re-read, a paid Order counts from its paid_at, so no existing row
-- needs a value.
ALTER TABLE orders ADD COLUMN charge_checked_at TIMESTAMPTZ;

-- Reconciliation's queue of paid Orders to re-read, the longest unchecked first
CREATE INDEX idx_orders_paid_charge_checked ON orders (COALESCE(charge_checked_at, paid_at), id) WHERE status = 'PAID';
