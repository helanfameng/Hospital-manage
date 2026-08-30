SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- V6 hardens invariants that cannot be expressed by a single service call.
-- It is safe to apply after V1-V5 because all existing seed rows satisfy these rules.

-- A group can only be referenced by items from the order that owns it.
ALTER TABLE order_group
    ADD CONSTRAINT uk_order_group_id_order UNIQUE (id, medical_order_id);

ALTER TABLE order_item
    DROP FOREIGN KEY fk_order_item_group,
    ADD CONSTRAINT fk_order_item_group_order
        FOREIGN KEY (group_id, medical_order_id)
        REFERENCES order_group(id, medical_order_id);

-- Only one open alert is allowed for one unsettled admission. Resolved alerts
-- retain the resolution timestamp, while open alerts must remain actionable.
ALTER TABLE account_alert
    ADD COLUMN open_admission_key BIGINT GENERATED ALWAYS AS
        (CASE WHEN status = 'OPEN' THEN admission_id END) STORED,
    ADD CONSTRAINT uk_account_alert_open_admission UNIQUE (open_admission_key),
    ADD CONSTRAINT ck_account_alert_resolution CHECK (
        (status = 'OPEN' AND resolved_at IS NULL)
        OR (status = 'RESOLVED' AND resolved_at IS NOT NULL)
    );

-- The catalog has one explicit stock model: only drugs carry stock quantities.
ALTER TABLE medical_item
    ADD CONSTRAINT ck_medical_item_inventory_shape CHECK (
        (item_type = 'DRUG' AND inventory_managed = TRUE AND stock_quantity IS NOT NULL)
        OR (item_type <> 'DRUG' AND inventory_managed = FALSE AND stock_quantity IS NULL)
    ),
    ADD CONSTRAINT ck_medical_item_execution_role CHECK (execution_role IN ('NURSE', 'DOCTOR'));

-- Snapshots are mandatory facts of an order item. Optional dosing fields remain
-- nullable for non-drug work, but the drug/non-drug shapes are mutually exclusive.
ALTER TABLE order_item
    MODIFY item_name_snapshot VARCHAR(120) NOT NULL,
    MODIFY item_type_snapshot VARCHAR(20) NOT NULL,
    MODIFY unit_snapshot VARCHAR(20) NOT NULL,
    MODIFY price_snapshot DECIMAL(12,2) NOT NULL,
    MODIFY execution_role VARCHAR(20) NOT NULL,
    MODIFY order_type VARCHAR(20) NOT NULL,
    ADD CONSTRAINT ck_order_item_drug_shape CHECK (
        (item_type_snapshot = 'DRUG'
            AND inventory_managed = TRUE
            AND dosage IS NOT NULL
            AND route IS NOT NULL
            AND dose_unit IS NOT NULL
            AND dose_units_per_stock > 0
            AND execution_role = 'NURSE')
        OR (item_type_snapshot <> 'DRUG'
            AND inventory_managed = FALSE
            AND dosage IS NULL
            AND route IS NULL
            AND dose_unit IS NULL
            AND dose_units_per_stock IS NULL)
    ),
    ADD CONSTRAINT ck_order_item_group_shape CHECK (
        group_id IS NULL OR (item_type_snapshot = 'DRUG' AND route IS NOT NULL)
    ),
    ADD CONSTRAINT ck_order_item_progress_shape CHECK (
        (item_type_snapshot = 'DRUG'
            AND administered_quantity <= dispensed_quantity * dose_units_per_stock
            AND executed_quantity = administered_quantity)
        OR (item_type_snapshot <> 'DRUG'
            AND dispensed_quantity = 0
            AND administered_quantity = 0)
    );

-- An execution always records its measurement unit and has a coherent status
-- and quantity. Reversals keep positive magnitude and point to the original row.
ALTER TABLE execution_record
    MODIFY unit_snapshot VARCHAR(20) NOT NULL,
    ADD CONSTRAINT ck_execution_status_shape CHECK (
        (status = 'SUCCESS' AND quantity > 0 AND reversed_execution_id IS NULL)
        OR (status = 'NOT_DONE' AND quantity = 0 AND reversed_execution_id IS NULL)
        OR (status = 'FAILED' AND quantity = 0 AND reversed_execution_id IS NULL)
        OR (status = 'REVERSED' AND quantity > 0 AND reversed_execution_id IS NOT NULL)
    );

