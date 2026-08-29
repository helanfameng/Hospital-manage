-- Optional end-to-end SQL demonstration for the current V1-V5 baseline.
-- Run only in a disposable database whose name ends in _test.
SET time_zone = '+00:00';
SET NAMES utf8mb4;
DELIMITER $$
DROP PROCEDURE IF EXISTS sp_demo_verified_workflow$$
CREATE PROCEDURE sp_demo_verified_workflow()
BEGIN
 DECLARE v_patient BIGINT;
 DECLARE v_bed BIGINT DEFAULT NULL;
 DECLARE v_department BIGINT;
 DECLARE v_doctor BIGINT;
 DECLARE v_user BIGINT DEFAULT NULL;
 DECLARE v_staff BIGINT;
 DECLARE v_admission BIGINT;
 DECLARE v_order BIGINT;
 DECLARE v_line BIGINT;
 DECLARE v_drug BIGINT DEFAULT NULL;
 DECLARE v_dispense BIGINT;
 DECLARE v_administer BIGINT;
 DECLARE v_settlement BIGINT;
 DECLARE v_stock DECIMAL(12,3);
 DECLARE v_price DECIMAL(12,2);
 DECLARE v_item_name VARCHAR(120);
 DECLARE v_stock_unit VARCHAR(20);
 DECLARE v_dose_unit VARCHAR(20);
 DECLARE v_dose_per_stock DECIMAL(12,6);
 DECLARE v_route VARCHAR(64);
 DECLARE v_charge_no VARCHAR(32);
 DECLARE v_tag VARCHAR(32);
 DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;

 IF RIGHT(DATABASE(),5)<>'_test' THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Use a disposable database ending in _test';
 END IF;
 START TRANSACTION;

 SELECT ua.id,ua.staff_id INTO v_user,v_staff FROM user_account ua
   JOIN user_role ur ON ur.user_id=ua.id JOIN role r ON r.id=ur.role_id
   JOIN staff s ON s.id=ua.staff_id
   WHERE r.code='ADMIN' AND r.active AND ua.enabled AND s.active ORDER BY ua.id LIMIT 1;
 IF v_user IS NULL THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Start the application once to bootstrap a test administrator';
 END IF;

 SELECT b.id,d.id,s.id INTO v_bed,v_department,v_doctor FROM bed b
   JOIN room rm ON rm.id=b.room_id JOIN ward w ON w.id=rm.ward_id JOIN department d ON d.id=w.department_id
   JOIN staff s ON s.department_id=d.id AND s.staff_type='DOCTOR' AND s.active
   WHERE b.status='AVAILABLE' AND b.active AND rm.active AND w.active AND d.active
   ORDER BY b.id,s.id LIMIT 1 FOR UPDATE;
 IF v_bed IS NULL THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='An available bed with an active doctor is required';
 END IF;

 SELECT mi.id,mi.stock_quantity,mi.unit_price,mi.name,mi.unit,dp.dose_unit,dp.dose_units_per_stock,
        SUBSTRING_INDEX(dp.allowed_routes,',',1)
   INTO v_drug,v_stock,v_price,v_item_name,v_stock_unit,v_dose_unit,v_dose_per_stock,v_route
   FROM medical_item mi JOIN drug_profile dp ON dp.medical_item_id=mi.id
   WHERE mi.item_type='DRUG' AND mi.active AND mi.billable AND mi.inventory_managed
     AND mi.stock_quantity>=1 AND mi.unit_price>0 AND mi.unit_price<1000
   ORDER BY mi.id LIMIT 1 FOR UPDATE;
 IF v_drug IS NULL THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='A billable drug with stock and a drug profile is required';
 END IF;

 SET v_tag=CONCAT('VERIFIED-',UUID_SHORT());
 INSERT INTO patient(patient_no,name,gender,birth_date,identity_number)
   VALUES(v_tag,'事务验证样本','OTHER','1990-01-01',v_tag);
 SET v_patient=LAST_INSERT_ID();
 CALL sp_admit_patient(v_patient,v_department,v_doctor,v_bed,1000,v_user,v_admission);

 INSERT INTO medical_order(order_no,admission_id,status,start_time,submitted_at,prescribed_by,note)
   VALUES(CONCAT('TMP',UUID_SHORT()),v_admission,'SUBMITTED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),v_staff,
          'VERIFIED: V1-V5 phase-based workflow');
 SET v_order=LAST_INSERT_ID();
 UPDATE medical_order SET order_no=CONCAT(DATE_FORMAT(UTC_TIMESTAMP(),'%Y%m%d'),LPAD(v_order,8,'0')) WHERE id=v_order;
 INSERT INTO order_item(medical_order_id,medical_item_id,dosage,quantity_per_execution,route,instructions,
   item_name_snapshot,item_type_snapshot,unit_snapshot,dose_unit,dose_units_per_stock,price_snapshot,
   billable,inventory_managed,execution_role,order_type,item_status,start_time,planned_administration_count)
 VALUES(v_order,v_drug,1,1,v_route,'SQL端到端验证',v_item_name,'DRUG',v_stock_unit,v_dose_unit,
   v_dose_per_stock,v_price,TRUE,TRUE,'NURSE','TEMPORARY','SUBMITTED',UTC_TIMESTAMP(3),1);
 SET v_line=LAST_INSERT_ID();

 INSERT INTO execution_record(request_no,order_item_id,quantity,status,result_note,executor_id,phase,unit_snapshot,batch_key)
 VALUES(CONCAT('VERIFIED-D-',UUID_SHORT()),v_line,1,'SUCCESS','SQL验证发药',v_staff,'DISPENSE',v_stock_unit,v_tag);
 SET v_dispense=LAST_INSERT_ID();
 UPDATE medical_item SET stock_quantity=stock_quantity-1,version=version+1 WHERE id=v_drug;
 INSERT INTO drug_inventory_transaction(medical_item_id,execution_id,transaction_type,quantity,
   before_quantity,after_quantity,note,operated_by)
 VALUES(v_drug,v_dispense,'DISPENSE',-1,v_stock,v_stock-1,'SQL验证发药',v_user);
 CALL sp_next_charge_number(FALSE,v_charge_no);
 INSERT INTO charge_detail(admission_id,execution_id,medical_item_id,department_id,charge_no,charge_type,
   unit_price_snapshot,quantity,amount,note)
 VALUES(v_admission,v_dispense,v_drug,v_department,v_charge_no,'NORMAL',v_price,1,v_price,'SQL验证发药计费');
 UPDATE order_item SET dispensed_quantity=1,item_status='IN_PROGRESS' WHERE id=v_line;
 UPDATE medical_order SET status='IN_PROGRESS' WHERE id=v_order;

 INSERT INTO execution_record(request_no,order_item_id,quantity,status,result_note,executor_id,phase,unit_snapshot,batch_key)
 VALUES(CONCAT('VERIFIED-A-',UUID_SHORT()),v_line,1,'SUCCESS','SQL验证给药',v_staff,'ADMINISTER',v_dose_unit,v_tag);
 SET v_administer=LAST_INSERT_ID();
 UPDATE order_item SET administered_quantity=1,executed_quantity=1,item_status='COMPLETED' WHERE id=v_line;
 UPDATE medical_order SET status='COMPLETED' WHERE id=v_order;
 CALL sp_refresh_account_alert(v_admission,0,v_user);

 UPDATE admission SET status='PENDING_DISCHARGE',version=version+1 WHERE id=v_admission;
 CALL sp_discharge_settlement(v_admission,v_user,v_settlement);
 IF (SELECT available_balance FROM v_admission_deposit_balance WHERE admission_id=v_admission)<>0
 OR (SELECT total_charge FROM settlement WHERE id=v_settlement)<>v_price
 OR (SELECT refund_amount FROM settlement WHERE id=v_settlement)<>1000-v_price
 OR (SELECT stock_quantity FROM medical_item WHERE id=v_drug)<>v_stock-1
 OR (SELECT status FROM bed WHERE id=v_bed)<>'AVAILABLE'
 OR EXISTS(SELECT 1 FROM bed_usage WHERE admission_id=v_admission AND end_time IS NULL) THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Workflow reconciliation failed; sample rolled back';
 END IF;
 COMMIT;
 SELECT v_tag sample,v_admission admission_id,v_dispense dispense_execution_id,
   v_administer administer_execution_id,v_settlement settlement_id,v_price charge,
   1000-v_price refund,v_stock stock_before,v_stock-1 stock_after,'PASS' reconciliation;
END$$
CALL sp_demo_verified_workflow()$$
DROP PROCEDURE sp_demo_verified_workflow$$
DELIMITER ;
