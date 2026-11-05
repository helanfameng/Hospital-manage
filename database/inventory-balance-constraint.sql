-- Every immutable inventory transaction must reconcile its before/after balance.
ALTER TABLE drug_inventory_transaction
    ADD CONSTRAINT ck_inventory_balance
    CHECK (after_quantity = before_quantity + quantity);
