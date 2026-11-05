SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- V9 models inpatient diagnoses and signed clinical notes. The catalog is
-- reference data; the two inpatient tables carry the actual clinical facts.
INSERT INTO permission(code, name) VALUES
('DIAGNOSIS_RECORD', '住院诊断记录'),
('CLINICAL_RECORD', '住院临床记录');

INSERT INTO role_permission(role_id, permission_id)
SELECT r.id, p.id
FROM role r CROSS JOIN permission p
WHERE r.code IN ('ADMIN', 'DOCTOR')
  AND p.code IN ('DIAGNOSIS_RECORD', 'CLINICAL_RECORD');

CREATE TABLE diagnosis_catalog (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    coding_system VARCHAR(32) NOT NULL DEFAULT 'ICD10',
    code VARCHAR(32) NOT NULL,
    name VARCHAR(200) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_diagnosis_catalog_code UNIQUE (coding_system, code),
    CONSTRAINT uk_diagnosis_catalog_reference UNIQUE (id, coding_system, code),
    CONSTRAINT ck_diagnosis_catalog_system_nonblank CHECK (CHAR_LENGTH(TRIM(coding_system)) > 0),
    CONSTRAINT ck_diagnosis_catalog_code_nonblank CHECK (CHAR_LENGTH(TRIM(code)) > 0),
    CONSTRAINT ck_diagnosis_catalog_name_nonblank CHECK (CHAR_LENGTH(TRIM(name)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inpatient_diagnosis (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    admission_id BIGINT NOT NULL,
    diagnosis_catalog_id BIGINT NOT NULL,
    diagnosis_no VARCHAR(64) NOT NULL,
    diagnosis_type VARCHAR(20) NOT NULL,
    clinical_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    present_on_admission BOOLEAN NOT NULL DEFAULT FALSE,
    diagnosed_at TIMESTAMP(3) NOT NULL,
    diagnosis_note VARCHAR(500),
    resolved_at TIMESTAMP(3),
    resolution_note VARCHAR(500),
    diagnosis_code_snapshot VARCHAR(32) NOT NULL,
    diagnosis_name_snapshot VARCHAR(200) NOT NULL,
    coding_system_snapshot VARCHAR(32) NOT NULL,
    diagnosed_by BIGINT NOT NULL,
    recorded_by BIGINT NOT NULL,
    resolved_by BIGINT,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    active_marker TINYINT GENERATED ALWAYS AS
        (CASE WHEN clinical_status = 'ACTIVE' THEN 1 ELSE NULL END) STORED,
    active_primary_key BIGINT GENERATED ALWAYS AS
        (CASE WHEN diagnosis_type = 'PRIMARY' AND clinical_status = 'ACTIVE' THEN admission_id ELSE NULL END) STORED,
    CONSTRAINT uk_inpatient_diagnosis_no UNIQUE (diagnosis_no),
    CONSTRAINT uk_inpatient_diagnosis_active UNIQUE
        (admission_id, diagnosis_catalog_id, diagnosis_type, active_marker),
    CONSTRAINT uk_inpatient_diagnosis_primary UNIQUE (active_primary_key),
    CONSTRAINT ck_inpatient_diagnosis_no_nonblank CHECK (CHAR_LENGTH(TRIM(diagnosis_no)) > 0),
    CONSTRAINT ck_inpatient_diagnosis_type CHECK (
        diagnosis_type IN ('ADMISSION', 'PRIMARY', 'SECONDARY', 'COMPLICATION', 'DISCHARGE')
    ),
    CONSTRAINT ck_inpatient_diagnosis_status CHECK (
        clinical_status IN ('ACTIVE', 'RESOLVED', 'RULED_OUT')
    ),
    CONSTRAINT ck_inpatient_diagnosis_time CHECK (
        resolved_at IS NULL OR resolved_at >= diagnosed_at
    ),
    CONSTRAINT ck_inpatient_diagnosis_status_shape CHECK (
        (clinical_status = 'ACTIVE' AND resolved_at IS NULL AND resolved_by IS NULL)
        OR (clinical_status IN ('RESOLVED', 'RULED_OUT') AND resolved_at IS NOT NULL AND resolved_by IS NOT NULL)
    ),
    CONSTRAINT ck_inpatient_diagnosis_snapshot_nonblank CHECK (
        CHAR_LENGTH(TRIM(coding_system_snapshot)) > 0
        AND CHAR_LENGTH(TRIM(diagnosis_code_snapshot)) > 0
        AND CHAR_LENGTH(TRIM(diagnosis_name_snapshot)) > 0
    ),
    CONSTRAINT fk_inpatient_diagnosis_admission FOREIGN KEY (admission_id) REFERENCES admission(id),
    CONSTRAINT fk_inpatient_diagnosis_catalog_snapshot
        FOREIGN KEY (diagnosis_catalog_id, coding_system_snapshot, diagnosis_code_snapshot)
        REFERENCES diagnosis_catalog(id, coding_system, code),
    CONSTRAINT fk_inpatient_diagnosis_diagnosed_by FOREIGN KEY (diagnosed_by) REFERENCES staff(id),
    CONSTRAINT fk_inpatient_diagnosis_recorded_by FOREIGN KEY (recorded_by) REFERENCES user_account(id),
    CONSTRAINT fk_inpatient_diagnosis_resolved_by FOREIGN KEY (resolved_by) REFERENCES user_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_inpatient_diagnosis_admission_time
    ON inpatient_diagnosis (admission_id, diagnosed_at, id);

CREATE TABLE inpatient_clinical_note (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    admission_id BIGINT NOT NULL,
    note_no VARCHAR(64) NOT NULL,
    note_type VARCHAR(24) NOT NULL,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    note_status VARCHAR(16) NOT NULL DEFAULT 'FINAL',
    amends_note_id BIGINT,
    amends_note_signed_at TIMESTAMP(3),
    amendment_reason VARCHAR(500),
    authored_by BIGINT NOT NULL,
    recorded_by BIGINT NOT NULL,
    signed_by BIGINT NOT NULL,
    authored_at TIMESTAMP(3) NOT NULL,
    signed_at TIMESTAMP(3) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    base_note_key BIGINT GENERATED ALWAYS AS
        (CASE WHEN amends_note_id IS NULL
                   AND note_type IN ('ADMISSION_ASSESSMENT', 'DISCHARGE_SUMMARY')
              THEN admission_id ELSE NULL END) STORED,
    CONSTRAINT uk_inpatient_clinical_note_no UNIQUE (note_no),
    CONSTRAINT uk_inpatient_clinical_note_parent UNIQUE (amends_note_id),
    CONSTRAINT uk_inpatient_clinical_note_single_summary UNIQUE
        (admission_id, note_type, base_note_key),
    CONSTRAINT uk_inpatient_clinical_note_parent_match UNIQUE (id, admission_id, note_type, signed_at),
    CONSTRAINT ck_inpatient_clinical_note_no_nonblank CHECK (CHAR_LENGTH(TRIM(note_no)) > 0),
    CONSTRAINT ck_inpatient_clinical_note_type CHECK (
        note_type IN ('ADMISSION_ASSESSMENT', 'PROGRESS', 'CRITICAL_EVENT', 'DISCHARGE_SUMMARY', 'OTHER')
    ),
    CONSTRAINT ck_inpatient_clinical_note_title_nonblank CHECK (CHAR_LENGTH(TRIM(title)) > 0),
    CONSTRAINT ck_inpatient_clinical_note_content_nonblank CHECK (CHAR_LENGTH(TRIM(content)) > 0),
    CONSTRAINT ck_inpatient_clinical_note_status_shape CHECK (
        (amends_note_id IS NULL AND amends_note_signed_at IS NULL
            AND note_status = 'FINAL' AND amendment_reason IS NULL)
        OR (amends_note_id IS NOT NULL AND note_status = 'AMENDED'
            AND amends_note_signed_at IS NOT NULL
            AND amendment_reason IS NOT NULL AND CHAR_LENGTH(TRIM(amendment_reason)) > 0)
    ),
    CONSTRAINT ck_inpatient_clinical_note_time CHECK (
        authored_at <= signed_at
    ),
    CONSTRAINT fk_inpatient_clinical_note_admission FOREIGN KEY (admission_id) REFERENCES admission(id),
    CONSTRAINT fk_inpatient_clinical_note_amends_match
        FOREIGN KEY (amends_note_id, admission_id, note_type, amends_note_signed_at)
        REFERENCES inpatient_clinical_note(id, admission_id, note_type, signed_at),
    CONSTRAINT fk_inpatient_clinical_note_author FOREIGN KEY (authored_by) REFERENCES staff(id),
    CONSTRAINT fk_inpatient_clinical_note_recorder FOREIGN KEY (recorded_by) REFERENCES user_account(id),
    CONSTRAINT fk_inpatient_clinical_note_signer FOREIGN KEY (signed_by) REFERENCES user_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_inpatient_clinical_note_admission_time
    ON inpatient_clinical_note (admission_id, signed_at, id);

-- A small inpatient-only catalog makes the relationship demonstrable without
-- allowing arbitrary, untraceable diagnosis text in the transaction table.
INSERT INTO diagnosis_catalog(coding_system, code, name) VALUES
('ICD10', 'J18.9', '肺炎，未特指'),
('ICD10', 'I10', '原发性高血压'),
('ICD10', 'E11.9', '2型糖尿病，不伴有并发症'),
('ICD10', 'K35.8', '其他和未特指的急性阑尾炎'),
('ICD10', 'S72.0', '股骨颈骨折'),
('ICD10', 'J44.1', '慢性阻塞性肺疾病伴急性加重'),
('ICD10', 'N20.0', '肾结石');

DELIMITER $$

-- Diagnosis insertion binds the snapshot to the catalog, the doctor to the
-- admission department, and the recorders to the dedicated permission.
DROP TRIGGER IF EXISTS trg_inpatient_diagnosis_guard_insert$$
CREATE TRIGGER trg_inpatient_diagnosis_guard_insert BEFORE INSERT ON inpatient_diagnosis FOR EACH ROW
BEGIN
    DECLARE v_admission_time TIMESTAMP(3) DEFAULT NULL;
    DECLARE v_status VARCHAR(24) DEFAULT NULL;
    DECLARE v_department BIGINT DEFAULT NULL;
    SELECT admission_time, status, department_id
      INTO v_admission_time, v_status, v_department
      FROM admission WHERE id = NEW.admission_id;
    IF v_status IS NULL OR v_status IN ('PENDING', 'CANCELLED') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '无效住院记录不能登记诊断';
    END IF;
    IF NEW.diagnosed_at < v_admission_time OR NEW.diagnosed_at > CURRENT_TIMESTAMP(3) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '诊断时间必须位于住院开始与当前时间之间';
    END IF;
    IF NEW.diagnosis_type = 'DISCHARGE' THEN
        IF v_status NOT IN ('PENDING_DISCHARGE', 'DISCHARGED') THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '出院诊断只能在待出院或已出院记录中登记';
        END IF;
    ELSEIF v_status NOT IN ('IN_HOSPITAL', 'PENDING_DISCHARGE') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '住院诊断只能在住院中或待出院记录中登记';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM staff s
        WHERE s.id = NEW.diagnosed_by AND s.active = TRUE
          AND s.staff_type = 'DOCTOR' AND s.department_id = v_department
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '诊断人必须是在职且属于本住院科室的医生';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM user_account ua JOIN staff s ON s.id = ua.staff_id
        JOIN user_role ur ON ur.user_id = ua.id
        JOIN role r ON r.id = ur.role_id AND r.active = TRUE
        JOIN role_permission rp ON rp.role_id = r.id
        JOIN permission p ON p.id = rp.permission_id AND p.code = 'DIAGNOSIS_RECORD'
        WHERE ua.id = NEW.recorded_by AND ua.enabled = TRUE AND s.active = TRUE
          AND (s.staff_type = 'ADMIN' OR s.department_id = v_department)
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '诊断记录人无效或无住院诊断权限';
    END IF;
END$$

-- A diagnosis is a current clinical assertion: only its lifecycle may move
-- from ACTIVE to RESOLVED/RULED_OUT. Identity and original evidence stay fixed.
DROP TRIGGER IF EXISTS trg_inpatient_diagnosis_guard_update$$
CREATE TRIGGER trg_inpatient_diagnosis_guard_update BEFORE UPDATE ON inpatient_diagnosis FOR EACH ROW
BEGIN
    IF OLD.clinical_status <> 'ACTIVE'
       OR NEW.clinical_status NOT IN ('RESOLVED', 'RULED_OUT')
       OR NEW.admission_id <> OLD.admission_id
       OR NEW.diagnosis_catalog_id <> OLD.diagnosis_catalog_id
       OR NEW.diagnosis_no <> OLD.diagnosis_no
       OR NEW.diagnosis_type <> OLD.diagnosis_type
       OR NEW.present_on_admission <> OLD.present_on_admission
       OR NEW.diagnosed_at <> OLD.diagnosed_at
       OR NOT (NEW.diagnosis_note <=> OLD.diagnosis_note)
       OR NEW.diagnosis_code_snapshot <> OLD.diagnosis_code_snapshot
       OR NEW.diagnosis_name_snapshot <> OLD.diagnosis_name_snapshot
       OR NEW.coding_system_snapshot <> OLD.coding_system_snapshot
       OR NEW.diagnosed_by <> OLD.diagnosed_by
       OR NEW.recorded_by <> OLD.recorded_by
       OR NEW.resolved_at IS NULL
       OR NEW.resolved_at < OLD.diagnosed_at
       OR NEW.resolved_at > CURRENT_TIMESTAMP(3)
       OR NEW.resolved_by IS NULL
    THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '诊断仅允许登记一次解决或排除状态变更';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM user_account ua JOIN staff s ON s.id = ua.staff_id
        JOIN user_role ur ON ur.user_id = ua.id
        JOIN role r ON r.id = ur.role_id AND r.active = TRUE
        JOIN role_permission rp ON rp.role_id = r.id
        JOIN permission p ON p.id = rp.permission_id AND p.code = 'DIAGNOSIS_RECORD'
        JOIN admission a ON a.id = OLD.admission_id
        WHERE ua.id = NEW.resolved_by AND ua.enabled = TRUE AND s.active = TRUE
          AND (s.staff_type = 'ADMIN' OR s.department_id = a.department_id)
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '诊断解决记录人无效或无住院诊断权限';
    END IF;
END$$

DROP TRIGGER IF EXISTS trg_inpatient_diagnosis_no_delete$$
CREATE TRIGGER trg_inpatient_diagnosis_no_delete BEFORE DELETE ON inpatient_diagnosis FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '住院诊断不可删除';
END$$

-- Signed notes are immutable. A correction is another signed note linked to
-- the same admission and note type through the composite self-reference.
DROP TRIGGER IF EXISTS trg_inpatient_clinical_note_guard_insert$$
CREATE TRIGGER trg_inpatient_clinical_note_guard_insert BEFORE INSERT ON inpatient_clinical_note FOR EACH ROW
BEGIN
    DECLARE v_admission_time TIMESTAMP(3) DEFAULT NULL;
    DECLARE v_status VARCHAR(24) DEFAULT NULL;
    DECLARE v_department BIGINT DEFAULT NULL;
    SELECT admission_time, status, department_id
      INTO v_admission_time, v_status, v_department
      FROM admission WHERE id = NEW.admission_id;
    IF v_status IS NULL OR v_status IN ('PENDING', 'CANCELLED') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '无效住院记录不能登记临床记录';
    END IF;
    IF NEW.authored_at < v_admission_time
       OR NEW.authored_at > NEW.signed_at
       OR NEW.signed_at > CURRENT_TIMESTAMP(3)
       OR (NEW.amends_note_signed_at IS NOT NULL AND NEW.signed_at < NEW.amends_note_signed_at) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '临床记录时间顺序或住院时间范围无效';
    END IF;
    IF NEW.note_type = 'DISCHARGE_SUMMARY' THEN
        IF v_status NOT IN ('PENDING_DISCHARGE', 'DISCHARGED') THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '出院小结只能在待出院或已出院记录中签署';
        END IF;
    ELSEIF v_status NOT IN ('IN_HOSPITAL', 'PENDING_DISCHARGE') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '住院临床记录只能在住院中或待出院记录中签署';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM staff s
        WHERE s.id = NEW.authored_by AND s.active = TRUE
          AND s.staff_type = 'DOCTOR' AND s.department_id = v_department
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '临床记录作者必须是在职且属于本住院科室的医生';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM user_account ua JOIN staff s ON s.id = ua.staff_id
        JOIN user_role ur ON ur.user_id = ua.id
        JOIN role r ON r.id = ur.role_id AND r.active = TRUE
        JOIN role_permission rp ON rp.role_id = r.id
        JOIN permission p ON p.id = rp.permission_id AND p.code = 'CLINICAL_RECORD'
        WHERE ua.id = NEW.recorded_by AND ua.enabled = TRUE AND s.active = TRUE
          AND (s.staff_type = 'ADMIN' OR s.department_id = v_department)
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '临床记录登记人无效或无住院临床记录权限';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM user_account ua JOIN staff s ON s.id = ua.staff_id
        JOIN user_role ur ON ur.user_id = ua.id
        JOIN role r ON r.id = ur.role_id AND r.active = TRUE
        JOIN role_permission rp ON rp.role_id = r.id
        JOIN permission p ON p.id = rp.permission_id AND p.code = 'CLINICAL_RECORD'
        WHERE ua.id = NEW.signed_by AND ua.enabled = TRUE AND s.active = TRUE
          AND (s.staff_type = 'ADMIN' OR s.department_id = v_department)
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '临床记录签署人无效或无住院临床记录权限';
    END IF;
END$$

DROP TRIGGER IF EXISTS trg_inpatient_clinical_note_no_update$$
CREATE TRIGGER trg_inpatient_clinical_note_no_update BEFORE UPDATE ON inpatient_clinical_note FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '已签署临床记录不可修改，请新增更正记录';
END$$

DROP TRIGGER IF EXISTS trg_inpatient_clinical_note_no_delete$$
CREATE TRIGGER trg_inpatient_clinical_note_no_delete BEFORE DELETE ON inpatient_clinical_note FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '已签署临床记录不可删除';
END$$

DELIMITER ;
