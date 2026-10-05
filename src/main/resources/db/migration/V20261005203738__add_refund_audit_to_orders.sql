-- The refund audit, on the Order itself: when a refund was asked of Asaas and by which Admin, and when Asaas reported
-- it done. Nullable, since only a refunded Order has them; a refund made in the Asaas UI has no Admin. No deployed
-- release has refunded an Order yet, so no existing row needs a value.
ALTER TABLE orders ADD COLUMN refund_requested_at TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN refund_requested_by BIGINT REFERENCES accounts (id);
ALTER TABLE orders ADD COLUMN refunded_at TIMESTAMPTZ;

-- Reconciliation's queue of refunds to follow until Asaas reports them done
CREATE INDEX idx_orders_refunding ON orders (id) WHERE status = 'REFUNDING';
-- The Admin's list, newest first, with or without a filter on the status
CREATE INDEX idx_orders_created_at ON orders (created_at DESC, id DESC);
CREATE INDEX idx_orders_status_created_at ON orders (status, created_at DESC, id DESC);
