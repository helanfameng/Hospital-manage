SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- A charge is a historical fact. Keep the catalog identity and billing unit
-- with the charge so later catalog maintenance cannot rewrite old statements.
ALTER TABLE charge_detail
    ADD COLUMN item_code_snapshot VARCHAR(32) NULL AFTER medical_item_id,
    ADD COLUMN item_name_snapshot VARCHAR(120) NULL AFTER item_code_snapshot,
    ADD COLUMN unit_snapshot VARCHAR(20) NULL AFTER item_name_snapshot;

-- V1-V13 charges predate these snapshots. For execution charges, the order
-- item carries the historical wording and unit; manual/system charges use the
-- catalog values visible at migration time. There is no earlier code snapshot,
-- so the catalog code is the only safe source for legacy rows.
UPDATE charge_detail c
JOIN medical_item mi ON mi.id = c.medical_item_id
LEFT JOIN execution_record er ON er.id = c.execution_id
LEFT JOIN order_item oi ON oi.id = er.order_item_id
SET c.item_code_snapshot = mi.item_code,
    c.item_name_snapshot = COALESCE(oi.item_name_snapshot, mi.name),
    c.unit_snapshot = COALESCE(oi.unit_snapshot, mi.unit)
WHERE c.item_code_snapshot IS NULL
   OR c.item_name_snapshot IS NULL
   OR c.unit_snapshot IS NULL;

ALTER TABLE charge_detail
    MODIFY item_code_snapshot VARCHAR(32) NOT NULL,
    MODIFY item_name_snapshot VARCHAR(120) NOT NULL,
    MODIFY unit_snapshot VARCHAR(20) NOT NULL,
    ADD CONSTRAINT ck_charge_catalog_snapshot_nonblank CHECK (
        CHAR_LENGTH(TRIM(item_code_snapshot)) > 0
        AND CHAR_LENGTH(TRIM(item_name_snapshot)) > 0
        AND CHAR_LENGTH(TRIM(unit_snapshot)) > 0
    );

DELIMITER $$

-- Normal execution charges inherit the order item's historical wording/unit
-- and the catalog code at the moment the charge is created. Manual charges
-- snapshot the current catalog. Reversals inherit every catalog snapshot from
-- the original charge, regardless of later catalog maintenance.
DROP TRIGGER IF EXISTS trg_charge_provenance_guard_insert$$
CREATE TRIGGER trg_charge_provenance_guard_insert BEFORE INSERT ON charge_detail FOR EACH ROW
BEGIN
    DECLARE v_admission BIGINT DEFAULT NULL;
    DECLARE v_item BIGINT DEFAULT NULL;
    DECLARE v_status VARCHAR(20) DEFAULT NULL;
    DECLARE v_order_item_type VARCHAR(20) DEFAULT NULL;
    DECLARE v_order_item_name VARCHAR(120) DEFAULT NULL;
    DECLARE v_order_item_unit VARCHAR(20) DEFAULT NULL;
    DECLARE v_catalog_code VARCHAR(32) DEFAULT NULL;
    DECLARE v_catalog_type VARCHAR(20) DEFAULT NULL;
    DECLARE v_catalog_name VARCHAR(120) DEFAULT NULL;
    DECLARE v_catalog_unit VARCHAR(20) DEFAULT NULL;
    DECLARE v_original_code VARCHAR(32) DEFAULT NULL;
    DECLARE v_original_name VARCHAR(120) DEFAULT NULL;
    DECLARE v_original_unit VARCHAR(20) DEFAULT NULL;
    DECLARE v_original_type VARCHAR(20) DEFAULT NULL;

    SELECT mo.admission_id, oi.medical_item_id, er.status,
           oi.item_type_snapshot, oi.item_name_snapshot, oi.unit_snapshot
      INTO v_admission, v_item, v_status,
           v_order_item_type, v_order_item_name, v_order_item_unit
      FROM execution_record er
      JOIN order_item oi ON oi.id = er.order_item_id
      JOIN medical_order mo ON mo.id = oi.medical_order_id
      WHERE er.id = NEW.execution_id;
    SELECT item_code, item_type, name, unit
      INTO v_catalog_code, v_catalog_type, v_catalog_name, v_catalog_unit
      FROM medical_item WHERE id = NEW.medical_item_id;
    SELECT item_code_snapshot, item_name_snapshot, unit_snapshot, item_type_snapshot
      INTO v_original_code, v_original_name, v_original_unit, v_original_type
      FROM charge_detail WHERE id = NEW.original_charge_id;

    IF NEW.execution_id IS NOT NULL AND
       (v_admission IS NULL OR v_admission <> NEW.admission_id
        OR v_item <> NEW.medical_item_id
        OR NOT (NEW.item_type_snapshot <=> v_order_item_type)
        OR NOT (NEW.item_name_snapshot <=> v_order_item_name)
        OR NOT (NEW.unit_snapshot <=> v_order_item_unit)
        OR (NEW.charge_type = 'NORMAL'
            AND NOT (NEW.item_code_snapshot <=> v_catalog_code))) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '费用来源执行与住院、医疗项目或项目快照不一致';
    END IF;
    IF NEW.charge_type = 'NORMAL' AND NEW.original_charge_id IS NOT NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '正常费用不能引用冲正原费用';
    END IF;
    IF NEW.charge_type = 'NORMAL' AND NEW.execution_id IS NULL
       AND (NOT (NEW.item_code_snapshot <=> v_catalog_code)
            OR NOT (NEW.item_type_snapshot <=> v_catalog_type)
            OR NOT (NEW.item_name_snapshot <=> v_catalog_name)
            OR NOT (NEW.unit_snapshot <=> v_catalog_unit)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '手工费用项目快照必须匹配当前医疗项目';
    END IF;
    IF NEW.charge_type = 'REVERSAL' AND
       (NEW.original_charge_id IS NULL OR v_original_type IS NULL
        OR NOT (NEW.item_code_snapshot <=> v_original_code)
        OR NOT (NEW.item_name_snapshot <=> v_original_name)
        OR NOT (NEW.unit_snapshot <=> v_original_unit)
        OR NOT (NEW.item_type_snapshot <=> v_original_type)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '费用冲正必须沿用原费用项目快照';
    END IF;

    IF NEW.execution_id IS NOT NULL AND NEW.charge_type = 'NORMAL'
       AND v_status <> 'SUCCESS' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '正常费用只能来源于成功执行';
    END IF;
    IF NEW.charge_type = 'REVERSAL' AND NEW.original_charge_id IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '费用冲正必须引用原费用';
    END IF;
END$$

DELIMITER ;
