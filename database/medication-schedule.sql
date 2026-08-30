SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- V10 replaces a manually entered total administration count with a normalized
-- medication regimen. Frequency is reference data; each drug order item owns
-- one immutable plan, while execution_record remains the source of actual doses.
CREATE TABLE medication_frequency (
    code VARCHAR(16) PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    interval_minutes INT NOT NULL,
    once_only BOOLEAN NOT NULL DEFAULT FALSE,
    as_needed BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT ck_medication_frequency_interval CHECK (
        (once_only = TRUE AND interval_minutes = 0)
        OR (once_only = FALSE AND interval_minutes > 0)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO medication_frequency(code, name, interval_minutes, once_only, as_needed) VALUES
('ONCE', '一次', 0, TRUE, FALSE),
('QD', '每日1次', 1440, FALSE, FALSE),
('BID', '每日2次（约每12小时）', 720, FALSE, FALSE),
('TID', '每日3次（约每8小时）', 480, FALSE, FALSE),
('QID', '每日4次（约每6小时）', 360, FALSE, FALSE),
('Q4H', '每4小时1次', 240, FALSE, FALSE),
('Q6H', '每6小时1次', 360, FALSE, FALSE),
('Q8H', '每8小时1次', 480, FALSE, FALSE),
('Q12H', '每12小时1次', 720, FALSE, FALSE),
('PRN6H', '必要时（至少间隔6小时）', 360, FALSE, TRUE);

DELIMITER $$

-- Frequency semantics are reference data, but changing an interval would
-- silently change every existing medication plan that points to the code.
-- Only the active flag may change; new orders can then stop using a retired
-- code while existing plans keep their historical meaning.
DROP TRIGGER IF EXISTS trg_medication_frequency_guard_update$$
CREATE TRIGGER trg_medication_frequency_guard_update
BEFORE UPDATE ON medication_frequency FOR EACH ROW
BEGIN
    IF NOT (NEW.code <=> OLD.code)
       OR NOT (NEW.interval_minutes <=> OLD.interval_minutes)
       OR NOT (NEW.once_only <=> OLD.once_only)
       OR NOT (NEW.as_needed <=> OLD.as_needed) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '已使用的给药频次语义不可修改，请停用后新增频次编码';
    END IF;
END$$

DELIMITER ;

-- A compounding group is one regimen: all of its drug items must share the
-- same frequency. Existing groups are backfilled from their old count shape;
-- no old count is carried into the new model.
ALTER TABLE order_group
    ADD COLUMN frequency_code VARCHAR(16) NULL AFTER order_type;

UPDATE order_group og
JOIN (
    SELECT group_id,
           CASE WHEN MAX(CASE
               WHEN order_type = 'LONG_TERM' OR COALESCE(planned_administration_count, 1) <> 1 THEN 1
               ELSE 0
           END) = 1 THEN 'QD' ELSE 'ONCE' END AS frequency_code
    FROM order_item
    WHERE group_id IS NOT NULL
    GROUP BY group_id
) legacy_group ON legacy_group.group_id = og.id
SET og.frequency_code = legacy_group.frequency_code;

UPDATE order_group SET frequency_code = 'QD' WHERE frequency_code IS NULL;

ALTER TABLE order_group
    MODIFY frequency_code VARCHAR(16) NOT NULL,
    ADD CONSTRAINT fk_order_group_frequency
        FOREIGN KEY (frequency_code) REFERENCES medication_frequency(code);

-- A plan is a 1:0..1 subtype of a drug order item. Draft drug items are
-- allowed briefly before their plan is inserted; submission and execution are
-- blocked by triggers unless the plan exists.
CREATE TABLE medication_plan (
    order_item_id BIGINT PRIMARY KEY,
    frequency_code VARCHAR(16) NOT NULL,
    first_dose_at TIMESTAMP(3) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_medication_plan_item FOREIGN KEY (order_item_id) REFERENCES order_item(id),
    CONSTRAINT fk_medication_plan_frequency FOREIGN KEY (frequency_code) REFERENCES medication_frequency(code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Convert V1-V9 drug rows before removing the old, semantically weak fields.
-- One old dose becomes ONCE; multi-dose temporary rows become QD because the
-- old schema contains no safe information from which to infer a real interval.
INSERT INTO medication_plan(order_item_id, frequency_code, first_dose_at)
SELECT oi.id,
       COALESCE(og.frequency_code,
           CASE
               WHEN oi.order_type = 'LONG_TERM' THEN 'QD'
               WHEN COALESCE(oi.planned_administration_count, 1) = 1 THEN 'ONCE'
               ELSE 'QD'
           END),
       COALESCE(og.start_time, oi.start_time, oi.created_at)
FROM order_item oi
LEFT JOIN order_group og ON og.id = oi.group_id
WHERE oi.item_type_snapshot = 'DRUG';

-- Legacy multi-dose temporary rows had a target count but often no end time.
-- Retain a bounded compatibility window using the conservative QD fallback;
-- the count itself is removed below and is never used as a future completion
-- target. New rows must provide an explicit end time for regular temporary use.
UPDATE order_item oi
JOIN medication_plan mp ON mp.order_item_id = oi.id
JOIN medication_frequency mf ON mf.code = mp.frequency_code
SET oi.end_time = TIMESTAMPADD(MINUTE, mf.interval_minutes * COALESCE(oi.planned_administration_count, 1), oi.start_time)
WHERE oi.order_type = 'TEMPORARY'
  AND mp.frequency_code <> 'ONCE'
  AND oi.end_time IS NULL;

ALTER TABLE order_item
    DROP CHECK ck_order_item_administration_plan,
    DROP COLUMN planned_administration_count,
    DROP COLUMN excess_dispense_reason;

CREATE INDEX idx_medication_plan_first_dose ON medication_plan(first_dose_at, frequency_code);
CREATE INDEX idx_order_item_medication_status ON order_item(item_status, start_time, end_time);

-- A read model exposes actual administrations and the next eligible time. It
-- never stores a target count: administration_count is derived from successful,
-- not-reversed ADMINISTER facts.
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
    mf.as_needed,
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

DELIMITER $$

-- The plan may only extend a drug item and is immutable after creation.
DROP TRIGGER IF EXISTS trg_medication_plan_guard_insert$$
CREATE TRIGGER trg_medication_plan_guard_insert
BEFORE INSERT ON medication_plan FOR EACH ROW
BEGIN
    DECLARE v_item_type VARCHAR(20);
    DECLARE v_order_type VARCHAR(20);
    DECLARE v_item_status VARCHAR(20);
    DECLARE v_start_time TIMESTAMP(3);
    DECLARE v_end_time TIMESTAMP(3);
    DECLARE v_group_id BIGINT;
    DECLARE v_once_only BOOLEAN;
    DECLARE v_group_frequency VARCHAR(16);
    DECLARE v_group_start TIMESTAMP(3);

    SELECT item_type_snapshot, order_type, item_status, start_time, end_time, group_id
      INTO v_item_type, v_order_type, v_item_status, v_start_time, v_end_time, v_group_id
      FROM order_item WHERE id = NEW.order_item_id;
    SELECT once_only INTO v_once_only
      FROM medication_frequency WHERE code = NEW.frequency_code AND active = TRUE;

    IF v_item_type IS NULL OR v_item_type <> 'DRUG' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '只有药品医嘱项目可以建立给药计划';
    END IF;
    IF v_once_only IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '给药频次不存在或已停用';
    END IF;
    IF v_item_status <> 'DRAFT' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '给药计划必须在医嘱草稿阶段建立';
    END IF;
    IF v_order_type = 'TEMPORARY' AND v_once_only = FALSE AND v_end_time IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '临时规律用药必须设置疗程结束时间';
    END IF;
    IF v_order_type = 'LONG_TERM' AND v_once_only = TRUE THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '长期医嘱不能使用一次性给药频次';
    END IF;
    IF v_start_time IS NULL OR NEW.first_dose_at < v_start_time
       OR (v_end_time IS NOT NULL AND NEW.first_dose_at >= v_end_time) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '首次给药时间必须位于医嘱有效时间窗内';
    END IF;
    IF v_group_id IS NOT NULL THEN
        SELECT frequency_code, start_time INTO v_group_frequency, v_group_start
          FROM order_group WHERE id = v_group_id;
        IF v_group_frequency IS NULL OR v_group_frequency <> NEW.frequency_code
           OR NOT (NEW.first_dose_at <=> v_group_start) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '同一配液组必须使用相同频次和首次给药时间';
        END IF;
    END IF;
END$$

DROP TRIGGER IF EXISTS trg_medication_plan_no_update$$
CREATE TRIGGER trg_medication_plan_no_update
BEFORE UPDATE ON medication_plan FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '给药计划不可覆盖修改，请撤销原医嘱后重新开立';
END$$

DROP TRIGGER IF EXISTS trg_medication_plan_no_delete$$
CREATE TRIGGER trg_medication_plan_no_delete
BEFORE DELETE ON medication_plan FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '给药计划不可物理删除';
END$$

-- A drug item can exist as a draft while its child plan is being assembled,
-- but it can never be submitted or executed without that child row.
DROP TRIGGER IF EXISTS trg_order_item_medication_plan_guard_insert$$
CREATE TRIGGER trg_order_item_medication_plan_guard_insert
BEFORE INSERT ON order_item FOR EACH ROW
BEGIN
    IF NEW.item_type_snapshot <> 'DRUG' AND NEW.group_id IS NOT NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '只有药品项目可以加入配液组';
    END IF;
    IF NEW.item_type_snapshot = 'DRUG' AND NEW.item_status <> 'DRAFT' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '药品医嘱必须先建立给药计划';
    END IF;
END$$

DROP TRIGGER IF EXISTS trg_order_item_medication_plan_guard_update$$
CREATE TRIGGER trg_order_item_medication_plan_guard_update
BEFORE UPDATE ON order_item FOR EACH ROW
BEGIN
    IF NEW.item_type_snapshot = 'DRUG' AND NEW.item_status <> 'DRAFT'
       AND NOT EXISTS (SELECT 1 FROM medication_plan WHERE order_item_id = NEW.id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '药品医嘱必须先建立给药计划';
    END IF;
    IF NEW.item_type_snapshot <> 'DRUG'
       AND EXISTS (SELECT 1 FROM medication_plan WHERE order_item_id = NEW.id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '非药品项目不能保留给药计划';
    END IF;
END$$

-- A direct SQL writer cannot bypass the medication plan's time window. The
-- minimum interval between actual doses is checked by the locked service layer
-- because reading execution_record from its own INSERT trigger is forbidden by
-- MySQL; the immutable plan and execution facts remain database-owned.
DROP TRIGGER IF EXISTS trg_medication_execution_plan_guard_insert$$
CREATE TRIGGER trg_medication_execution_plan_guard_insert
BEFORE INSERT ON execution_record FOR EACH ROW
BEGIN
    DECLARE v_item_type VARCHAR(20);
    DECLARE v_frequency VARCHAR(16);
    DECLARE v_first_dose TIMESTAMP(3);
    DECLARE v_end_time TIMESTAMP(3);

    SELECT oi.item_type_snapshot, mp.frequency_code, mp.first_dose_at, oi.end_time
      INTO v_item_type, v_frequency, v_first_dose, v_end_time
      FROM order_item oi LEFT JOIN medication_plan mp ON mp.order_item_id = oi.id
      WHERE oi.id = NEW.order_item_id;
    IF v_item_type IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '执行项目不存在';
    END IF;
    IF NEW.phase IN ('DISPENSE', 'ADMINISTER')
       AND (v_item_type <> 'DRUG' OR v_frequency IS NULL) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '药品执行必须绑定有效的给药计划';
    END IF;
    IF NEW.phase = 'ADMINISTER'
       AND (NEW.execution_time < v_first_dose
            OR (v_end_time IS NOT NULL AND NEW.execution_time >= v_end_time)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '实际给药时间不在医嘱计划时间窗内';
    END IF;
END$$

-- A group frequency is part of the group contract and cannot be changed after
-- any member plan exists.
DROP TRIGGER IF EXISTS trg_order_group_frequency_guard_update$$
CREATE TRIGGER trg_order_group_frequency_guard_update
BEFORE UPDATE ON order_group FOR EACH ROW
BEGIN
    IF NOT (NEW.frequency_code <=> OLD.frequency_code)
       AND EXISTS (SELECT 1 FROM medication_plan mp
                   JOIN order_item oi ON oi.id = mp.order_item_id
                   WHERE oi.group_id = OLD.id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '已有给药计划的配液组不能变更频次';
    END IF;
END$$

DELIMITER ;
