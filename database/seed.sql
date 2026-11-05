SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- ---------------------------------------------------------------------------
-- Part 1: reference data. The system cannot operate without these rows.
-- ---------------------------------------------------------------------------

INSERT INTO role(code, name) VALUES
('ADMIN', '系统管理员'),
('ADMISSION', '住院处人员'),
('DOCTOR', '医生'),
('NURSE', '护士'),
('PHARMACY', '药房人员'),
('CASHIER', '收费人员');

INSERT INTO permission(code, name) VALUES
('ADMIN_MANAGE', '系统管理'),
('PATIENT_READ', '患者查询'),
('PATIENT_WRITE', '患者维护'),
('ADMISSION_MANAGE', '入院与床位管理'),
('ORDER_WRITE', '医嘱开立'),
('ORDER_EXECUTE', '医嘱执行'),
('MEDICATION_ADMINISTER', '住院给药记录'),
('PHARMACY_EXECUTE', '药品发退'),
('DEPOSIT_MANAGE', '押金管理'),
('SETTLEMENT_MANAGE', '出院结算'),
('STATISTICS_READ', '统计查询'),
('AUDIT_READ', '审计查询');

INSERT INTO role_permission(role_id, permission_id)
SELECT r.id, p.id FROM role r CROSS JOIN permission p WHERE r.code = 'ADMIN';
INSERT INTO role_permission(role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p ON p.code IN ('PATIENT_READ','PATIENT_WRITE','ADMISSION_MANAGE','STATISTICS_READ')
WHERE r.code = 'ADMISSION';
INSERT INTO role_permission(role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p ON p.code IN ('PATIENT_READ','ORDER_WRITE','ORDER_EXECUTE','STATISTICS_READ')
WHERE r.code = 'DOCTOR';
INSERT INTO role_permission(role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p ON p.code IN ('PATIENT_READ','ORDER_EXECUTE','MEDICATION_ADMINISTER')
WHERE r.code = 'NURSE';
INSERT INTO role_permission(role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p ON p.code IN ('PATIENT_READ','ORDER_EXECUTE','PHARMACY_EXECUTE')
WHERE r.code = 'PHARMACY';
INSERT INTO role_permission(role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p ON p.code IN ('PATIENT_READ','DEPOSIT_MANAGE','SETTLEMENT_MANAGE','STATISTICS_READ')
WHERE r.code = 'CASHIER';

INSERT INTO department(code, name, description) VALUES
('CARD', '心内科', '心血管疾病住院诊疗'),
('RESP', '呼吸内科', '呼吸系统疾病住院诊疗'),
('GENS', '普通外科', '普通外科住院诊疗'),
('ORTH', '骨科', '骨科住院诊疗'),
('NEUR', '神经内科', '神经系统疾病住院诊疗');

-- Two wards per department so that cross-ward transfers can be demonstrated.
INSERT INTO ward(department_id, code, name)
SELECT id, CONCAT(code, '-W1'), CONCAT(name, '一病区') FROM department;
INSERT INTO ward(department_id, code, name)
SELECT id, CONCAT(code, '-W2'), CONCAT(name, '二病区') FROM department;

INSERT INTO room(ward_id, room_no, room_type)
SELECT w.id, CONCAT(LEFT(w.code, 4), '-', n.n), IF(n.n <= 2, 'DOUBLE', 'GENERAL')
FROM ward w
CROSS JOIN (SELECT 101 AS n UNION ALL SELECT 102 UNION ALL SELECT 103 UNION ALL SELECT 104 UNION ALL SELECT 105) n
WHERE w.code LIKE '%-W1';
INSERT INTO room(ward_id, room_no, room_type)
SELECT w.id, CONCAT(LEFT(w.code, 4), '-', n.n), 'DOUBLE'
FROM ward w
CROSS JOIN (SELECT 201 AS n UNION ALL SELECT 202 UNION ALL SELECT 203 UNION ALL SELECT 204 UNION ALL SELECT 205) n
WHERE w.code LIKE '%-W2';

INSERT INTO bed(room_id, bed_no)
SELECT r.id, b.n FROM room r CROSS JOIN (SELECT 'A' AS n UNION ALL SELECT 'B') b;

-- Two doctors and two nurses per department support transfers between attending doctors.
INSERT INTO staff(staff_no, name, department_id, staff_type, phone)
SELECT CONCAT('DOC', LPAD(d.id, 3, '0')), CONCAT(d.name, '医生一'), d.id, 'DOCTOR', CONCAT('1380000', LPAD(d.id, 4, '0'))
FROM department d;
INSERT INTO staff(staff_no, name, department_id, staff_type, phone)
SELECT CONCAT('DOC', LPAD(d.id + 100, 3, '0')), CONCAT(d.name, '医生二'), d.id, 'DOCTOR', CONCAT('1380100', LPAD(d.id, 4, '0'))
FROM department d;
INSERT INTO staff(staff_no, name, department_id, staff_type, phone)
SELECT CONCAT('NUR', LPAD(d.id, 3, '0')), CONCAT(d.name, '护士一'), d.id, 'NURSE', CONCAT('1390000', LPAD(d.id, 4, '0'))
FROM department d;
INSERT INTO staff(staff_no, name, department_id, staff_type, phone)
SELECT CONCAT('NUR', LPAD(d.id + 100, 3, '0')), CONCAT(d.name, '护士二'), d.id, 'NURSE', CONCAT('1390100', LPAD(d.id, 4, '0'))
FROM department d;

INSERT INTO staff(staff_no, name, department_id, staff_type) VALUES
('ADM001', '系统管理员', NULL, 'ADMIN'),
('INP001', '住院处一号', NULL, 'ADMISSION'),
('INP002', '住院处二号', NULL, 'ADMISSION'),
('PHA001', '药房一号', NULL, 'PHARMACY'),
('PHA002', '药房二号', NULL, 'PHARMACY'),
('CAS001', '收费处一号', NULL, 'CASHIER'),
('CAS002', '收费处二号', NULL, 'CASHIER');

-- Catalog. billable=FALSE marks instructions that are never charged automatically.
-- inventory_managed=TRUE marks stock-controlled drugs.
INSERT INTO medical_item(item_code,name,item_type,unit,unit_price,specification,dosage_form,stock_quantity,low_stock_threshold,billable,inventory_managed,execution_role,allow_fractional_quantity) VALUES
('DRUG001','阿莫西林胶囊','DRUG','盒',28.50,'0.25g×24粒','胶囊',500,50,TRUE,TRUE,'NURSE',FALSE),
('DRUG002','氯化钠注射液','DRUG','袋',8.60,'500mL/袋','注射液',800,100,TRUE,TRUE,'NURSE',FALSE),
('DRUG003','布洛芬缓释胶囊','DRUG','盒',19.80,'0.3g×20粒','胶囊',300,30,TRUE,TRUE,'NURSE',FALSE),
('DRUG004','头孢曲松钠','DRUG','瓶',12.50,'2g/瓶','注射剂',400,40,TRUE,TRUE,'NURSE',FALSE),
('DRUG005','复方甘草片','DRUG','瓶',15.20,'100片/瓶','片剂',180,20,TRUE,TRUE,'NURSE',FALSE),
('DRUG006','阿莫西林克拉维酸钾片','DRUG','盒',46.80,'875mg/125mg×14片','片剂',320,30,TRUE,TRUE,'NURSE',FALSE),
('DRUG007','对乙酰氨基酚片','DRUG','盒',12.80,'0.5g×20片','片剂',260,30,TRUE,TRUE,'NURSE',FALSE),
('DRUG008','甘精胰岛素注射液','DRUG','支',98.00,'3mL:300单位','注射剂',120,15,TRUE,TRUE,'NURSE',FALSE),
('EXAM001','腹部平片','EXAMINATION','次',80.00,'立位腹部平片','影像检查',NULL,NULL,TRUE,FALSE,'DOCTOR',FALSE),
('EXAM002','胸部X光（正位）','EXAMINATION','次',120.00,'数字化摄影','影像检查',NULL,NULL,TRUE,FALSE,'DOCTOR',FALSE),
('EXAM003','十二导联心电图','EXAMINATION','次',35.00,'常规十二导联','功能检查',NULL,NULL,TRUE,FALSE,'NURSE',FALSE),
('EXAM004','腹部超声','EXAMINATION','次',180.00,'肝胆胰脾双肾','超声检查',NULL,NULL,TRUE,FALSE,'DOCTOR',FALSE),
('EXAM005','胸部CT平扫','EXAMINATION','次',360.00,'胸部薄层扫描','影像检查',NULL,NULL,TRUE,FALSE,'DOCTOR',FALSE),
('LAB001','血常规','LAB','次',25.00,'全血细胞分析','检验',NULL,NULL,TRUE,FALSE,'NURSE',FALSE),
('LAB002','C反应蛋白','LAB','次',38.00,'CRP定量','检验',NULL,NULL,TRUE,FALSE,'NURSE',FALSE),
('LAB003','血培养及药敏','LAB','次',160.00,'需氧+厌氧培养','检验',NULL,NULL,TRUE,FALSE,'NURSE',FALSE),
('LAB004','肝功能检查','LAB','次',82.00,'ALT、AST、胆红素等','检验',NULL,NULL,TRUE,FALSE,'NURSE',FALSE),
('LAB005','肾功能检查','LAB','次',65.00,'肌酐、尿素氮、尿酸','检验',NULL,NULL,TRUE,FALSE,'NURSE',FALSE),
('LAB006','空腹血糖','LAB','次',8.00,'葡萄糖测定','检验',NULL,NULL,TRUE,FALSE,'NURSE',FALSE),
('LAB007','凝血功能','LAB','次',68.00,'PT、APTT、INR等','检验',NULL,NULL,TRUE,FALSE,'NURSE',FALSE),
('TREAT001','静脉输液','TREATMENT','次',22.00,'外周静脉通路','治疗',NULL,NULL,TRUE,FALSE,'NURSE',FALSE),
('TREAT002','换药','TREATMENT','次',45.00,'伤口换药','治疗',NULL,NULL,TRUE,FALSE,'NURSE',FALSE),
('TREAT003','低流量吸氧','TREATMENT','小时',6.00,'鼻导管吸氧','治疗',NULL,NULL,TRUE,FALSE,'NURSE',TRUE),
('TREAT004','雾化吸入治疗','TREATMENT','次',18.00,'氧驱动雾化','治疗',NULL,NULL,TRUE,FALSE,'NURSE',FALSE),
('NURS001','一级护理','NURSING','项',0,'密切观察病情','护理',NULL,NULL,FALSE,FALSE,'NURSE',FALSE),
('NURS002','二级护理','NURSING','项',0,'定时巡视','护理',NULL,NULL,FALSE,FALSE,'NURSE',FALSE),
('NURS003','三级护理','NURSING','项',0,'常规护理','护理',NULL,NULL,FALSE,FALSE,'NURSE',FALSE),
('NURS004','生命体征观察','NURSING','项',0,'体温、脉搏、呼吸、血压','护理',NULL,NULL,FALSE,FALSE,'NURSE',FALSE),
('NURS005','静脉输液护理','NURSING','项',0,'巡视输液及穿刺部位','护理',NULL,NULL,FALSE,FALSE,'NURSE',FALSE),
('NURS006','跌倒风险防护','NURSING','项',0,'高风险患者安全护理','护理',NULL,NULL,FALSE,FALSE,'NURSE',FALSE),
('DIET001','普通饮食','DIET','项',0,NULL,'饮食',NULL,NULL,FALSE,FALSE,'NURSE',FALSE),
('DIET002','流质饮食','DIET','项',0,NULL,'饮食',NULL,NULL,FALSE,FALSE,'NURSE',FALSE),
('DIET003','禁食','DIET','项',0,NULL,'饮食',NULL,NULL,FALSE,FALSE,'NURSE',FALSE),
('DIET004','低盐饮食','DIET','项',0,NULL,'饮食',NULL,NULL,FALSE,FALSE,'NURSE',FALSE),
('DIET005','糖尿病饮食','DIET','项',0,NULL,'饮食',NULL,NULL,FALSE,FALSE,'NURSE',FALSE),
('DIET006','低脂饮食','DIET','项',0,'限制高脂食物','饮食',NULL,NULL,FALSE,FALSE,'NURSE',FALSE),
('OTHER001','病情观察','OTHER','项',0,'记录病情变化','观察',NULL,NULL,FALSE,FALSE,'NURSE',FALSE),
('OTHER002','治疗效果评估','OTHER','项',0,'评估治疗反应','观察',NULL,NULL,FALSE,FALSE,'DOCTOR',FALSE),
('OTHER003','出入量记录','OTHER','项',0,'记录24小时液体出入量','观察',NULL,NULL,FALSE,FALSE,'NURSE',FALSE),
('BED001','普通床位费','BED_FEE','床日',50.00,NULL,NULL,NULL,NULL,TRUE,FALSE,'NURSE',FALSE);

-- Stock-to-dose conversions are stated explicitly, never inferred from the specification text.
INSERT INTO drug_profile(medical_item_id, dose_unit, dose_units_per_stock, allowed_routes)
SELECT id,'粒',24,'口服' FROM medical_item WHERE item_code='DRUG001';
INSERT INTO drug_profile(medical_item_id, dose_unit, dose_units_per_stock, allowed_routes)
SELECT id,'mL',500,'静脉滴注' FROM medical_item WHERE item_code='DRUG002';
INSERT INTO drug_profile(medical_item_id, dose_unit, dose_units_per_stock, allowed_routes)
SELECT id,'粒',20,'口服' FROM medical_item WHERE item_code='DRUG003';
INSERT INTO drug_profile(medical_item_id, dose_unit, dose_units_per_stock, allowed_routes)
SELECT id,'g',2,'静脉滴注,静脉注射' FROM medical_item WHERE item_code='DRUG004';
INSERT INTO drug_profile(medical_item_id, dose_unit, dose_units_per_stock, allowed_routes)
SELECT id,'片',100,'口服' FROM medical_item WHERE item_code='DRUG005';
INSERT INTO drug_profile(medical_item_id, dose_unit, dose_units_per_stock, allowed_routes)
SELECT id,'片',14,'口服' FROM medical_item WHERE item_code='DRUG006';
INSERT INTO drug_profile(medical_item_id, dose_unit, dose_units_per_stock, allowed_routes)
SELECT id,'片',20,'口服' FROM medical_item WHERE item_code='DRUG007';
INSERT INTO drug_profile(medical_item_id, dose_unit, dose_units_per_stock, allowed_routes)
SELECT id,'单位',300,'皮下注射' FROM medical_item WHERE item_code='DRUG008';

-- ---------------------------------------------------------------------------
-- Part 2: demonstration data, 30 patients.
-- Every row is written explicitly so the figures can be reconciled by hand.
-- Order of operations respects the workflow triggers:
--   orders require IN_HOSPITAL, charges and deposits require an unsettled account,
--   and settlement runs last through sp_discharge_settlement.
-- ---------------------------------------------------------------------------

INSERT INTO patient(patient_no,name,gender,birth_date,identity_number,phone,address,emergency_contact,emergency_phone) VALUES
('P00001','陈建国','MALE','1952-03-14','310101195203140011','13800010001','示范市健康路1号','陈晓明','13900010001'),
('P00002','李秀兰','FEMALE','1958-07-22','310101195807220022','13800010002','示范市健康路2号','李强','13900010002'),
('P00003','王志强','MALE','1965-11-03','310101196511030033','13800010003','示范市健康路3号','王丽','13900010003'),
('P00004','张淑芬','FEMALE','1949-01-19','310101194901190044','13800010004','示范市健康路4号','张伟','13900010004'),
('P00005','刘国庆','MALE','1970-10-01','310101197010010055','13800010005','示范市健康路5号','刘洋','13900010005'),
('P00006','赵敏','FEMALE','1983-05-28','310101198305280066','13800010006','示范市健康路6号','赵磊','13900010006'),
('P00007','孙德海','MALE','1946-09-12','310101194609120077','13800010007','示范市健康路7号','孙倩','13900010007'),
('P00008','周桂英','FEMALE','1955-12-30','310101195512300088','13800010008','示范市健康路8号','周涛','13900010008'),
('P00009','吴建华','MALE','1962-04-07','310101196204070099','13800010009','示范市健康路9号','吴敏','13900010009'),
('P00010','郑小燕','FEMALE','1990-08-15','310101199008150101','13800010010','示范市健康路10号','郑刚','13900010010'),
('P00011','冯长海','MALE','1943-02-26','310101194302260112','13800010011','示范市健康路11号','冯静','13900010011'),
('P00012','陈美玲','FEMALE','1976-06-18','310101197606180123','13800010012','示范市健康路12号','陈勇','13900010012'),
('P00013','褚文彬','MALE','1968-03-09','310101196803090134','13800010013','示范市健康路13号','褚颖','13900010013'),
('P00014','卫红梅','FEMALE','1959-10-24','310101195910240145','13800010014','示范市健康路14号','卫东','13900010014'),
('P00015','蒋志明','MALE','1951-07-05','310101195107050156','13800010015','示范市健康路15号','蒋琳','13900010015'),
('P00016','沈玉珍','FEMALE','1947-11-11','310101194711110167','13800010016','示范市健康路16号','沈鹏','13900010016'),
('P00017','韩振国','MALE','1972-01-30','310101197201300178','13800010017','示范市健康路17号','韩雪','13900010017'),
('P00018','杨春梅','FEMALE','1985-09-08','310101198509080189','13800010018','示范市健康路18号','杨帆','13900010018'),
('P00019','朱国平','MALE','1964-05-16','310101196405160190','13800010019','示范市健康路19号','朱婷','13900010019'),
('P00020','秦淑华','FEMALE','1953-08-21','310101195308210201','13800010020','示范市健康路20号','秦峰','13900010020'),
('P00021','尤伟民','MALE','1979-12-02','310101197912020212','13800010021','示范市健康路21号','尤佳','13900010021'),
('P00022','许桂芳','FEMALE','1961-04-14','310101196104140223','13800010022','示范市健康路22号','许亮','13900010022'),
('P00023','何建军','MALE','1957-02-07','310101195702070234','13800010023','示范市健康路23号','何芳','13900010023'),
('P00024','吕小娟','FEMALE','1988-10-19','310101198810190245','13800010024','示范市健康路24号','吕波','13900010024'),
('P00025','施长春','MALE','1944-06-25','310101194406250256','13800010025','示范市健康路25号','施红','13900010025'),
('P00026','张丽华','FEMALE','1973-03-31','310101197303310267','13800010026','示范市健康路26号','张野','13900010026'),
('P00027','孔繁荣','MALE','1966-08-09','310101196608090278','13800010027','示范市健康路27号','孔萍','13900010027'),
('P00028','曹秀云','FEMALE','1950-05-13','310101195005130289','13800010028','示范市健康路28号','曹军','13900010028'),
('P00029','严振华','MALE','1981-11-27','310101198111270290','13800010029','示范市健康路29号','严丹','13900010029'),
('P00030','华金凤','FEMALE','1954-09-04','310101195409040301','13800010030','示范市健康路30号','华磊','13900010030');

-- Admissions are created through the real procedure so beds, deposits and history stay consistent.
-- Patients 1-24 stay in hospital, 25-26 await discharge, 27-28 will be settled, 29-30 are cancelled.
DROP PROCEDURE IF EXISTS sp_seed_admissions;
DELIMITER $$
CREATE PROCEDURE sp_seed_admissions()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE v_patient BIGINT;
    DECLARE v_department BIGINT;
    DECLARE v_doctor BIGINT;
    DECLARE v_bed BIGINT;
    DECLARE v_admission BIGINT;
    DECLARE v_deposit DECIMAL(12,2);
    WHILE i <= 28 DO
        SELECT id INTO v_patient FROM patient WHERE patient_no = CONCAT('P', LPAD(i, 5, '0'));
        SET v_department = 1 + MOD(i - 1, 5);
        SELECT id INTO v_doctor FROM staff
          WHERE department_id = v_department AND staff_type = 'DOCTOR' AND active ORDER BY id LIMIT 1;
        -- Everyone lands in ward one; the transfer sample below moves one patient to ward two.
        SELECT b.id INTO v_bed
          FROM bed b JOIN room r ON r.id = b.room_id JOIN ward w ON w.id = r.ward_id
         WHERE w.department_id = v_department AND w.code LIKE '%-W1' AND b.status = 'AVAILABLE'
         ORDER BY b.id LIMIT 1;
        -- Patients 9 and 18 are deliberately underfunded to demonstrate the overdue alert.
        SET v_deposit = CASE WHEN i IN (9,18) THEN 60.00 ELSE 3000.00 END;
        CALL sp_admit_patient(v_patient, v_department, v_doctor, v_bed, v_deposit, 'CASH', NULL, v_admission);
        SET i = i + 1;
    END WHILE;
END$$
DELIMITER ;
CALL sp_seed_admissions();
DROP PROCEDURE sp_seed_admissions;

-- Cancelled admissions never occupied a bed and hold no financial records.
INSERT INTO admission(admission_no,patient_id,department_id,attending_doctor_id,status,admission_time,diagnosis_summary)
SELECT 'A20260829000029',p.id,1,(SELECT id FROM staff WHERE staff_no='DOC001'),'CANCELLED',
       UTC_TIMESTAMP(3)-INTERVAL 2 DAY,'登记后患者本人要求取消入院'
FROM patient p WHERE p.patient_no='P00029';
INSERT INTO admission(admission_no,patient_id,department_id,attending_doctor_id,status,admission_time,diagnosis_summary)
SELECT 'A20260829000030',p.id,2,(SELECT id FROM staff WHERE staff_no='DOC002'),'CANCELLED',
       UTC_TIMESTAMP(3)-INTERVAL 1 DAY,'重复登记，已作废'
FROM patient p WHERE p.patient_no='P00030';

UPDATE admission a JOIN patient p ON p.id=a.patient_id
SET a.diagnosis_summary=CASE
    WHEN MOD(p.id,5)=1 THEN '冠状动脉粥样硬化性心脏病'
    WHEN MOD(p.id,5)=2 THEN '社区获得性肺炎'
    WHEN MOD(p.id,5)=3 THEN '急性阑尾炎术后'
    WHEN MOD(p.id,5)=4 THEN '腰椎间盘突出症'
    ELSE '短暂性脑缺血发作' END
WHERE a.status='IN_HOSPITAL' AND a.diagnosis_summary IS NULL;

-- ---------------------------------------------------------------------------
-- Transfer sample: patient 1 moves from ward one to ward two within cardiology.
-- sp_transfer_bed keeps bed_usage, bed status, admission and transfer_record aligned.
-- ---------------------------------------------------------------------------
SET @transfer_admission = (SELECT a.id FROM admission a JOIN patient p ON p.id=a.patient_id WHERE p.patient_no='P00001');
SET @ward2_bed = (SELECT b.id FROM bed b JOIN room r ON r.id=b.room_id JOIN ward w ON w.id=r.ward_id
                  WHERE w.code='CARD-W2' AND b.status='AVAILABLE' ORDER BY b.id LIMIT 1);
CALL sp_transfer_bed(@transfer_admission, @ward2_bed, NULL, '同科室二病区床位更适合监护', NULL);

-- ---------------------------------------------------------------------------
-- Order templates. created_by is populated by TemplateBootstrap after the
-- admin account exists, so no template rows are inserted here.
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- Orders. Long-term versus temporary is a property of each item, never of the header.
-- Order numbers follow yyyyMMdd + zero-padded database id, matching OrderNumber.
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_seed_orders;
DELIMITER $$
CREATE PROCEDURE sp_seed_orders()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE v_admission BIGINT;
    DECLARE v_doctor BIGINT;
    DECLARE v_nurse BIGINT;
    DECLARE v_department BIGINT;
    DECLARE v_order BIGINT;
    DECLARE v_line BIGINT;
    DECLARE v_execution BIGINT;
    DECLARE v_item BIGINT;
    DECLARE v_price DECIMAL(12,2);
    DECLARE v_stock DECIMAL(12,3);
    DECLARE v_charge VARCHAR(32);
    DECLARE v_status VARCHAR(24);
    DECLARE v_item_code VARCHAR(32);
    DECLARE v_item_type VARCHAR(20);
    DECLARE v_unit VARCHAR(20);

    WHILE i <= 28 DO
        SELECT a.id, a.attending_doctor_id, a.department_id INTO v_admission, v_doctor, v_department
          FROM admission a JOIN patient p ON p.id = a.patient_id
         WHERE p.patient_no = CONCAT('P', LPAD(i, 5, '0'));
        SELECT id INTO v_nurse FROM staff WHERE department_id=v_department AND staff_type='NURSE' AND active ORDER BY id LIMIT 1;

        -- Rotate through the catalog so every item type appears in the demonstration set.
        SET v_item_code = ELT(1 + MOD(i - 1, 7), 'LAB001','EXAM003','TREAT002','LAB004','EXAM002','LAB006','TREAT004');
        SELECT id, unit_price, item_type, unit INTO v_item, v_price, v_item_type, v_unit
          FROM medical_item WHERE item_code = v_item_code;

        -- Order one: a completed non-drug item, charged once at PERFORM.
        INSERT INTO medical_order(order_no,admission_id,status,start_time,submitted_at,note,prescribed_by)
          VALUES(CONCAT('TMP',UUID_SHORT()),v_admission,'COMPLETED',
                 UTC_TIMESTAMP(3)-INTERVAL 2 DAY,UTC_TIMESTAMP(3)-INTERVAL 2 DAY,'入院常规项目',v_doctor);
        SET v_order = LAST_INSERT_ID();
        UPDATE medical_order SET order_no=CONCAT(DATE_FORMAT(DATE_ADD(created_at,INTERVAL 8 HOUR),'%Y%m%d'),LPAD(v_order,8,'0'))
          WHERE id=v_order;
        INSERT INTO order_item(medical_order_id,medical_item_id,execution_quantity_per_operation,instructions,
            item_name_snapshot,item_type_snapshot,unit_snapshot,price_snapshot,billable,inventory_managed,
            execution_role,order_type,item_status,effective_start_time,executed_quantity)
          SELECT v_order,id,1,'入院后完成并回报结果',name,item_type,unit,unit_price,billable,inventory_managed,
                 execution_role,'TEMPORARY','COMPLETED',UTC_TIMESTAMP(3)-INTERVAL 2 DAY,1
            FROM medical_item WHERE id=v_item;
        SET v_line = LAST_INSERT_ID();
        INSERT INTO execution_record(request_no,order_item_id,execution_time,quantity,status,result_note,
            executor_id,phase,unit_snapshot)
          VALUES(CONCAT('SEED-P-',LPAD(i,3,'0')),v_line,UTC_TIMESTAMP(3)-INTERVAL 2 DAY,1,'SUCCESS','项目已完成并回报',
                 v_nurse,'PERFORM',v_unit);
        SET v_execution = LAST_INSERT_ID();
        SET v_charge=CONCAT('C',SUBSTRING(REPLACE(UUID(),'-',''),1,30));
        INSERT INTO charge_detail(admission_id,execution_id,medical_item_id,department_id,charge_no,charge_type,
            unit_price_snapshot,quantity,amount,note,charged_at)
          VALUES(v_admission,v_execution,v_item,v_department,v_charge,'NORMAL',v_price,1,v_price,'项目执行计费',
                 UTC_TIMESTAMP(3)-INTERVAL 2 DAY);

        -- Order two: a long-term nursing instruction that is executed but never charged.
        INSERT INTO medical_order(order_no,admission_id,status,start_time,submitted_at,note,prescribed_by)
          VALUES(CONCAT('TMP',UUID_SHORT()),v_admission,'IN_PROGRESS',
                 UTC_TIMESTAMP(3)-INTERVAL 2 DAY,UTC_TIMESTAMP(3)-INTERVAL 2 DAY,'长期护理与饮食医嘱',v_doctor);
        SET v_order = LAST_INSERT_ID();
        UPDATE medical_order SET order_no=CONCAT(DATE_FORMAT(DATE_ADD(created_at,INTERVAL 8 HOUR),'%Y%m%d'),LPAD(v_order,8,'0'))
          WHERE id=v_order;
        INSERT INTO order_item(medical_order_id,medical_item_id,execution_quantity_per_operation,instructions,
            item_name_snapshot,item_type_snapshot,unit_snapshot,price_snapshot,billable,inventory_managed,
            execution_role,order_type,item_status,effective_start_time,executed_quantity)
          SELECT v_order,id,1,'每4小时记录一次',name,item_type,unit,unit_price,billable,inventory_managed,
                 execution_role,'LONG_TERM','IN_PROGRESS',UTC_TIMESTAMP(3)-INTERVAL 2 DAY,1
            FROM medical_item WHERE item_code='NURS004';
        SET v_line = LAST_INSERT_ID();
        INSERT INTO execution_record(request_no,order_item_id,execution_time,quantity,status,result_note,
            executor_id,phase,unit_snapshot)
          VALUES(CONCAT('SEED-N-',LPAD(i,3,'0')),v_line,UTC_TIMESTAMP(3)-INTERVAL 1 DAY,1,'SUCCESS','生命体征平稳',
                 v_nurse,'PERFORM','项');
        INSERT INTO order_item(medical_order_id,medical_item_id,execution_quantity_per_operation,instructions,
            item_name_snapshot,item_type_snapshot,unit_snapshot,price_snapshot,billable,inventory_managed,
            execution_role,order_type,item_status,effective_start_time)
          SELECT v_order,id,1,'按医嘱执行',name,item_type,unit,unit_price,billable,inventory_managed,
                 execution_role,'LONG_TERM','SUBMITTED',UTC_TIMESTAMP(3)-INTERVAL 2 DAY
            FROM medical_item WHERE item_code = ELT(1 + MOD(i - 1, 4), 'DIET001','DIET004','DIET005','NURS006');

        -- Order three (every second patient): a temporary drug, dispensed and administered once.
        -- Dispensing draws stock and charges; administration consumes dose units without a second charge.
        IF MOD(i, 2) = 0 THEN
            SELECT id, unit_price, stock_quantity INTO v_item, v_price, v_stock
              FROM medical_item WHERE item_code='DRUG003' FOR UPDATE;
            INSERT INTO medical_order(order_no,admission_id,status,start_time,submitted_at,note,prescribed_by)
              VALUES(CONCAT('TMP',UUID_SHORT()),v_admission,'COMPLETED',
                     UTC_TIMESTAMP(3)-INTERVAL 1 DAY,UTC_TIMESTAMP(3)-INTERVAL 1 DAY,'发热时临时用药',v_doctor);
            SET v_order = LAST_INSERT_ID();
            UPDATE medical_order SET order_no=CONCAT(DATE_FORMAT(DATE_ADD(created_at,INTERVAL 8 HOUR),'%Y%m%d'),LPAD(v_order,8,'0'))
              WHERE id=v_order;
            INSERT INTO order_item(medical_order_id,medical_item_id,dose_per_administration,requested_dispense_quantity,route,instructions,
                item_name_snapshot,item_type_snapshot,unit_snapshot,dose_unit,dose_units_per_stock,price_snapshot,
                billable,inventory_managed,execution_role,order_type,item_status,administration_mode,effective_start_time,
                dispensed_quantity,executed_quantity)
              SELECT v_order,mi.id,1,1,'口服','发热时一次服用',mi.name,mi.item_type,mi.unit,dp.dose_unit,
                     dp.dose_units_per_stock,mi.unit_price,mi.billable,mi.inventory_managed,'NURSE','TEMPORARY',
                     'COMPLETED','ONCE',UTC_TIMESTAMP(3)-INTERVAL 1 DAY,1,1
                FROM medical_item mi JOIN drug_profile dp ON dp.medical_item_id=mi.id WHERE mi.id=v_item;
            SET v_line = LAST_INSERT_ID();
            INSERT INTO medication_schedule(order_item_id,scheduled_at,status,completed_at)
              VALUES(v_line,UTC_TIMESTAMP(3)-INTERVAL 20 HOUR,'ADMINISTERED',UTC_TIMESTAMP(3)-INTERVAL 20 HOUR);
            SET @seed_schedule=LAST_INSERT_ID();
            -- Dispense: one box leaves the pharmacy and is charged.
            INSERT INTO execution_record(request_no,order_item_id,execution_time,quantity,status,result_note,
                executor_id,phase,unit_snapshot)
              VALUES(CONCAT('SEED-D-',LPAD(i,3,'0')),v_line,UTC_TIMESTAMP(3)-INTERVAL 1 DAY,1,'SUCCESS','已核对医嘱并发药',
                     v_nurse,'DISPENSE','盒');
            SET v_execution = LAST_INSERT_ID();
            UPDATE medical_item SET stock_quantity=stock_quantity-1,version=version+1 WHERE id=v_item;
            INSERT INTO drug_inventory_transaction(medical_item_id,execution_id,transaction_type,quantity,
                before_quantity,after_quantity,note,created_at)
              VALUES(v_item,v_execution,'DISPENSE',-1,v_stock,v_stock-1,'药房发药',UTC_TIMESTAMP(3)-INTERVAL 1 DAY);
            SET v_charge=CONCAT('C',SUBSTRING(REPLACE(UUID(),'-',''),1,30));
            INSERT INTO charge_detail(admission_id,execution_id,medical_item_id,department_id,charge_no,charge_type,
                unit_price_snapshot,quantity,amount,note,charged_at)
              VALUES(v_admission,v_execution,v_item,v_department,v_charge,'NORMAL',v_price,1,v_price,'药房发药计费',
                     UTC_TIMESTAMP(3)-INTERVAL 1 DAY);
            -- Administer: one capsule is given. No stock movement and no second charge.
            INSERT INTO execution_record(request_no,order_item_id,schedule_id,execution_time,quantity,status,result_note,
                executor_id,phase,unit_snapshot)
              VALUES(CONCAT('SEED-A-',LPAD(i,3,'0')),v_line,@seed_schedule,UTC_TIMESTAMP(3)-INTERVAL 20 HOUR,1,'SUCCESS',
                     '患者身份及用药信息核对无误',v_nurse,'ADMINISTER','粒');
        END IF;

        SET i = i + 1;
    END WHILE;
END$$
DELIMITER ;
CALL sp_seed_orders();
DROP PROCEDURE sp_seed_orders;

-- ---------------------------------------------------------------------------
-- Curated order states on the first patients: draft, awaiting dispense,
-- dispensed-awaiting-administration, independent infusion items, stopped and cancelled.
-- ---------------------------------------------------------------------------
SET @a1=(SELECT a.id FROM admission a JOIN patient p ON p.id=a.patient_id WHERE p.patient_no='P00002');
SET @a2=(SELECT a.id FROM admission a JOIN patient p ON p.id=a.patient_id WHERE p.patient_no='P00003');
SET @a3=(SELECT a.id FROM admission a JOIN patient p ON p.id=a.patient_id WHERE p.patient_no='P00004');
SET @a4=(SELECT a.id FROM admission a JOIN patient p ON p.id=a.patient_id WHERE p.patient_no='P00005');
SET @a5=(SELECT a.id FROM admission a JOIN patient p ON p.id=a.patient_id WHERE p.patient_no='P00006');
SET @doc1=(SELECT attending_doctor_id FROM admission WHERE id=@a1);
SET @doc2=(SELECT attending_doctor_id FROM admission WHERE id=@a2);
SET @doc3=(SELECT attending_doctor_id FROM admission WHERE id=@a3);
SET @doc4=(SELECT attending_doctor_id FROM admission WHERE id=@a4);
SET @doc5=(SELECT attending_doctor_id FROM admission WHERE id=@a5);
SET @dept2=(SELECT department_id FROM admission WHERE id=@a2);
SET @nurse2=(SELECT id FROM staff WHERE department_id=@dept2 AND staff_type='NURSE' AND active ORDER BY id LIMIT 1);

-- Draft: prescribed but not yet submitted, so nothing is executable or chargeable.
INSERT INTO medical_order(order_no,admission_id,status,start_time,note,prescribed_by)
VALUES(CONCAT('TMP',UUID_SHORT()),@a1,'DRAFT',UTC_TIMESTAMP(3),'待确认的肺炎基础医嘱',@doc1);
SET @o1=LAST_INSERT_ID();
UPDATE medical_order SET order_no=CONCAT(DATE_FORMAT(DATE_ADD(created_at,INTERVAL 8 HOUR),'%Y%m%d'),LPAD(@o1,8,'0')) WHERE id=@o1;
INSERT INTO order_item(medical_order_id,medical_item_id,dose_per_administration,requested_dispense_quantity,route,instructions,
    item_name_snapshot,item_type_snapshot,unit_snapshot,dose_unit,dose_units_per_stock,price_snapshot,
    billable,inventory_managed,execution_role,order_type,item_status,administration_mode,administration_interval_minutes,effective_start_time,effective_end_time)
SELECT @o1,mi.id,1,1,'口服','每12小时一次，共14次',mi.name,mi.item_type,mi.unit,dp.dose_unit,dp.dose_units_per_stock,
       mi.unit_price,mi.billable,mi.inventory_managed,'NURSE','LONG_TERM','DRAFT','SCHEDULED',720,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)+INTERVAL 7 DAY
FROM medical_item mi JOIN drug_profile dp ON dp.medical_item_id=mi.id WHERE mi.item_code='DRUG006';
INSERT INTO order_item(medical_order_id,medical_item_id,execution_quantity_per_operation,instructions,
    item_name_snapshot,item_type_snapshot,unit_snapshot,price_snapshot,billable,inventory_managed,
    execution_role,order_type,item_status,effective_start_time)
SELECT @o1,id,1,'与用药同步复查',name,item_type,unit,unit_price,billable,inventory_managed,
       execution_role,'TEMPORARY','DRAFT',UTC_TIMESTAMP(3) FROM medical_item WHERE item_code='LAB001';

-- Submitted: waiting for the pharmacy to dispense.
INSERT INTO medical_order(order_no,admission_id,status,start_time,submitted_at,note,prescribed_by)
VALUES(CONCAT('TMP',UUID_SHORT()),@a2,'SUBMITTED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'等待药房发药',@doc2);
SET @o2=LAST_INSERT_ID();
UPDATE medical_order SET order_no=CONCAT(DATE_FORMAT(DATE_ADD(created_at,INTERVAL 8 HOUR),'%Y%m%d'),LPAD(@o2,8,'0')) WHERE id=@o2;
INSERT INTO order_item(medical_order_id,medical_item_id,dose_per_administration,requested_dispense_quantity,route,instructions,
    item_name_snapshot,item_type_snapshot,unit_snapshot,dose_unit,dose_units_per_stock,price_snapshot,
    billable,inventory_managed,execution_role,order_type,item_status,administration_mode,administration_interval_minutes,effective_start_time,effective_end_time)
SELECT @o2,mi.id,1,1,'口服','每8小时一次，共6次',mi.name,mi.item_type,mi.unit,dp.dose_unit,dp.dose_units_per_stock,
       mi.unit_price,mi.billable,mi.inventory_managed,'NURSE','LONG_TERM','SUBMITTED','SCHEDULED',480,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)+INTERVAL 2 DAY
FROM medical_item mi JOIN drug_profile dp ON dp.medical_item_id=mi.id WHERE mi.item_code='DRUG001';
SET @line_o2=LAST_INSERT_ID();
INSERT INTO medication_schedule(order_item_id,scheduled_at) VALUES(@line_o2,UTC_TIMESTAMP(3));

-- Independent infusion items: each drug is dispensed and charged separately.
INSERT INTO medical_order(order_no,admission_id,status,start_time,submitted_at,note,prescribed_by)
VALUES(CONCAT('TMP',UUID_SHORT()),@a3,'IN_PROGRESS',UTC_TIMESTAMP(3)-INTERVAL 6 HOUR,UTC_TIMESTAMP(3)-INTERVAL 6 HOUR,'抗感染静滴医嘱，已发药待给药',@doc3);
SET @o3=LAST_INSERT_ID();
UPDATE medical_order SET order_no=CONCAT(DATE_FORMAT(DATE_ADD(created_at,INTERVAL 8 HOUR),'%Y%m%d'),LPAD(@o3,8,'0')) WHERE id=@o3;
SET @dept3=(SELECT department_id FROM admission WHERE id=@a3);
SET @nurse3=(SELECT id FROM staff WHERE department_id=@dept3 AND staff_type='NURSE' AND active ORDER BY id LIMIT 1);

-- Sodium chloride: one 500 mL bag dispensed, 250 mL planned per administration.
SET @sodium=(SELECT id FROM medical_item WHERE item_code='DRUG002');
SET @sodium_stock=(SELECT stock_quantity FROM medical_item WHERE id=@sodium);
SET @sodium_price=(SELECT unit_price FROM medical_item WHERE id=@sodium);
INSERT INTO order_item(medical_order_id,medical_item_id,dose_per_administration,requested_dispense_quantity,route,instructions,
    item_name_snapshot,item_type_snapshot,unit_snapshot,dose_unit,dose_units_per_stock,price_snapshot,
    billable,inventory_managed,execution_role,order_type,item_status,administration_mode,effective_start_time,dispensed_quantity)
SELECT @o3,mi.id,250,1,'静脉滴注','溶媒，单次250mL',mi.name,mi.item_type,mi.unit,dp.dose_unit,dp.dose_units_per_stock,
       mi.unit_price,mi.billable,mi.inventory_managed,'NURSE','TEMPORARY','IN_PROGRESS','ONCE',UTC_TIMESTAMP(3)-INTERVAL 6 HOUR,1
FROM medical_item mi JOIN drug_profile dp ON dp.medical_item_id=mi.id WHERE mi.id=@sodium;
SET @line_sodium=LAST_INSERT_ID();
INSERT INTO medication_schedule(order_item_id,scheduled_at) VALUES(@line_sodium,UTC_TIMESTAMP(3)-INTERVAL 4 HOUR);
INSERT INTO execution_record(request_no,order_item_id,execution_time,quantity,status,result_note,executor_id,phase,unit_snapshot)
VALUES('SEED-INFUSION-DISPENSE-1',@line_sodium,UTC_TIMESTAMP(3)-INTERVAL 5 HOUR,1,'SUCCESS','药房发药',@nurse3,'DISPENSE','袋');
SET @exec_sodium=LAST_INSERT_ID();
UPDATE medical_item SET stock_quantity=stock_quantity-1,version=version+1 WHERE id=@sodium;
INSERT INTO drug_inventory_transaction(medical_item_id,execution_id,transaction_type,quantity,before_quantity,after_quantity,note,created_at)
VALUES(@sodium,@exec_sodium,'DISPENSE',-1,@sodium_stock,@sodium_stock-1,'药房发药',UTC_TIMESTAMP(3)-INTERVAL 5 HOUR);
SET @charge_sodium=CONCAT('C',SUBSTRING(REPLACE(UUID(),'-',''),1,30));
INSERT INTO charge_detail(admission_id,execution_id,medical_item_id,department_id,charge_no,charge_type,
    unit_price_snapshot,quantity,amount,note,charged_at)
VALUES(@a3,@exec_sodium,@sodium,@dept3,@charge_sodium,'NORMAL',@sodium_price,1,@sodium_price,'药房发药计费',UTC_TIMESTAMP(3)-INTERVAL 5 HOUR);

-- Ceftriaxone: one 2 g vial dispensed, 2 g planned per administration.
SET @ceftri=(SELECT id FROM medical_item WHERE item_code='DRUG004');
SET @ceftri_stock=(SELECT stock_quantity FROM medical_item WHERE id=@ceftri);
SET @ceftri_price=(SELECT unit_price FROM medical_item WHERE id=@ceftri);
INSERT INTO order_item(medical_order_id,medical_item_id,dose_per_administration,requested_dispense_quantity,route,instructions,
    item_name_snapshot,item_type_snapshot,unit_snapshot,dose_unit,dose_units_per_stock,price_snapshot,
    billable,inventory_managed,execution_role,order_type,item_status,administration_mode,effective_start_time,dispensed_quantity)
SELECT @o3,mi.id,2,1,'静脉滴注','主药，单次2g',mi.name,mi.item_type,mi.unit,dp.dose_unit,dp.dose_units_per_stock,
       mi.unit_price,mi.billable,mi.inventory_managed,'NURSE','TEMPORARY','IN_PROGRESS','ONCE',UTC_TIMESTAMP(3)-INTERVAL 6 HOUR,1
FROM medical_item mi JOIN drug_profile dp ON dp.medical_item_id=mi.id WHERE mi.id=@ceftri;
SET @line_ceftri=LAST_INSERT_ID();
INSERT INTO medication_schedule(order_item_id,scheduled_at) VALUES(@line_ceftri,UTC_TIMESTAMP(3)-INTERVAL 4 HOUR);
INSERT INTO execution_record(request_no,order_item_id,execution_time,quantity,status,result_note,executor_id,phase,unit_snapshot)
VALUES('SEED-INFUSION-DISPENSE-2',@line_ceftri,UTC_TIMESTAMP(3)-INTERVAL 5 HOUR,1,'SUCCESS','药房发药',@nurse3,'DISPENSE','瓶');
SET @exec_ceftri=LAST_INSERT_ID();
UPDATE medical_item SET stock_quantity=stock_quantity-1,version=version+1 WHERE id=@ceftri;
INSERT INTO drug_inventory_transaction(medical_item_id,execution_id,transaction_type,quantity,before_quantity,after_quantity,note,created_at)
VALUES(@ceftri,@exec_ceftri,'DISPENSE',-1,@ceftri_stock,@ceftri_stock-1,'药房发药',UTC_TIMESTAMP(3)-INTERVAL 5 HOUR);
SET @charge_ceftri=CONCAT('C',SUBSTRING(REPLACE(UUID(),'-',''),1,30));
INSERT INTO charge_detail(admission_id,execution_id,medical_item_id,department_id,charge_no,charge_type,
    unit_price_snapshot,quantity,amount,note,charged_at)
VALUES(@a3,@exec_ceftri,@ceftri,@dept3,@charge_ceftri,'NORMAL',@ceftri_price,1,@ceftri_price,'药房发药计费',UTC_TIMESTAMP(3)-INTERVAL 5 HOUR);

-- Stopped: a long-term instruction discontinued after two executions.
INSERT INTO medical_order(order_no,admission_id,status,start_time,submitted_at,stopped_at,note,prescribed_by)
VALUES(CONCAT('TMP',UUID_SHORT()),@a4,'STOPPED',UTC_TIMESTAMP(3)-INTERVAL 2 DAY,UTC_TIMESTAMP(3)-INTERVAL 2 DAY,UTC_TIMESTAMP(3),'血氧改善后停止吸氧',@doc4);
SET @o4=LAST_INSERT_ID();
UPDATE medical_order SET order_no=CONCAT(DATE_FORMAT(DATE_ADD(created_at,INTERVAL 8 HOUR),'%Y%m%d'),LPAD(@o4,8,'0')) WHERE id=@o4;
SET @dept4=(SELECT department_id FROM admission WHERE id=@a4);
SET @nurse4=(SELECT id FROM staff WHERE department_id=@dept4 AND staff_type='NURSE' AND active ORDER BY id LIMIT 1);
SET @oxygen=(SELECT id FROM medical_item WHERE item_code='TREAT003');
SET @oxygen_price=(SELECT unit_price FROM medical_item WHERE id=@oxygen);
INSERT INTO order_item(medical_order_id,medical_item_id,execution_quantity_per_operation,instructions,
    item_name_snapshot,item_type_snapshot,unit_snapshot,price_snapshot,billable,inventory_managed,
    execution_role,order_type,item_status,effective_start_time,effective_end_time,executed_quantity)
SELECT @o4,id,2,'鼻导管低流量吸氧，每次2小时',name,item_type,unit,unit_price,billable,inventory_managed,
       execution_role,'LONG_TERM','STOPPED',UTC_TIMESTAMP(3)-INTERVAL 2 DAY,UTC_TIMESTAMP(3),2
FROM medical_item WHERE id=@oxygen;
SET @line_oxygen=LAST_INSERT_ID();
INSERT INTO execution_record(request_no,order_item_id,execution_time,quantity,status,result_note,executor_id,phase,unit_snapshot)
VALUES('SEED-OXY-1',@line_oxygen,UTC_TIMESTAMP(3)-INTERVAL 2 DAY,2,'SUCCESS','吸氧2小时，血氧回升',@nurse4,'PERFORM','小时');
SET @exec_oxygen=LAST_INSERT_ID();
SET @charge_oxygen=CONCAT('C',SUBSTRING(REPLACE(UUID(),'-',''),1,30));
INSERT INTO charge_detail(admission_id,execution_id,medical_item_id,department_id,charge_no,charge_type,
    unit_price_snapshot,quantity,amount,note,charged_at)
VALUES(@a4,@exec_oxygen,@oxygen,@dept4,@charge_oxygen,'NORMAL',@oxygen_price,2,@oxygen_price*2,'项目执行计费',UTC_TIMESTAMP(3)-INTERVAL 2 DAY);

-- Cancelled before execution: no stock, no charge, no execution record.
INSERT INTO medical_order(order_no,admission_id,status,start_time,submitted_at,note,prescribed_by)
VALUES(CONCAT('TMP',UUID_SHORT()),@a5,'CANCELLED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'开立后因病情变化撤销',@doc5);
SET @o5=LAST_INSERT_ID();
UPDATE medical_order SET order_no=CONCAT(DATE_FORMAT(DATE_ADD(created_at,INTERVAL 8 HOUR),'%Y%m%d'),LPAD(@o5,8,'0')) WHERE id=@o5;
INSERT INTO order_item(medical_order_id,medical_item_id,execution_quantity_per_operation,instructions,
    item_name_snapshot,item_type_snapshot,unit_snapshot,price_snapshot,billable,inventory_managed,
    execution_role,order_type,item_status,effective_start_time)
SELECT @o5,id,1,'原计划检查，后撤销',name,item_type,unit,unit_price,billable,inventory_managed,
       execution_role,'TEMPORARY','CANCELLED',UTC_TIMESTAMP(3) FROM medical_item WHERE item_code='LAB004';

-- ---------------------------------------------------------------------------
-- Deposits: supplements, one fully reversed supplement and the resulting balances.
-- A reversal must mirror its original row exactly or the guard rejects it.
-- The account guard locks `admission`, so the rows are inserted one at a time:
-- an INSERT ... SELECT reading `admission` would collide with that same trigger.
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_seed_deposits;
DELIMITER $$
CREATE PROCEDURE sp_seed_deposits()
BEGIN
    DECLARE v_done INT DEFAULT 0;
    DECLARE v_admission BIGINT;
    DECLARE cur CURSOR FOR
        SELECT a.id FROM admission a JOIN patient p ON p.id=a.patient_id
         WHERE a.status='IN_HOSPITAL' AND MOD(p.id,3)=0;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done=1;
    OPEN cur;
    read_loop: LOOP
        FETCH cur INTO v_admission;
        IF v_done=1 THEN LEAVE read_loop; END IF;
        INSERT INTO deposit_transaction(admission_id,transaction_no,transaction_type,amount,payment_method,note)
          VALUES(v_admission,CONCAT('D2026082900',LPAD(v_admission,6,'0')),'SUPPLEMENT',500.00,'CARD','住院期间补交押金');
    END LOOP;
    CLOSE cur;
END$$
DELIMITER ;
CALL sp_seed_deposits();
DROP PROCEDURE sp_seed_deposits;

-- One supplement is registered twice by mistake and then reversed in full.
SET @dup_admission=(SELECT a.id FROM admission a JOIN patient p ON p.id=a.patient_id WHERE p.patient_no='P00007');
INSERT INTO deposit_transaction(admission_id,transaction_no,transaction_type,amount,payment_method,note)
VALUES(@dup_admission,'D20260829DUP0001','SUPPLEMENT',800.00,'CASH','重复登记的补交押金');
SET @dup_deposit=LAST_INSERT_ID();
INSERT INTO deposit_transaction(admission_id,transaction_no,transaction_type,amount,original_transaction_id,payment_method,note)
VALUES(@dup_admission,'D20260829REV0001','REVERSAL',-800.00,@dup_deposit,'CASH','重复登记押金，全额冲正');

-- ---------------------------------------------------------------------------
-- Bed fees: system-generated charges with no source order, one night per stay.
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_seed_bed_fees;
DELIMITER $$
CREATE PROCEDURE sp_seed_bed_fees()
BEGIN
    DECLARE v_done INT DEFAULT 0;
    DECLARE v_admission BIGINT;
    DECLARE v_department BIGINT;
    DECLARE v_charge VARCHAR(32);
    DECLARE v_bed_item BIGINT;
    DECLARE v_bed_price DECIMAL(12,2);
    DECLARE cur CURSOR FOR SELECT id,department_id FROM admission WHERE status IN ('IN_HOSPITAL','PENDING_DISCHARGE');
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done=1;
    SELECT id,unit_price INTO v_bed_item,v_bed_price FROM medical_item WHERE item_code='BED001';
    OPEN cur;
    read_loop: LOOP
        FETCH cur INTO v_admission,v_department;
        IF v_done=1 THEN LEAVE read_loop; END IF;
        SET v_charge=CONCAT('C',SUBSTRING(REPLACE(UUID(),'-',''),1,30));
        INSERT INTO charge_detail(admission_id,execution_id,medical_item_id,department_id,charge_no,charge_type,
            unit_price_snapshot,quantity,amount,note,charged_at)
          VALUES(v_admission,NULL,v_bed_item,v_department,v_charge,'NORMAL',v_bed_price,1,v_bed_price,
                 '系统自动床位费',UTC_TIMESTAMP(3)-INTERVAL 1 DAY);
    END LOOP;
    CLOSE cur;
END$$
DELIMITER ;
CALL sp_seed_bed_fees();
DROP PROCEDURE sp_seed_bed_fees;

-- ---------------------------------------------------------------------------
-- Discharge flow. Patients 25-26 await discharge; 27-28 are settled in full.
-- Settlement must run last: it closes the account against further changes.
-- ---------------------------------------------------------------------------
UPDATE admission a JOIN patient p ON p.id=a.patient_id
SET a.status='PENDING_DISCHARGE',a.version=a.version+1
WHERE p.patient_no IN ('P00025','P00026');

UPDATE admission a JOIN patient p ON p.id=a.patient_id
SET a.status='PENDING_DISCHARGE',a.version=a.version+1
WHERE p.patient_no IN ('P00027','P00028');

SET @settle1=(SELECT a.id FROM admission a JOIN patient p ON p.id=a.patient_id WHERE p.patient_no='P00027');
SET @settle2=(SELECT a.id FROM admission a JOIN patient p ON p.id=a.patient_id WHERE p.patient_no='P00028');

-- Settlement refuses to run while any order is unfinished, so the doctor closes the
-- long-term instructions first. Executed items complete; untouched ones are cancelled.
UPDATE order_item oi JOIN medical_order mo ON mo.id=oi.medical_order_id
SET oi.item_status=IF(oi.executed_quantity>0,'COMPLETED','CANCELLED')
WHERE mo.admission_id IN (@settle1,@settle2) AND oi.item_status IN ('DRAFT','SUBMITTED','IN_PROGRESS');
UPDATE medical_order mo
SET mo.status=IF(EXISTS(SELECT 1 FROM order_item oi WHERE oi.medical_order_id=mo.id AND oi.item_status='COMPLETED'),
                 'COMPLETED','CANCELLED')
WHERE mo.admission_id IN (@settle1,@settle2) AND mo.status IN ('DRAFT','SUBMITTED','IN_PROGRESS');

CALL sp_discharge_settlement(@settle1, 'CASH', NULL, @settlement1);
CALL sp_discharge_settlement(@settle2, 'CASH', NULL, @settlement2);

-- ---------------------------------------------------------------------------
-- Overdue alerts. The only rule is a negative available balance on an open stay.
-- Patients 9 and 18 were admitted with a deposit smaller than their charges.
-- ---------------------------------------------------------------------------
INSERT INTO account_alert(admission_id,alert_type,level,balance_snapshot,threshold_snapshot,status,created_at)
SELECT v.admission_id,'LOW_BALANCE',IF(v.available_balance < -1000,'CRITICAL','WARNING'),
       v.available_balance,0,'OPEN',UTC_TIMESTAMP(3)
FROM v_admission_deposit_balance v JOIN admission a ON a.id=v.admission_id
WHERE a.status IN ('IN_HOSPITAL','PENDING_DISCHARGE') AND v.available_balance < 0;

-- A previously handled alert, kept so the resolved view has content.
INSERT INTO account_alert(admission_id,alert_type,level,balance_snapshot,threshold_snapshot,status,created_at,resolved_at)
SELECT a.id,'LOW_BALANCE','WARNING',-120.00,0,'RESOLVED',UTC_TIMESTAMP(3)-INTERVAL 2 DAY,UTC_TIMESTAMP(3)-INTERVAL 1 DAY
FROM admission a JOIN patient p ON p.id=a.patient_id WHERE p.patient_no='P00012';
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
