SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- Additional finance-screen examples.  These are real charge/deposit facts:
-- patients 1-4 remain OPEN with a negative balance, while patients 5-8 first
-- enter the same negative state and then become RESOLVED after a supplement.
-- A cursor is required because the charge/deposit guards lock admission and
-- MySQL disallows selecting that row in the same INSERT ... SELECT statement.
DROP PROCEDURE IF EXISTS sp_seed_alert_demo;
DELIMITER $$
CREATE PROCEDURE sp_seed_alert_demo()
BEGIN
    DECLARE v_done INT DEFAULT 0;
    DECLARE v_admission BIGINT;
    DECLARE v_department BIGINT;
    DECLARE v_item BIGINT;
    DECLARE v_price DECIMAL(12,2);
    DECLARE v_patient_no VARCHAR(32);
    DECLARE cur CURSOR FOR
      SELECT a.id,a.department_id,p.patient_no
      FROM admission a JOIN patient p ON p.id=a.patient_id
      WHERE p.patient_no IN ('P00001','P00002','P00003','P00004','P00005','P00006','P00007','P00008')
      ORDER BY a.id;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done=1;

    SELECT id,unit_price INTO v_item,v_price FROM medical_item WHERE item_code='EXAM005';
    OPEN cur;
    read_loop: LOOP
      FETCH cur INTO v_admission,v_department,v_patient_no;
      IF v_done=1 THEN LEAVE read_loop; END IF;

      IF NOT EXISTS (SELECT 1 FROM charge_detail WHERE charge_no=CONCAT('ALERTCHG',LPAD(v_admission,8,'0'))) THEN
        INSERT INTO charge_detail(admission_id,execution_id,medical_item_id,department_id,charge_no,charge_type,
          unit_price_snapshot,quantity,amount,note,charged_at)
        VALUES(v_admission,NULL,v_item,v_department,CONCAT('ALERTCHG',LPAD(v_admission,8,'0')),'NORMAL',
          v_price,10,v_price*10,'演示欠费提醒费用',UTC_TIMESTAMP(3)-INTERVAL 1 DAY);
      END IF;

      -- Capture the negative-balance state before the later supplement.
      CALL sp_refresh_account_alert(v_admission,0,NULL);

      IF v_patient_no IN ('P00005','P00006','P00007','P00008')
         AND NOT EXISTS (SELECT 1 FROM deposit_transaction WHERE transaction_no=CONCAT('ALERTDEP',LPAD(v_admission,8,'0'))) THEN
        INSERT INTO deposit_transaction(admission_id,transaction_no,transaction_type,amount,payment_method,note)
        VALUES(v_admission,CONCAT('ALERTDEP',LPAD(v_admission,8,'0')),'SUPPLEMENT',1000.00,'CASH','演示欠费提醒后补交押金');
      END IF;

      CALL sp_refresh_account_alert(v_admission,0,NULL);
    END LOOP;
    CLOSE cur;
END$$
DELIMITER ;
CALL sp_seed_alert_demo();
DROP PROCEDURE sp_seed_alert_demo;
