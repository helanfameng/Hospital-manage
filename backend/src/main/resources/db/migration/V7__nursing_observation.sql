SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- V7 adds the inpatient nursing observation ledger. It intentionally models a
-- point-in-time observation rather than an outpatient visit or a diagnosis.
INSERT INTO permission(code, name) VALUES ('NURSING_RECORD', '护理观察记录');

INSERT INTO role_permission(role_id, permission_id)
SELECT r.id, p.id
FROM role r CROSS JOIN permission p
WHERE r.code IN ('ADMIN', 'NURSE') AND p.code = 'NURSING_RECORD';

CREATE TABLE nursing_observation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    admission_id BIGINT NOT NULL,
    request_no VARCHAR(64) NOT NULL,
    observed_at TIMESTAMP(3) NOT NULL,
    recorded_by BIGINT NOT NULL,
    temperature_c DECIMAL(4,1),
    pulse_rate SMALLINT,
    respiratory_rate SMALLINT,
    systolic_pressure SMALLINT,
    diastolic_pressure SMALLINT,
    oxygen_saturation DECIMAL(5,2),
    consciousness VARCHAR(20),
    note VARCHAR(500),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_nursing_observation_request UNIQUE (request_no),
    CONSTRAINT uk_nursing_observation_time UNIQUE (admission_id, observed_at),
    CONSTRAINT ck_nursing_observation_request_nonblank CHECK (CHAR_LENGTH(TRIM(request_no)) > 0),
    CONSTRAINT ck_nursing_observation_temperature CHECK (
        temperature_c IS NULL OR temperature_c BETWEEN 30.0 AND 45.0
    ),
    CONSTRAINT ck_nursing_observation_pulse CHECK (
        pulse_rate IS NULL OR pulse_rate BETWEEN 20 AND 250
    ),
    CONSTRAINT ck_nursing_observation_respiratory CHECK (
        respiratory_rate IS NULL OR respiratory_rate BETWEEN 5 AND 80
    ),
    CONSTRAINT ck_nursing_observation_systolic CHECK (
        systolic_pressure IS NULL OR systolic_pressure BETWEEN 50 AND 300
    ),
    CONSTRAINT ck_nursing_observation_diastolic CHECK (
        diastolic_pressure IS NULL OR diastolic_pressure BETWEEN 20 AND 200
    ),
    CONSTRAINT ck_nursing_observation_pressure_order CHECK (
        systolic_pressure IS NULL OR diastolic_pressure IS NULL OR systolic_pressure > diastolic_pressure
    ),
    CONSTRAINT ck_nursing_observation_pressure_pair CHECK (
        (systolic_pressure IS NULL) = (diastolic_pressure IS NULL)
    ),
    CONSTRAINT ck_nursing_observation_oxygen CHECK (
        oxygen_saturation IS NULL OR oxygen_saturation BETWEEN 0.0 AND 100.0
    ),
    CONSTRAINT ck_nursing_observation_consciousness CHECK (
        consciousness IS NULL OR consciousness IN ('CLEAR', 'CONFUSED', 'DROWSY', 'UNRESPONSIVE')
    ),
    CONSTRAINT ck_nursing_observation_has_measurement CHECK (
        temperature_c IS NOT NULL OR pulse_rate IS NOT NULL OR respiratory_rate IS NOT NULL
        OR systolic_pressure IS NOT NULL OR diastolic_pressure IS NOT NULL
        OR oxygen_saturation IS NOT NULL OR consciousness IS NOT NULL
    ),
    CONSTRAINT fk_nursing_observation_admission FOREIGN KEY (admission_id) REFERENCES admission(id),
    CONSTRAINT fk_nursing_observation_recorder FOREIGN KEY (recorded_by) REFERENCES user_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DELIMITER $$

-- The observation must belong to an active inpatient stay, fall inside the
-- stay's time window, and be recorded by an enabled nurse or administrator.
DROP TRIGGER IF EXISTS trg_nursing_observation_guard_insert$$
CREATE TRIGGER trg_nursing_observation_guard_insert BEFORE INSERT ON nursing_observation FOR EACH ROW
BEGIN
    DECLARE v_admission_time TIMESTAMP(3);
    DECLARE v_discharge_time TIMESTAMP(3);
    DECLARE v_status VARCHAR(24);
    SELECT admission_time, discharge_time, status
      INTO v_admission_time, v_discharge_time, v_status
      FROM admission WHERE id = NEW.admission_id;
    IF v_status IS NULL OR v_status NOT IN ('IN_HOSPITAL', 'PENDING_DISCHARGE') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '只有住院中或待出院的住院记录可以登记护理观察';
    END IF;
    IF NEW.observed_at < v_admission_time
       OR NEW.observed_at > CURRENT_TIMESTAMP(3)
       OR (v_discharge_time IS NOT NULL AND NEW.observed_at > v_discharge_time) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '观察时间必须位于本次住院有效时间内';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM user_account ua JOIN staff s ON s.id = ua.staff_id
        JOIN admission a ON a.id = NEW.admission_id
        WHERE ua.id = NEW.recorded_by AND ua.enabled = TRUE
          AND s.active = TRUE AND s.staff_type IN ('NURSE', 'ADMIN')
          AND (s.staff_type = 'ADMIN' OR s.department_id = a.department_id)
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '护理观察只能由在职护士或管理员记录';
    END IF;
END$$

-- Nursing observations are an append-only clinical ledger. Corrections must
-- be represented by a new observation, preserving the original fact.
DROP TRIGGER IF EXISTS trg_nursing_observation_immutable_update$$
CREATE TRIGGER trg_nursing_observation_immutable_update BEFORE UPDATE ON nursing_observation FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '护理观察记录不可修改，请新增更正记录';
END$$

DROP TRIGGER IF EXISTS trg_nursing_observation_immutable_delete$$
CREATE TRIGGER trg_nursing_observation_immutable_delete BEFORE DELETE ON nursing_observation FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '护理观察记录不可删除';
END$$

DELIMITER ;
