SET NAMES utf8mb4;
SET time_zone = '+00:00';

DELIMITER $$

-- Admission is one transaction: record, bed occupancy and the opening deposit.
DROP PROCEDURE IF EXISTS sp_admit_patient$$
CREATE PROCEDURE sp_admit_patient(IN p_patient_id BIGINT, IN p_department_id BIGINT,
    IN p_doctor_id BIGINT, IN p_bed_id BIGINT, IN p_initial_deposit DECIMAL(12,2),
    IN p_payment_method VARCHAR(20), IN p_operator_id BIGINT, OUT p_admission_id BIGINT)
BEGIN
    DECLARE v_patient BIGINT DEFAULT NULL;
    DECLARE v_bed BIGINT DEFAULT NULL;
    DECLARE v_doctor BIGINT DEFAULT NULL;
    SELECT id INTO v_patient FROM patient WHERE id=p_patient_id AND active=TRUE FOR UPDATE;
    IF v_patient IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='患者不存在或已停用'; END IF;
    IF p_initial_deposit IS NULL OR p_initial_deposit<0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='初始押金不能为负数'; END IF;
    IF p_initial_deposit>0 AND (p_payment_method IS NULL OR p_payment_method NOT IN ('CASH','CARD','TRANSFER')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='初始押金支付方式不合法'; END IF;
    IF EXISTS(SELECT 1 FROM admission WHERE active_patient_key=p_patient_id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='患者已有有效住院记录'; END IF;
    SELECT b.id INTO v_bed FROM bed b JOIN room r ON r.id=b.room_id JOIN ward w ON w.id=r.ward_id
      JOIN department d ON d.id=w.department_id
      WHERE b.id=p_bed_id AND b.status='AVAILABLE' AND b.active AND r.active AND w.active AND d.active
        AND d.id=p_department_id FOR UPDATE;
    IF v_bed IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='床位不可用或不属于所选科室'; END IF;
    SELECT id INTO v_doctor FROM staff WHERE id=p_doctor_id AND department_id=p_department_id
        AND staff_type='DOCTOR' AND active FOR SHARE;
    IF v_doctor IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='主管医生必须是该科室的在职医生'; END IF;
    INSERT INTO admission(admission_no,patient_id,department_id,attending_doctor_id,current_bed_id,status,created_by)
      VALUES(CONCAT('A',UUID_SHORT()),p_patient_id,p_department_id,p_doctor_id,p_bed_id,'IN_HOSPITAL',p_operator_id);
    SET p_admission_id=LAST_INSERT_ID();
    INSERT INTO bed_usage(admission_id,bed_id,assigned_by) VALUES(p_admission_id,p_bed_id,p_operator_id);
    UPDATE bed SET status='OCCUPIED',version=version+1 WHERE id=p_bed_id;
    IF p_initial_deposit>0 THEN
      INSERT INTO deposit_transaction(admission_id,transaction_no,transaction_type,amount,payment_method,note,operated_by)
        VALUES(p_admission_id,CONCAT('D',UUID_SHORT()),'INITIAL',p_initial_deposit,p_payment_method,'入院初始押金',p_operator_id);
    END IF;
END$$

-- Cross-department transfers require an in-post doctor there and no unfinished orders here.
DROP PROCEDURE IF EXISTS sp_transfer_bed$$
CREATE PROCEDURE sp_transfer_bed(IN p_admission_id BIGINT, IN p_target_bed_id BIGINT,
    IN p_target_doctor_id BIGINT, IN p_reason VARCHAR(500), IN p_operator_id BIGINT)
BEGIN
    DECLARE v_status VARCHAR(24) DEFAULT NULL;
    DECLARE v_old_bed BIGINT;
    DECLARE v_old_dept BIGINT;
    DECLARE v_old_doctor BIGINT;
    DECLARE v_new_dept BIGINT DEFAULT NULL;
    DECLARE v_new_doctor BIGINT DEFAULT NULL;
    SELECT status,current_bed_id,department_id,attending_doctor_id INTO v_status,v_old_bed,v_old_dept,v_old_doctor
      FROM admission WHERE id=p_admission_id FOR UPDATE;
    IF v_status IS NULL OR v_status<>'IN_HOSPITAL' THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='仅住院中的患者可以转床或转科'; END IF;
    IF p_reason IS NULL OR TRIM(p_reason)='' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='转床原因不能为空'; END IF;
    SELECT d.id INTO v_new_dept FROM bed b JOIN room r ON r.id=b.room_id JOIN ward w ON w.id=r.ward_id
      JOIN department d ON d.id=w.department_id
      WHERE b.id=p_target_bed_id AND b.status='AVAILABLE' AND b.active AND r.active AND w.active AND d.active FOR UPDATE;
    IF v_new_dept IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='目标床位不可用'; END IF;
    IF v_new_dept<>v_old_dept AND EXISTS(
      SELECT 1 FROM medical_order WHERE admission_id=p_admission_id AND status IN ('DRAFT','SUBMITTED','IN_PROGRESS')
    ) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='转科前请完成、停止或撤销原科室的未完成医嘱'; END IF;
    SELECT id INTO v_new_doctor FROM staff
      WHERE id=COALESCE(p_target_doctor_id,IF(v_new_dept=v_old_dept,v_old_doctor,NULL))
      AND department_id=v_new_dept AND staff_type='DOCTOR' AND active FOR SHARE;
    IF v_new_doctor IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='请选择目标科室的在职主管医生'; END IF;
    UPDATE bed_usage SET end_time=UTC_TIMESTAMP(3),end_reason='TRANSFER'
      WHERE admission_id=p_admission_id AND end_time IS NULL;
    INSERT INTO bed_usage(admission_id,bed_id,assigned_by) VALUES(p_admission_id,p_target_bed_id,p_operator_id);
    UPDATE bed SET status='AVAILABLE',version=version+1 WHERE id=v_old_bed;
    UPDATE bed SET status='OCCUPIED',version=version+1 WHERE id=p_target_bed_id;
    UPDATE admission SET current_bed_id=p_target_bed_id,department_id=v_new_dept,
      attending_doctor_id=v_new_doctor,version=version+1 WHERE id=p_admission_id;
    INSERT INTO transfer_record(admission_id,transfer_type,from_department_id,to_department_id,from_bed_id,to_bed_id,
      from_doctor_id,to_doctor_id,reason,operated_by)
      VALUES(p_admission_id,IF(v_old_dept=v_new_dept,'BED','BOTH'),v_old_dept,v_new_dept,v_old_bed,p_target_bed_id,
        v_old_doctor,v_new_doctor,p_reason,p_operator_id);
END$$

-- One rule drives alerts: the current available balance against the configured threshold.
DROP PROCEDURE IF EXISTS sp_refresh_account_alert$$
CREATE PROCEDURE sp_refresh_account_alert(IN p_admission BIGINT, IN p_threshold DECIMAL(12,2), IN p_user BIGINT)
BEGIN
    DECLARE v_balance DECIMAL(12,2);
    DECLARE v_status VARCHAR(24);
    SELECT status INTO v_status FROM admission WHERE id=p_admission FOR UPDATE;
    SELECT available_balance INTO v_balance FROM v_admission_deposit_balance WHERE admission_id=p_admission;
    IF v_status NOT IN ('IN_HOSPITAL','PENDING_DISCHARGE') OR v_balance>=p_threshold THEN
      UPDATE account_alert SET status='RESOLVED',resolved_by=p_user,resolved_at=UTC_TIMESTAMP(3)
        WHERE admission_id=p_admission AND status='OPEN';
    ELSEIF EXISTS(SELECT 1 FROM account_alert WHERE admission_id=p_admission AND status='OPEN') THEN
      UPDATE account_alert SET balance_snapshot=v_balance,threshold_snapshot=p_threshold,
        level=IF(v_balance<0,'CRITICAL','WARNING') WHERE admission_id=p_admission AND status='OPEN';
    ELSE
      INSERT INTO account_alert(admission_id,level,balance_snapshot,threshold_snapshot)
        VALUES(p_admission,IF(v_balance<0,'CRITICAL','WARNING'),v_balance,p_threshold);
    END IF;
END$$

DROP PROCEDURE IF EXISTS sp_discharge_settlement$$
CREATE PROCEDURE sp_discharge_settlement(IN p_admission_id BIGINT,IN p_payment_method VARCHAR(20),IN p_operator_id BIGINT,OUT p_settlement_id BIGINT)
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
    IF v_refund>0 AND (p_payment_method IS NULL OR p_payment_method NOT IN ('CASH','CARD','TRANSFER')) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='退款支付方式不合法'; END IF;
    -- Refund precedes the settlement row so the closed-account guard remains universal.
    IF v_refund>0 THEN
      INSERT INTO deposit_transaction(admission_id,transaction_no,transaction_type,amount,payment_method,note,operated_by)
        VALUES(p_admission_id,CONCAT('D',UUID_SHORT()),'REFUND',-v_refund,p_payment_method,'出院结算退款',p_operator_id);
    END IF;
    INSERT INTO settlement(settlement_no,admission_id,total_charge,total_deposit,amount_due,refund_amount,settled_by)
      VALUES(CONCAT('S',UUID_SHORT()),p_admission_id,v_charge,v_deposit,0,v_refund,p_operator_id);
    SET p_settlement_id=LAST_INSERT_ID();
    UPDATE bed_usage SET end_time=UTC_TIMESTAMP(3),end_reason='DISCHARGE' WHERE admission_id=p_admission_id AND end_time IS NULL;
    UPDATE bed SET status='AVAILABLE',version=version+1 WHERE id=v_bed;
    UPDATE admission SET status='DISCHARGED',discharge_time=UTC_TIMESTAMP(3),current_bed_id=NULL,version=version+1 WHERE id=p_admission_id;
    UPDATE account_alert SET status='RESOLVED',resolved_by=p_operator_id,resolved_at=UTC_TIMESTAMP(3)
      WHERE admission_id=p_admission_id AND status='OPEN';
END$$

-- Orders belong to a stay that is currently in hospital.
DROP TRIGGER IF EXISTS trg_order_admission_guard_insert$$
CREATE TRIGGER trg_order_admission_guard_insert BEFORE INSERT ON medical_order FOR EACH ROW
BEGIN
    IF NOT EXISTS (SELECT 1 FROM admission WHERE id = NEW.admission_id AND status = 'IN_HOSPITAL') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '只能为住院中的患者创建医嘱';
    END IF;
END$$

-- Deposits and charges may only move on an open, unsettled account.
DROP TRIGGER IF EXISTS trg_deposit_open_account$$
CREATE TRIGGER trg_deposit_open_account BEFORE INSERT ON deposit_transaction FOR EACH ROW
BEGIN
    DECLARE v_status VARCHAR(24);
    SELECT status INTO v_status FROM admission WHERE id=NEW.admission_id FOR UPDATE;
    IF v_status NOT IN ('IN_HOSPITAL','PENDING_DISCHARGE') OR EXISTS(SELECT 1 FROM settlement WHERE admission_id=NEW.admission_id) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='已结算或无效住院不可变更押金'; END IF;
    IF (NEW.transaction_type IN ('INITIAL','SUPPLEMENT') AND NEW.amount<=0) OR
       (NEW.transaction_type='REFUND' AND NEW.amount>=0) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='押金类型与金额符号不匹配'; END IF;
    IF NEW.transaction_type='REVERSAL' AND NOT EXISTS(SELECT 1 FROM deposit_transaction d
      WHERE d.id=NEW.original_transaction_id AND d.admission_id=NEW.admission_id AND d.transaction_type<>'REVERSAL' AND d.amount=-NEW.amount) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='冲正必须完整引用同一住院的原流水'; END IF;
END$$

-- The department is stamped from the stay, so a later transfer never rewrites revenue history.
DROP TRIGGER IF EXISTS trg_charge_open_account$$
CREATE TRIGGER trg_charge_open_account BEFORE INSERT ON charge_detail FOR EACH ROW
BEGIN
    DECLARE v_status VARCHAR(24);
    DECLARE v_dept BIGINT;
    SELECT status,department_id INTO v_status,v_dept FROM admission WHERE id=NEW.admission_id FOR UPDATE;
    IF v_status NOT IN ('IN_HOSPITAL','PENDING_DISCHARGE') OR EXISTS(SELECT 1 FROM settlement WHERE admission_id=NEW.admission_id) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='已结算或无效住院不可变更费用'; END IF;
    IF NEW.charge_type='NORMAL' THEN
      SET NEW.department_id=v_dept;
      IF NEW.quantity<=0 OR NEW.amount<=0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='正常费用必须为正数'; END IF;
    ELSE
      IF NOT EXISTS(SELECT 1 FROM charge_detail c WHERE c.id=NEW.original_charge_id AND c.admission_id=NEW.admission_id
        AND c.charge_type='NORMAL' AND c.medical_item_id=NEW.medical_item_id AND c.amount=-NEW.amount
        AND c.quantity=-NEW.quantity AND c.unit_price_snapshot=NEW.unit_price_snapshot AND c.department_id=NEW.department_id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='费用冲正必须完整引用原费用'; END IF;
    END IF;
END$$

-- Historical facts are append-only: correct them with a reversing entry, never in place.
DROP TRIGGER IF EXISTS trg_execution_no_delete$$
CREATE TRIGGER trg_execution_no_delete BEFORE DELETE ON execution_record FOR EACH ROW
BEGIN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='执行记录禁止物理删除';
END$$
DROP TRIGGER IF EXISTS trg_execution_record_no_update$$
CREATE TRIGGER trg_execution_record_no_update BEFORE UPDATE ON execution_record FOR EACH ROW
BEGIN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='历史事实禁止覆盖，请使用反向流水';
END$$
DROP TRIGGER IF EXISTS trg_charge_no_delete$$
CREATE TRIGGER trg_charge_no_delete BEFORE DELETE ON charge_detail FOR EACH ROW
BEGIN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='费用记录禁止物理删除，请使用冲正';
END$$
DROP TRIGGER IF EXISTS trg_charge_detail_no_update$$
CREATE TRIGGER trg_charge_detail_no_update BEFORE UPDATE ON charge_detail FOR EACH ROW
BEGIN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='历史事实禁止覆盖，请使用反向流水';
END$$
DROP TRIGGER IF EXISTS trg_deposit_no_delete$$
CREATE TRIGGER trg_deposit_no_delete BEFORE DELETE ON deposit_transaction FOR EACH ROW
BEGIN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='押金流水禁止物理删除，请使用冲正';
END$$
DROP TRIGGER IF EXISTS trg_deposit_transaction_no_update$$
CREATE TRIGGER trg_deposit_transaction_no_update BEFORE UPDATE ON deposit_transaction FOR EACH ROW
BEGIN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='历史事实禁止覆盖，请使用反向流水';
END$$
DROP TRIGGER IF EXISTS trg_drug_inventory_transaction_no_delete$$
CREATE TRIGGER trg_drug_inventory_transaction_no_delete BEFORE DELETE ON drug_inventory_transaction FOR EACH ROW
BEGIN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='历史记录禁止物理删除';
END$$
DROP TRIGGER IF EXISTS trg_drug_inventory_transaction_no_update$$
CREATE TRIGGER trg_drug_inventory_transaction_no_update BEFORE UPDATE ON drug_inventory_transaction FOR EACH ROW
BEGIN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='历史事实禁止覆盖，请使用反向流水';
END$$
DROP TRIGGER IF EXISTS trg_bed_usage_no_delete$$
CREATE TRIGGER trg_bed_usage_no_delete BEFORE DELETE ON bed_usage FOR EACH ROW
BEGIN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='历史记录禁止物理删除';
END$$
DROP TRIGGER IF EXISTS trg_transfer_record_no_delete$$
CREATE TRIGGER trg_transfer_record_no_delete BEFORE DELETE ON transfer_record FOR EACH ROW
BEGIN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='历史记录禁止物理删除';
END$$
DROP TRIGGER IF EXISTS trg_transfer_record_no_update$$
CREATE TRIGGER trg_transfer_record_no_update BEFORE UPDATE ON transfer_record FOR EACH ROW
BEGIN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='历史事实禁止覆盖，请使用反向流水';
END$$
DROP TRIGGER IF EXISTS trg_settlement_no_delete$$
CREATE TRIGGER trg_settlement_no_delete BEFORE DELETE ON settlement FOR EACH ROW
BEGIN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='历史记录禁止物理删除';
END$$
DROP TRIGGER IF EXISTS trg_settlement_no_update$$
CREATE TRIGGER trg_settlement_no_update BEFORE UPDATE ON settlement FOR EACH ROW
BEGIN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='历史事实禁止覆盖，请使用反向流水';
END$$
DROP TRIGGER IF EXISTS trg_audit_log_no_delete$$
CREATE TRIGGER trg_audit_log_no_delete BEFORE DELETE ON audit_log FOR EACH ROW
BEGIN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='历史记录禁止物理删除';
END$$
DROP TRIGGER IF EXISTS trg_audit_log_no_update$$
CREATE TRIGGER trg_audit_log_no_update BEFORE UPDATE ON audit_log FOR EACH ROW
BEGIN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='历史事实禁止覆盖，请使用反向流水';
END$$

DELIMITER ;
