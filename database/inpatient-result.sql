SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- V8 closes the inpatient examination/laboratory loop. Results belong to a
-- successful PERFORM execution, never to an unrelated non-inpatient visit.
INSERT INTO permission(code, name) VALUES ('RESULT_RECORD', '住院检查检验结果');

INSERT INTO role_permission(role_id, permission_id)
SELECT r.id, p.id
FROM role r CROSS JOIN permission p
WHERE r.code IN ('ADMIN', 'DOCTOR', 'NURSE') AND p.code = 'RESULT_RECORD';

CREATE TABLE inpatient_result (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    admission_id BIGINT NOT NULL,
    order_item_id BIGINT NOT NULL,
    execution_id BIGINT NOT NULL,
    result_no VARCHAR(64) NOT NULL,
    analyte_code VARCHAR(32) NOT NULL,
    analyte_name VARCHAR(100) NOT NULL,
    result_value DECIMAL(18,6),
    result_text VARCHAR(1000),
    result_unit VARCHAR(32),
    reference_range VARCHAR(100),
    abnormal_flag VARCHAR(16) NOT NULL DEFAULT 'UNMARKED',
    result_status VARCHAR(16) NOT NULL DEFAULT 'FINAL',
    amends_result_id BIGINT,
    amendment_parent_key BIGINT GENERATED ALWAYS AS (COALESCE(amends_result_id, 0)) STORED,
    amendment_reason VARCHAR(255),
    performed_by BIGINT NOT NULL,
    recorded_by BIGINT NOT NULL,
    verified_by BIGINT NOT NULL,
    reported_at TIMESTAMP(3) NOT NULL,
    verified_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_inpatient_result_no UNIQUE (result_no),
    CONSTRAINT uk_inpatient_result_parent_match UNIQUE (id, execution_id, analyte_code),
    CONSTRAINT uk_inpatient_result_analyte_revision UNIQUE (execution_id, analyte_code, amendment_parent_key),
    CONSTRAINT ck_inpatient_result_no_nonblank CHECK (CHAR_LENGTH(TRIM(result_no)) > 0),
    CONSTRAINT ck_inpatient_result_analyte_code_nonblank CHECK (CHAR_LENGTH(TRIM(analyte_code)) > 0),
    CONSTRAINT ck_inpatient_result_analyte_name_nonblank CHECK (CHAR_LENGTH(TRIM(analyte_name)) > 0),
    CONSTRAINT ck_inpatient_result_value CHECK (
        result_value IS NOT NULL OR (result_text IS NOT NULL AND CHAR_LENGTH(TRIM(result_text)) > 0)
    ),
    CONSTRAINT ck_inpatient_result_abnormal_flag CHECK (
        abnormal_flag IN ('NORMAL', 'LOW', 'HIGH', 'CRITICAL', 'UNMARKED')
    ),
    CONSTRAINT ck_inpatient_result_status_shape CHECK (
        (amends_result_id IS NULL AND result_status = 'FINAL' AND amendment_reason IS NULL)
        OR (amends_result_id IS NOT NULL AND result_status = 'AMENDED'
            AND amendment_reason IS NOT NULL AND CHAR_LENGTH(TRIM(amendment_reason)) > 0)
    ),
    CONSTRAINT fk_inpatient_result_admission FOREIGN KEY (admission_id) REFERENCES admission(id),
    CONSTRAINT fk_inpatient_result_item FOREIGN KEY (order_item_id) REFERENCES order_item(id),
    CONSTRAINT fk_inpatient_result_execution FOREIGN KEY (execution_id) REFERENCES execution_record(id),
    CONSTRAINT fk_inpatient_result_amends_match FOREIGN KEY (amends_result_id, execution_id, analyte_code)
        REFERENCES inpatient_result(id, execution_id, analyte_code),
    CONSTRAINT fk_inpatient_result_performer FOREIGN KEY (performed_by) REFERENCES staff(id),
    CONSTRAINT fk_inpatient_result_recorder FOREIGN KEY (recorded_by) REFERENCES user_account(id),
    CONSTRAINT fk_inpatient_result_verifier FOREIGN KEY (verified_by) REFERENCES user_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_inpatient_result_admission_time
    ON inpatient_result (admission_id, reported_at, id);

DELIMITER $$

-- The source execution, item and admission must agree. The creator and
-- verifier must have the dedicated permission and remain in the admission's
-- department unless they are administrators.
DROP TRIGGER IF EXISTS trg_inpatient_result_guard_insert$$
CREATE TRIGGER trg_inpatient_result_guard_insert BEFORE INSERT ON inpatient_result FOR EACH ROW
BEGIN
    DECLARE v_admission BIGINT DEFAULT NULL;
    DECLARE v_item BIGINT DEFAULT NULL;
    DECLARE v_item_type VARCHAR(20) DEFAULT NULL;
    DECLARE v_phase VARCHAR(20) DEFAULT NULL;
    DECLARE v_status VARCHAR(20) DEFAULT NULL;
    DECLARE v_execution_time TIMESTAMP(3) DEFAULT NULL;
    DECLARE v_executor BIGINT DEFAULT NULL;
    SELECT mo.admission_id, oi.id, oi.item_type_snapshot, er.phase, er.status,
           er.execution_time, er.executor_id
      INTO v_admission, v_item, v_item_type, v_phase, v_status, v_execution_time, v_executor
      FROM execution_record er
      JOIN order_item oi ON oi.id = er.order_item_id
      JOIN medical_order mo ON mo.id = oi.medical_order_id
      WHERE er.id = NEW.execution_id;
    IF v_admission IS NULL OR NEW.admission_id <> v_admission OR NEW.order_item_id <> v_item THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '结果来源与住院医嘱执行不一致';
    END IF;
    IF v_item_type IS NULL OR v_item_type NOT IN ('EXAMINATION', 'LAB')
       OR v_phase IS NULL OR v_phase <> 'PERFORM' OR v_status IS NULL OR v_status <> 'SUCCESS'
       OR EXISTS (SELECT 1 FROM execution_record rev WHERE rev.reversed_execution_id = NEW.execution_id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '结果必须来源于未冲正的成功检查或检验执行';
    END IF;
    IF (SELECT status FROM admission WHERE id = NEW.admission_id)
       NOT IN ('IN_HOSPITAL', 'PENDING_DISCHARGE', 'DISCHARGED') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '只有有效住院记录可以登记检查检验结果';
    END IF;
    IF NEW.performed_by <> v_executor THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '结果执行人与原检查执行人不一致';
    END IF;
    IF NEW.reported_at < v_execution_time OR NEW.reported_at > CURRENT_TIMESTAMP(3)
       OR NEW.verified_at < NEW.reported_at OR NEW.verified_at > CURRENT_TIMESTAMP(3) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '结果时间必须位于检查执行之后且不晚于当前时间';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM user_account ua JOIN staff s ON s.id = ua.staff_id
        JOIN user_role ur ON ur.user_id = ua.id
        JOIN role r ON r.id = ur.role_id AND r.active = TRUE
        JOIN role_permission rp ON rp.role_id = r.id
        JOIN permission p ON p.id = rp.permission_id AND p.code = 'RESULT_RECORD'
        JOIN admission a ON a.id = NEW.admission_id
        WHERE ua.id = NEW.recorded_by AND ua.enabled = TRUE AND s.active = TRUE
          AND (s.staff_type = 'ADMIN' OR s.department_id = a.department_id)
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '结果只能由有权限的在职住院医护人员记录';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM user_account ua JOIN staff s ON s.id = ua.staff_id
        JOIN user_role ur ON ur.user_id = ua.id
        JOIN role r ON r.id = ur.role_id AND r.active = TRUE
        JOIN role_permission rp ON rp.role_id = r.id
        JOIN permission p ON p.id = rp.permission_id AND p.code = 'RESULT_RECORD'
        JOIN admission a ON a.id = NEW.admission_id
        WHERE ua.id = NEW.verified_by AND ua.enabled = TRUE AND s.active = TRUE
          AND (s.staff_type = 'ADMIN' OR s.department_id = a.department_id)
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '结果审核人无效或无住院结果权限';
    END IF;
END$$

DROP TRIGGER IF EXISTS trg_inpatient_result_immutable_update$$
CREATE TRIGGER trg_inpatient_result_immutable_update BEFORE UPDATE ON inpatient_result FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '检查检验结果不可修改，请新增更正结果';
END$$

DROP TRIGGER IF EXISTS trg_inpatient_result_immutable_delete$$
CREATE TRIGGER trg_inpatient_result_immutable_delete BEFORE DELETE ON inpatient_result FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '检查检验结果不可删除';
END$$

DELIMITER ;
