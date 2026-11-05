SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- PRN/"as needed" is not part of the inpatient coursework scope. Do not
-- silently remove an already-used frequency: an existing PRN order must be
-- reviewed before the destructive reference-data change is allowed.
DELIMITER $$
DROP PROCEDURE IF EXISTS sp_guard_prn_frequency_removal$$
CREATE PROCEDURE sp_guard_prn_frequency_removal()
BEGIN
    IF EXISTS (SELECT 1 FROM order_group WHERE frequency_code = 'PRN6H')
       OR EXISTS (SELECT 1 FROM medication_plan WHERE frequency_code = 'PRN6H') THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '存在已使用的 PRN6H 医嘱，不能自动删除 PRN 功能，请先完成人工迁移';
    END IF;
END$$
CALL sp_guard_prn_frequency_removal()$$
DROP PROCEDURE sp_guard_prn_frequency_removal$$
DELIMITER ;

-- The V10 view and trigger mention as_needed, so replace those dependent
-- objects around the column removal. The trigger name is retained and its
-- invariant is narrowed to the remaining frequency semantics.
DROP VIEW IF EXISTS v_medication_plan_progress;
DROP TRIGGER IF EXISTS trg_medication_frequency_guard_update;
DELETE FROM medication_frequency WHERE code = 'PRN6H';
ALTER TABLE medication_frequency DROP COLUMN as_needed;

DELIMITER $$
CREATE TRIGGER trg_medication_frequency_guard_update
BEFORE UPDATE ON medication_frequency FOR EACH ROW
BEGIN
    IF NOT (NEW.code <=> OLD.code)
       OR NOT (NEW.interval_minutes <=> OLD.interval_minutes)
       OR NOT (NEW.once_only <=> OLD.once_only) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '已使用的给药频次语义不可修改，请停用后新增频次编码';
    END IF;
END$$
DELIMITER ;

CREATE OR REPLACE VIEW v_medication_plan_progress AS
SELECT
    oi.id AS order_item_id,
    oi.medical_order_id,
    oi.order_type,
    oi.item_status,
    oi.dosage,
    oi.dose_unit,
    oi.quantity_per_execution,
    oi.unit_snapshot,
    oi.dispensed_quantity,
    oi.administered_quantity,
    oi.start_time,
    oi.end_time,
    mp.frequency_code,
    mf.name AS frequency_name,
    mf.interval_minutes,
    mf.once_only,
    mp.first_dose_at,
    COALESCE(ap.administration_count, 0) AS administration_count,
    ap.last_administration_at,
    CASE
        WHEN mf.once_only = TRUE AND COALESCE(ap.administration_count, 0) > 0 THEN NULL
        ELSE TIMESTAMPADD(MINUTE, mf.interval_minutes,
                          COALESCE(ap.last_administration_at, mp.first_dose_at))
    END AS next_administration_at
FROM order_item oi
JOIN medication_plan mp ON mp.order_item_id = oi.id
JOIN medication_frequency mf ON mf.code = mp.frequency_code
LEFT JOIN (
    SELECT er.order_item_id,
           COUNT(*) AS administration_count,
           MAX(er.execution_time) AS last_administration_at
    FROM execution_record er
    WHERE er.phase = 'ADMINISTER'
      AND er.status = 'SUCCESS'
      AND NOT EXISTS (
          SELECT 1 FROM execution_record reversal
          WHERE reversal.reversed_execution_id = er.id
      )
    GROUP BY er.order_item_id
) ap ON ap.order_item_id = oi.id;
