SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- A charge is a historical fact. Its reporting category must not change when
-- the current medical-item catalog is later edited.
ALTER TABLE charge_detail
    ADD COLUMN item_type_snapshot VARCHAR(20) NULL AFTER medical_item_id;

-- V1-V12 charges predate this snapshot. The catalog at migration time is the
-- only safe source available for those rows; all new charges are validated
-- against their execution/order snapshot or the current catalog below.
UPDATE charge_detail c
JOIN medical_item mi ON mi.id = c.medical_item_id
SET c.item_type_snapshot = mi.item_type
WHERE c.item_type_snapshot IS NULL;

ALTER TABLE charge_detail
    MODIFY item_type_snapshot VARCHAR(20) NOT NULL,
    ADD CONSTRAINT ck_charge_item_type_snapshot CHECK (
        item_type_snapshot IN ('DRUG', 'EXAMINATION', 'LAB', 'TREATMENT', 'NURSING', 'DIET', 'OTHER', 'BED_FEE')
    );

DELIMITER $$

-- Normal execution charges inherit the order-item category snapshot. Manual
-- charges have no execution and therefore must snapshot the current catalog.
-- Reversal charges inherit the original charge's category, even if the
-- catalog has changed since the original event.
DROP TRIGGER IF EXISTS trg_charge_provenance_guard_insert$$
CREATE TRIGGER trg_charge_provenance_guard_insert BEFORE INSERT ON charge_detail FOR EACH ROW
BEGIN
    DECLARE v_admission BIGINT DEFAULT NULL;
    DECLARE v_item BIGINT DEFAULT NULL;
    DECLARE v_status VARCHAR(20) DEFAULT NULL;
    DECLARE v_order_item_type VARCHAR(20) DEFAULT NULL;
    DECLARE v_catalog_type VARCHAR(20) DEFAULT NULL;
    DECLARE v_original_type VARCHAR(20) DEFAULT NULL;

    SELECT mo.admission_id, oi.medical_item_id, er.status, oi.item_type_snapshot
      INTO v_admission, v_item, v_status, v_order_item_type
      FROM execution_record er
      JOIN order_item oi ON oi.id = er.order_item_id
      JOIN medical_order mo ON mo.id = oi.medical_order_id
      WHERE er.id = NEW.execution_id;
    SELECT item_type INTO v_catalog_type
      FROM medical_item WHERE id = NEW.medical_item_id;
    SELECT item_type_snapshot INTO v_original_type
      FROM charge_detail WHERE id = NEW.original_charge_id;

    IF NEW.execution_id IS NOT NULL AND
       (v_admission IS NULL OR v_admission <> NEW.admission_id
        OR v_item <> NEW.medical_item_id
        OR NOT (NEW.item_type_snapshot <=> v_order_item_type)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '费用来源执行与住院、医疗项目或类别快照不一致';
    END IF;
    IF NEW.charge_type = 'NORMAL' AND NEW.original_charge_id IS NOT NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '正常费用不能引用冲正原费用';
    END IF;
    IF NEW.charge_type = 'NORMAL' AND NEW.execution_id IS NULL
       AND NOT (NEW.item_type_snapshot <=> v_catalog_type) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '手工费用类别快照必须匹配当前医疗项目';
    END IF;
    IF NEW.charge_type = 'REVERSAL' AND
       (NEW.original_charge_id IS NULL OR v_original_type IS NULL
        OR NOT (NEW.item_type_snapshot <=> v_original_type)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '费用冲正必须沿用原费用类别快照';
    END IF;

    IF NEW.execution_id IS NOT NULL AND NEW.charge_type = 'NORMAL'
       AND v_status <> 'SUCCESS' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '正常费用只能来源于成功执行';
    END IF;
    IF NEW.charge_type = 'REVERSAL' AND NEW.original_charge_id IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '费用冲正必须引用原费用';
    END IF;
END$$

-- Settlement detail is a materialized historical breakdown. Recreate the
-- procedure so it groups by the charge snapshot rather than the mutable
-- medical-item catalog.
DROP PROCEDURE IF EXISTS sp_discharge_settlement$$
CREATE PROCEDURE sp_discharge_settlement(IN p_admission_id BIGINT,IN p_operator_id BIGINT,OUT p_settlement_id BIGINT)
BEGIN
    DECLARE v_status VARCHAR(24) DEFAULT NULL;
    DECLARE v_bed BIGINT;
    DECLARE v_charge DECIMAL(12,2);
    DECLARE v_deposit DECIMAL(12,2);
    DECLARE v_refund DECIMAL(12,2);
    SELECT status,current_bed_id INTO v_status,v_bed FROM admission WHERE id=p_admission_id FOR UPDATE;
    IF v_status IS NULL OR v_status<>'PENDING_DISCHARGE' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='请先申请出院，且不得重复结算'; END IF;
    IF EXISTS(SELECT 1 FROM medical_order WHERE admission_id=p_admission_id AND status IN ('DRAFT','SUBMITTED','IN_PROGRESS')) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='仍有未完成医嘱，不能结算'; END IF;
    IF EXISTS(SELECT 1 FROM settlement WHERE admission_id=p_admission_id) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='该次住院已经结算'; END IF;
    SELECT total_charge,deposit_balance INTO v_charge,v_deposit FROM v_admission_deposit_balance WHERE admission_id=p_admission_id;
    IF v_charge<0 OR v_deposit<0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='账目存在异常负余额，请先核对'; END IF;
    IF v_charge>v_deposit THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='押金不足，请先补缴费用'; END IF;
    SET v_refund=v_deposit-v_charge;
    -- Refund precedes the settlement row so the closed-account guard remains universal.
    IF v_refund>0 THEN
      INSERT INTO deposit_transaction(admission_id,transaction_no,transaction_type,amount,payment_method,note,operated_by)
        VALUES(p_admission_id,CONCAT('D',UUID_SHORT()),'REFUND',-v_refund,'CASH','出院结算退款',p_operator_id);
    END IF;
    INSERT INTO settlement(settlement_no,admission_id,total_charge,total_deposit,amount_due,refund_amount,settled_by)
      VALUES(CONCAT('S',UUID_SHORT()),p_admission_id,v_charge,v_deposit,0,v_refund,p_operator_id);
    SET p_settlement_id=LAST_INSERT_ID();
    INSERT INTO settlement_detail(settlement_id,category,amount,item_count)
      SELECT p_settlement_id,c.item_type_snapshot,SUM(c.amount),COUNT(*) FROM charge_detail c
      WHERE c.admission_id=p_admission_id GROUP BY c.item_type_snapshot;
    UPDATE bed_usage SET end_time=UTC_TIMESTAMP(3),end_reason='DISCHARGE' WHERE admission_id=p_admission_id AND end_time IS NULL;
    UPDATE bed SET status='AVAILABLE',version=version+1 WHERE id=v_bed;
    UPDATE admission SET status='DISCHARGED',discharge_time=UTC_TIMESTAMP(3),current_bed_id=NULL,version=version+1 WHERE id=p_admission_id;
    UPDATE account_alert SET status='RESOLVED',resolved_by=p_operator_id,resolved_at=UTC_TIMESTAMP(3)
      WHERE admission_id=p_admission_id AND status='OPEN';
END$$

-- Revenue categories are also historical charge facts, not live catalog data.
DROP VIEW IF EXISTS v_admission_charge_summary$$
CREATE VIEW v_admission_charge_summary AS
SELECT
    a.id AS admission_id,
    a.admission_no,
    COALESCE(SUM(c.amount), 0.00) AS total_charge,
    COALESCE(SUM(CASE WHEN c.item_type_snapshot = 'DRUG' THEN c.amount ELSE 0 END), 0.00) AS drug_charge,
    COALESCE(SUM(CASE WHEN c.item_type_snapshot = 'EXAMINATION' THEN c.amount ELSE 0 END), 0.00) AS examination_charge,
    COALESCE(SUM(CASE WHEN c.item_type_snapshot = 'LAB' THEN c.amount ELSE 0 END), 0.00) AS lab_charge,
    COALESCE(SUM(CASE WHEN c.item_type_snapshot = 'TREATMENT' THEN c.amount ELSE 0 END), 0.00) AS treatment_charge,
    COALESCE(SUM(CASE WHEN c.item_type_snapshot = 'BED_FEE' THEN c.amount ELSE 0 END), 0.00) AS bed_charge
FROM admission a
LEFT JOIN charge_detail c ON c.admission_id = a.id
GROUP BY a.id, a.admission_no$$

DROP VIEW IF EXISTS v_department_revenue$$
CREATE VIEW v_department_revenue AS
SELECT
    d.id AS department_id,
    d.name AS department_name,
    DATE(c.charged_at) AS revenue_date,
    c.item_type_snapshot AS item_type,
    COUNT(*) AS charge_count,
    SUM(c.amount) AS revenue
FROM charge_detail c
JOIN department d ON d.id = c.department_id
GROUP BY d.id, d.name, DATE(c.charged_at), c.item_type_snapshot$$

DELIMITER ;