ALTER TABLE drug_inventory_transaction
    ADD CONSTRAINT ck_inventory_delta CHECK (after_quantity = before_quantity + quantity),
    ADD CONSTRAINT ck_inventory_direction CHECK (
        (transaction_type IN ('INBOUND', 'RETURN') AND quantity > 0)
        OR (transaction_type = 'DISPENSE' AND quantity < 0)
        OR transaction_type = 'ADJUSTMENT'
    );

ALTER TABLE settlement_detail
    ADD CONSTRAINT ck_settlement_detail_category CHECK (
        category IN ('DRUG', 'EXAMINATION', 'LAB', 'TREATMENT', 'NURSING', 'DIET', 'OTHER', 'BED_FEE')
    );

DELIMITER $$

-- Admission references must remain inside the selected department.
DROP TRIGGER IF EXISTS trg_admission_reference_guard_insert$$
CREATE TRIGGER trg_admission_reference_guard_insert BEFORE INSERT ON admission FOR EACH ROW
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM staff s
        WHERE s.id = NEW.attending_doctor_id
          AND s.department_id = NEW.department_id
          AND s.staff_type = 'DOCTOR'
          AND s.active = TRUE
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '主管医生必须是所选科室的在职医生';
    END IF;
    IF NEW.current_bed_id IS NOT NULL AND NOT EXISTS (
        SELECT 1
        FROM bed b JOIN room r ON r.id = b.room_id
        JOIN ward w ON w.id = r.ward_id
        WHERE b.id = NEW.current_bed_id
          AND w.department_id = NEW.department_id
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '当前床位必须属于住院科室';
    END IF;
END$$

DROP TRIGGER IF EXISTS trg_admission_reference_guard_update$$
CREATE TRIGGER trg_admission_reference_guard_update BEFORE UPDATE ON admission FOR EACH ROW
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM staff s
        WHERE s.id = NEW.attending_doctor_id
          AND s.department_id = NEW.department_id
          AND s.staff_type = 'DOCTOR'
          AND s.active = TRUE
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '主管医生必须是所选科室的在职医生';
    END IF;
    IF NEW.current_bed_id IS NOT NULL AND NOT EXISTS (
        SELECT 1
        FROM bed b JOIN room r ON r.id = b.room_id
        JOIN ward w ON w.id = r.ward_id
        WHERE b.id = NEW.current_bed_id
          AND w.department_id = NEW.department_id
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '当前床位必须属于住院科室';
    END IF;
END$$

-- A drug profile is a subtype row, never a profile for a lab or treatment item.
DROP TRIGGER IF EXISTS trg_drug_profile_item_guard_insert$$
CREATE TRIGGER trg_drug_profile_item_guard_insert BEFORE INSERT ON drug_profile FOR EACH ROW
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM medical_item
        WHERE id = NEW.medical_item_id AND item_type = 'DRUG' AND inventory_managed = TRUE
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '只有库存药品可以维护药品用量资料';
    END IF;
END$$

DROP TRIGGER IF EXISTS trg_drug_profile_item_guard_update$$
CREATE TRIGGER trg_drug_profile_item_guard_update BEFORE UPDATE ON drug_profile FOR EACH ROW
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM medical_item
        WHERE id = NEW.medical_item_id AND item_type = 'DRUG' AND inventory_managed = TRUE
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '只有库存药品可以维护药品用量资料';
    END IF;
END$$

DROP TRIGGER IF EXISTS trg_medical_item_profile_guard_update$$
CREATE TRIGGER trg_medical_item_profile_guard_update BEFORE UPDATE ON medical_item FOR EACH ROW
BEGIN
    IF NEW.item_type <> 'DRUG' AND EXISTS (
        SELECT 1 FROM drug_profile WHERE medical_item_id = NEW.id
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '已有药品用量资料的项目不能改为非药品';
    END IF;
END$$

-- The application routes tasks correctly, and the database independently keeps
-- drug dispensing, drug administration and non-drug work distinguishable.
DROP TRIGGER IF EXISTS trg_execution_semantics_guard_insert$$
CREATE TRIGGER trg_execution_semantics_guard_insert BEFORE INSERT ON execution_record FOR EACH ROW
BEGIN
    DECLARE v_item_type VARCHAR(20);
    DECLARE v_unit VARCHAR(20);
    DECLARE v_dose_unit VARCHAR(20);
    DECLARE v_inventory BOOLEAN;
    SELECT item_type_snapshot, unit_snapshot, dose_unit, inventory_managed
      INTO v_item_type, v_unit, v_dose_unit, v_inventory
      FROM order_item WHERE id = NEW.order_item_id;
    IF v_item_type IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '执行项目不存在';
    END IF;
    IF NEW.phase = 'DISPENSE' AND (v_item_type <> 'DRUG' OR v_inventory <> TRUE OR NEW.unit_snapshot <> v_unit) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '发药执行必须对应库存药品并使用库存单位';
    END IF;
    IF NEW.phase = 'ADMINISTER' AND (v_item_type <> 'DRUG' OR NEW.unit_snapshot <> v_dose_unit) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '给药执行必须对应药品并使用用量单位';
    END IF;
    IF NEW.phase = 'PERFORM' AND v_item_type = 'DRUG' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '药品不能通过非药品执行环节完成';
    END IF;
END$$

-- If a charge names an execution, both the admission and catalog item must be
-- the same source. Manual/system charges without an execution remain supported.
DROP TRIGGER IF EXISTS trg_charge_provenance_guard_insert$$
CREATE TRIGGER trg_charge_provenance_guard_insert BEFORE INSERT ON charge_detail FOR EACH ROW
BEGIN
    DECLARE v_admission BIGINT DEFAULT NULL;
    DECLARE v_item BIGINT DEFAULT NULL;
    DECLARE v_status VARCHAR(20) DEFAULT NULL;
    SELECT mo.admission_id, oi.medical_item_id, er.status
      INTO v_admission, v_item, v_status
      FROM execution_record er
      JOIN order_item oi ON oi.id = er.order_item_id
      JOIN medical_order mo ON mo.id = oi.medical_order_id
      WHERE er.id = NEW.execution_id;
    IF NEW.execution_id IS NOT NULL AND (v_admission IS NULL OR v_admission <> NEW.admission_id OR v_item <> NEW.medical_item_id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '费用来源执行与住院或医疗项目不一致';
    END IF;
    IF NEW.execution_id IS NOT NULL AND NEW.charge_type = 'NORMAL' AND v_status <> 'SUCCESS' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '正常费用只能来源于成功执行';
    END IF;
    IF NEW.charge_type = 'REVERSAL' AND NEW.original_charge_id IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '费用冲正必须引用原费用';
    END IF;
END$$

-- Inventory movements must name the matching item and the correct execution
-- phase. The signed delta itself is protected by the table CHECK above.
DROP TRIGGER IF EXISTS trg_inventory_provenance_guard_insert$$
CREATE TRIGGER trg_inventory_provenance_guard_insert BEFORE INSERT ON drug_inventory_transaction FOR EACH ROW
BEGIN
    DECLARE v_item BIGINT DEFAULT NULL;
    DECLARE v_phase VARCHAR(20) DEFAULT NULL;
    DECLARE v_status VARCHAR(20) DEFAULT NULL;
    SELECT oi.medical_item_id, er.phase, er.status
      INTO v_item, v_phase, v_status
      FROM execution_record er JOIN order_item oi ON oi.id = er.order_item_id
      WHERE er.id = NEW.execution_id;
    IF NEW.execution_id IS NOT NULL AND (v_item IS NULL OR v_item <> NEW.medical_item_id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '库存流水与执行项目不一致';
    END IF;
    IF NEW.transaction_type = 'DISPENSE'
       AND (NEW.execution_id IS NULL OR v_phase <> 'DISPENSE' OR v_status <> 'SUCCESS' OR NEW.quantity >= 0) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '发药库存流水必须来源于成功发药执行';
    END IF;
    IF NEW.transaction_type = 'RETURN'
       AND (NEW.execution_id IS NULL OR v_phase <> 'DISPENSE' OR v_status <> 'REVERSED' OR NEW.quantity <= 0) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '退药库存流水必须来源于发药冲正';
    END IF;
END$$

DELIMITER ;
