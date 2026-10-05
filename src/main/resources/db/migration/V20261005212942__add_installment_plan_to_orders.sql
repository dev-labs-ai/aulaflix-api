-- A card paid in more than one installment makes an Asaas installment plan, one charge per installment: a refund of the
-- whole sale goes through the plan, since a refund of the charge the Order keeps would return one installment only.
-- Nullable, since a Pix and a card paid at once have none, so no existing row needs a value.
ALTER TABLE orders ADD COLUMN asaas_installment_id VARCHAR(64);
