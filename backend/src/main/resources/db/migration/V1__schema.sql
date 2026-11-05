SET NAMES utf8mb4;
SET time_zone = '+00:00';

CREATE TABLE department (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_department_code UNIQUE (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ward (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    department_id BIGINT NOT NULL,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_ward_code UNIQUE (code),
    CONSTRAINT fk_ward_department FOREIGN KEY (department_id) REFERENCES department(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE room (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    ward_id BIGINT NOT NULL,
    room_no VARCHAR(32) NOT NULL,
    room_type VARCHAR(32) NOT NULL DEFAULT 'GENERAL',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_room_ward_no UNIQUE (ward_id, room_no),
    CONSTRAINT fk_room_ward FOREIGN KEY (ward_id) REFERENCES ward(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE bed (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    room_id BIGINT NOT NULL,
    bed_no VARCHAR(32) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    version INT NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_bed_room_no UNIQUE (room_id, bed_no),
    CONSTRAINT ck_bed_status CHECK (status IN ('AVAILABLE','OCCUPIED','MAINTENANCE','DISABLED')),
    CONSTRAINT fk_bed_room FOREIGN KEY (room_id) REFERENCES room(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE staff (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    staff_no VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    department_id BIGINT,
    staff_type VARCHAR(24) NOT NULL,
    phone VARCHAR(32),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_staff_no UNIQUE (staff_no),
    CONSTRAINT ck_staff_type CHECK (staff_type IN ('ADMIN','ADMISSION','DOCTOR','NURSE','PHARMACY','CASHIER')),
    CONSTRAINT fk_staff_department FOREIGN KEY (department_id) REFERENCES department(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_account (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    staff_id BIGINT,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    token_version INT NOT NULL DEFAULT 0,
    password_changed_at TIMESTAMP(3),
    last_login_at TIMESTAMP(3),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_user_username UNIQUE (username),
    CONSTRAINT uk_user_staff UNIQUE (staff_id),
    CONSTRAINT fk_user_staff FOREIGN KEY (staff_id) REFERENCES staff(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE role (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uk_role_code UNIQUE (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE permission (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(100) NOT NULL,
    CONSTRAINT uk_permission_code UNIQUE (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES user_account(id),
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES role(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE role_permission (
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES role(id),
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES permission(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE patient (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    patient_no VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    gender VARCHAR(10) NOT NULL,
    birth_date DATE NOT NULL,
    identity_number VARCHAR(32) NOT NULL,
    phone VARCHAR(32),
    address VARCHAR(255),
    emergency_contact VARCHAR(100),
    emergency_phone VARCHAR(32),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_patient_no UNIQUE (patient_no),
    CONSTRAINT uk_patient_identity UNIQUE (identity_number),
    CONSTRAINT ck_patient_gender CHECK (gender IN ('MALE','FEMALE','OTHER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- active_patient_key keeps one open admission per patient; it is NULL once the stay closes.
CREATE TABLE admission (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    admission_no VARCHAR(32) NOT NULL,
    patient_id BIGINT NOT NULL,
    department_id BIGINT NOT NULL,
    attending_doctor_id BIGINT NOT NULL,
    current_bed_id BIGINT,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    admission_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    discharge_time TIMESTAMP(3),
    diagnosis_summary VARCHAR(500),
    nursing_level VARCHAR(24) NOT NULL DEFAULT 'LEVEL_III',
    version INT NOT NULL DEFAULT 0,
    created_by BIGINT,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    active_patient_key BIGINT GENERATED ALWAYS AS
        (CASE WHEN status IN ('PENDING','IN_HOSPITAL','PENDING_DISCHARGE') THEN patient_id END) STORED,
    CONSTRAINT uk_admission_no UNIQUE (admission_no),
    CONSTRAINT uk_admission_active_patient UNIQUE (active_patient_key),
    CONSTRAINT ck_admission_status CHECK (status IN ('PENDING','IN_HOSPITAL','PENDING_DISCHARGE','DISCHARGED','CANCELLED')),
    CONSTRAINT ck_admission_time CHECK (discharge_time IS NULL OR discharge_time >= admission_time),
    CONSTRAINT fk_admission_patient FOREIGN KEY (patient_id) REFERENCES patient(id),
    CONSTRAINT fk_admission_department FOREIGN KEY (department_id) REFERENCES department(id),
    CONSTRAINT fk_admission_doctor FOREIGN KEY (attending_doctor_id) REFERENCES staff(id),
    CONSTRAINT fk_admission_bed FOREIGN KEY (current_bed_id) REFERENCES bed(id),
    CONSTRAINT fk_admission_creator FOREIGN KEY (created_by) REFERENCES user_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- One open bed_usage per bed and per admission; both keys go NULL when the stay on that bed ends.
CREATE TABLE bed_usage (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    admission_id BIGINT NOT NULL,
    bed_id BIGINT NOT NULL,
    start_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    end_time TIMESTAMP(3),
    end_reason VARCHAR(32),
    assigned_by BIGINT,
    active_bed_key BIGINT GENERATED ALWAYS AS (CASE WHEN end_time IS NULL THEN bed_id END) STORED,
    active_admission_key BIGINT GENERATED ALWAYS AS (CASE WHEN end_time IS NULL THEN admission_id END) STORED,
    CONSTRAINT uk_bed_usage_active UNIQUE (active_bed_key),
    CONSTRAINT uk_bed_usage_active_admission UNIQUE (active_admission_key),
    CONSTRAINT ck_bed_usage_time CHECK (end_time IS NULL OR end_time >= start_time),
    CONSTRAINT fk_bed_usage_admission FOREIGN KEY (admission_id) REFERENCES admission(id),
    CONSTRAINT fk_bed_usage_bed FOREIGN KEY (bed_id) REFERENCES bed(id),
    CONSTRAINT fk_bed_usage_user FOREIGN KEY (assigned_by) REFERENCES user_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE transfer_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    admission_id BIGINT NOT NULL,
    transfer_type VARCHAR(20) NOT NULL,
    from_department_id BIGINT,
    to_department_id BIGINT,
    from_bed_id BIGINT,
    to_bed_id BIGINT,
    from_doctor_id BIGINT,
    to_doctor_id BIGINT,
    reason VARCHAR(500) NOT NULL,
    transferred_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    operated_by BIGINT,
    CONSTRAINT ck_transfer_type CHECK (transfer_type IN ('BED','DEPARTMENT','BOTH')),
    CONSTRAINT fk_transfer_admission FOREIGN KEY (admission_id) REFERENCES admission(id),
    CONSTRAINT fk_transfer_from_department FOREIGN KEY (from_department_id) REFERENCES department(id),
    CONSTRAINT fk_transfer_to_department FOREIGN KEY (to_department_id) REFERENCES department(id),
    CONSTRAINT fk_transfer_from_bed FOREIGN KEY (from_bed_id) REFERENCES bed(id),
    CONSTRAINT fk_transfer_to_bed FOREIGN KEY (to_bed_id) REFERENCES bed(id),
    CONSTRAINT fk_transfer_from_doctor FOREIGN KEY (from_doctor_id) REFERENCES staff(id),
    CONSTRAINT fk_transfer_to_doctor FOREIGN KEY (to_doctor_id) REFERENCES staff(id),
    CONSTRAINT fk_transfer_operator FOREIGN KEY (operated_by) REFERENCES user_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- billable separates chargeable goods from nursing/diet/other instructions.
-- inventory_managed marks stock-controlled drugs; execution_role names the responsible post.
CREATE TABLE medical_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    item_code VARCHAR(32) NOT NULL,
    name VARCHAR(120) NOT NULL,
    item_type VARCHAR(20) NOT NULL,
    unit VARCHAR(20) NOT NULL,
    unit_price DECIMAL(12,2) NOT NULL,
    specification VARCHAR(120),
    dosage_form VARCHAR(64),
    stock_quantity DECIMAL(12,3),
    low_stock_threshold DECIMAL(12,3),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    billable BOOLEAN NOT NULL DEFAULT TRUE,
    inventory_managed BOOLEAN NOT NULL DEFAULT FALSE,
    execution_role VARCHAR(20) NOT NULL DEFAULT 'NURSE',
    allow_fractional_quantity BOOLEAN NOT NULL DEFAULT FALSE,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_medical_item_code UNIQUE (item_code),
    CONSTRAINT ck_medical_item_type CHECK (item_type IN ('DRUG','EXAMINATION','LAB','TREATMENT','NURSING','DIET','OTHER','BED_FEE')),
    CONSTRAINT ck_medical_item_price CHECK (unit_price >= 0),
    CONSTRAINT ck_medical_item_stock CHECK (stock_quantity IS NULL OR stock_quantity >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Stock unit to dose unit conversion, kept explicit and never inferred from free text.
CREATE TABLE drug_profile (
    medical_item_id BIGINT PRIMARY KEY,
    dose_unit VARCHAR(20) NOT NULL,
    dose_units_per_stock DECIMAL(18,6) NOT NULL,
    allowed_routes VARCHAR(255) NOT NULL,
    CONSTRAINT fk_drug_profile_item FOREIGN KEY (medical_item_id) REFERENCES medical_item(id),
    CONSTRAINT ck_drug_profile_conversion CHECK (dose_units_per_stock > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The order header carries no long-term/temporary flag: that is a per-item property.
CREATE TABLE medical_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_no VARCHAR(32) NOT NULL,
    admission_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    start_time TIMESTAMP(3),
    planned_end_time TIMESTAMP(3),
    submitted_at TIMESTAMP(3),
    stopped_at TIMESTAMP(3),
    note VARCHAR(500),
    prescribed_by BIGINT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_medical_order_no UNIQUE (order_no),
    CONSTRAINT ck_order_status CHECK (status IN ('DRAFT','SUBMITTED','IN_PROGRESS','COMPLETED','STOPPED','CANCELLED')),
    CONSTRAINT ck_order_time CHECK (planned_end_time IS NULL OR start_time IS NULL OR planned_end_time >= start_time),
    CONSTRAINT fk_order_admission FOREIGN KEY (admission_id) REFERENCES admission(id),
    CONSTRAINT fk_order_prescriber FOREIGN KEY (prescribed_by) REFERENCES staff(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- A compounding group shares one route, one type and one schedule across its drugs.
CREATE TABLE order_group (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    medical_order_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    route VARCHAR(64) NOT NULL,
    order_type VARCHAR(20) NOT NULL,
    start_time TIMESTAMP(3) NOT NULL,
    end_time TIMESTAMP(3),
    CONSTRAINT fk_order_group_order FOREIGN KEY (medical_order_id) REFERENCES medical_order(id),
    CONSTRAINT ck_order_group_type CHECK (order_type IN ('TEMPORARY','LONG_TERM')),
    CONSTRAINT ck_order_group_dates CHECK (end_time IS NULL OR end_time >= start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Snapshots freeze the catalog wording and price at prescribing time.
-- dispensed_quantity counts stock units; administered_quantity counts dose units.
CREATE TABLE order_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    medical_order_id BIGINT NOT NULL,
    medical_item_id BIGINT NOT NULL,
    group_id BIGINT,
    dosage DECIMAL(12,3),
    quantity_per_execution DECIMAL(12,3) NOT NULL,
    route VARCHAR(64),
    instructions VARCHAR(255),
    item_name_snapshot VARCHAR(120),
    item_type_snapshot VARCHAR(20),
    unit_snapshot VARCHAR(20),
    dose_unit VARCHAR(20),
    dose_units_per_stock DECIMAL(18,6),
    price_snapshot DECIMAL(12,2),
    billable BOOLEAN NOT NULL DEFAULT TRUE,
    inventory_managed BOOLEAN NOT NULL DEFAULT FALSE,
    execution_role VARCHAR(20),
    order_type VARCHAR(20),
    item_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    start_time TIMESTAMP(3),
    end_time TIMESTAMP(3),
    planned_administration_count INT,
    excess_dispense_reason VARCHAR(255),
    dispensed_quantity DECIMAL(12,3) NOT NULL DEFAULT 0,
    administered_quantity DECIMAL(12,3) NOT NULL DEFAULT 0,
    executed_quantity DECIMAL(12,3) NOT NULL DEFAULT 0,
    CONSTRAINT ck_order_item_dosage CHECK (dosage IS NULL OR dosage > 0),
    CONSTRAINT ck_order_item_quantity CHECK (quantity_per_execution > 0 AND executed_quantity >= 0),
    CONSTRAINT ck_order_item_type CHECK (order_type IS NULL OR order_type IN ('TEMPORARY','LONG_TERM')),
    CONSTRAINT ck_order_item_status CHECK (item_status IN ('DRAFT','SUBMITTED','IN_PROGRESS','COMPLETED','STOPPED','CANCELLED')),
    CONSTRAINT ck_order_item_dates CHECK (end_time IS NULL OR start_time IS NULL OR end_time >= start_time),
    CONSTRAINT ck_order_item_balances CHECK (dispensed_quantity >= 0 AND administered_quantity >= 0),
    CONSTRAINT ck_order_item_administration_plan
        CHECK (planned_administration_count IS NULL OR planned_administration_count BETWEEN 1 AND 1000),
    CONSTRAINT fk_order_item_order FOREIGN KEY (medical_order_id) REFERENCES medical_order(id),
    CONSTRAINT fk_order_item_catalog FOREIGN KEY (medical_item_id) REFERENCES medical_item(id),
    CONSTRAINT fk_order_item_group FOREIGN KEY (group_id) REFERENCES order_group(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Dispensing, administration and non-drug work are separate, individually reversible facts.
-- batch_key ties one compounding group's executions together.
CREATE TABLE execution_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_no VARCHAR(64) NOT NULL,
    order_item_id BIGINT NOT NULL,
    execution_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    quantity DECIMAL(12,3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    result_note VARCHAR(500),
    executor_id BIGINT NOT NULL,
    reversed_execution_id BIGINT,
    phase VARCHAR(20) NOT NULL DEFAULT 'PERFORM',
    batch_key VARCHAR(64),
    unit_snapshot VARCHAR(20),
    CONSTRAINT uk_execution_request UNIQUE (request_no),
    CONSTRAINT uk_execution_reversal UNIQUE (reversed_execution_id),
    CONSTRAINT ck_execution_quantity CHECK (quantity >= 0),
    CONSTRAINT ck_execution_status CHECK (status IN ('SUCCESS','FAILED','REVERSED','NOT_DONE')),
    CONSTRAINT ck_execution_phase CHECK (phase IN ('DISPENSE','ADMINISTER','PERFORM')),
    CONSTRAINT fk_execution_order_item FOREIGN KEY (order_item_id) REFERENCES order_item(id),
    CONSTRAINT fk_execution_executor FOREIGN KEY (executor_id) REFERENCES staff(id),
    CONSTRAINT fk_execution_reversed FOREIGN KEY (reversed_execution_id) REFERENCES execution_record(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE drug_inventory_transaction (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    medical_item_id BIGINT NOT NULL,
    execution_id BIGINT,
    transaction_type VARCHAR(20) NOT NULL,
    quantity DECIMAL(12,3) NOT NULL,
    before_quantity DECIMAL(12,3) NOT NULL,
    after_quantity DECIMAL(12,3) NOT NULL,
    note VARCHAR(255),
    operated_by BIGINT,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT ck_inventory_type CHECK (transaction_type IN ('INBOUND','DISPENSE','RETURN','ADJUSTMENT')),
    CONSTRAINT ck_inventory_quantity CHECK (quantity <> 0 AND before_quantity >= 0 AND after_quantity >= 0),
    CONSTRAINT fk_inventory_item FOREIGN KEY (medical_item_id) REFERENCES medical_item(id),
    CONSTRAINT fk_inventory_execution FOREIGN KEY (execution_id) REFERENCES execution_record(id),
    CONSTRAINT fk_inventory_operator FOREIGN KEY (operated_by) REFERENCES user_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE deposit_transaction (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    admission_id BIGINT NOT NULL,
    transaction_no VARCHAR(32) NOT NULL,
    transaction_type VARCHAR(20) NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    original_transaction_id BIGINT,
    payment_method VARCHAR(20),
    note VARCHAR(255),
    request_no VARCHAR(64),
    operated_by BIGINT,
    transaction_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_deposit_no UNIQUE (transaction_no),
    CONSTRAINT uk_deposit_reversal UNIQUE (original_transaction_id),
    CONSTRAINT uk_deposit_request UNIQUE (request_no),
    CONSTRAINT ck_deposit_type CHECK (transaction_type IN ('INITIAL','SUPPLEMENT','REFUND','REVERSAL')),
    CONSTRAINT ck_deposit_amount CHECK (amount <> 0),
    CONSTRAINT fk_deposit_admission FOREIGN KEY (admission_id) REFERENCES admission(id),
    CONSTRAINT fk_deposit_original FOREIGN KEY (original_transaction_id) REFERENCES deposit_transaction(id),
    CONSTRAINT fk_deposit_operator FOREIGN KEY (operated_by) REFERENCES user_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- department_id records where the charge actually happened, surviving later transfers.
CREATE TABLE charge_detail (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    admission_id BIGINT NOT NULL,
    execution_id BIGINT,
    medical_item_id BIGINT NOT NULL,
    department_id BIGINT NOT NULL,
    charge_no VARCHAR(32) NOT NULL,
    charge_type VARCHAR(20) NOT NULL,
    unit_price_snapshot DECIMAL(12,2) NOT NULL,
    quantity DECIMAL(12,3) NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    original_charge_id BIGINT,
    note VARCHAR(255),
    charged_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_charge_no UNIQUE (charge_no),
    CONSTRAINT uk_charge_reversal UNIQUE (original_charge_id),
    CONSTRAINT ck_charge_type CHECK (charge_type IN ('NORMAL','REVERSAL')),
    CONSTRAINT ck_charge_price CHECK (unit_price_snapshot >= 0),
    CONSTRAINT ck_charge_quantity CHECK (quantity <> 0),
    CONSTRAINT ck_charge_amount CHECK (amount <> 0),
    CONSTRAINT fk_charge_admission FOREIGN KEY (admission_id) REFERENCES admission(id),
    CONSTRAINT fk_charge_execution FOREIGN KEY (execution_id) REFERENCES execution_record(id),
    CONSTRAINT fk_charge_item FOREIGN KEY (medical_item_id) REFERENCES medical_item(id),
    CONSTRAINT fk_charge_department FOREIGN KEY (department_id) REFERENCES department(id),
    CONSTRAINT fk_charge_original FOREIGN KEY (original_charge_id) REFERENCES charge_detail(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE settlement (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    settlement_no VARCHAR(32) NOT NULL,
    admission_id BIGINT NOT NULL,
    total_charge DECIMAL(12,2) NOT NULL,
    total_deposit DECIMAL(12,2) NOT NULL,
    amount_due DECIMAL(12,2) NOT NULL,
    refund_amount DECIMAL(12,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    settled_by BIGINT,
    settled_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_settlement_no UNIQUE (settlement_no),
    CONSTRAINT uk_settlement_admission UNIQUE (admission_id),
    CONSTRAINT ck_settlement_amounts CHECK (
        total_charge >= 0 AND total_deposit >= 0 AND amount_due >= 0 AND refund_amount >= 0
    ),
    CONSTRAINT ck_settlement_status CHECK (status IN ('COMPLETED','VOIDED')),
    CONSTRAINT fk_settlement_admission FOREIGN KEY (admission_id) REFERENCES admission(id),
    CONSTRAINT fk_settlement_user FOREIGN KEY (settled_by) REFERENCES user_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE settlement_detail (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    settlement_id BIGINT NOT NULL,
    category VARCHAR(20) NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    item_count INT NOT NULL,
    CONSTRAINT ck_settlement_detail_amount CHECK (amount >= 0 AND item_count >= 0),
    CONSTRAINT fk_settlement_detail_settlement FOREIGN KEY (settlement_id) REFERENCES settlement(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE account_alert (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    admission_id BIGINT NOT NULL,
    alert_type VARCHAR(24) NOT NULL DEFAULT 'LOW_BALANCE',
    level VARCHAR(16) NOT NULL DEFAULT 'WARNING',
    balance_snapshot DECIMAL(12,2) NOT NULL,
    threshold_snapshot DECIMAL(12,2) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    resolved_by BIGINT,
    resolved_at TIMESTAMP(3),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT ck_alert_level CHECK (level IN ('INFO','WARNING','CRITICAL')),
    CONSTRAINT ck_alert_status CHECK (status IN ('OPEN','RESOLVED')),
    CONSTRAINT fk_alert_admission FOREIGN KEY (admission_id) REFERENCES admission(id),
    CONSTRAINT fk_alert_resolver FOREIGN KEY (resolved_by) REFERENCES user_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE order_template (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_order_template_creator FOREIGN KEY (created_by) REFERENCES user_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Templates expand into independent order items; they are never a bundled charge.
CREATE TABLE order_template_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    template_id BIGINT NOT NULL,
    medical_item_id BIGINT NOT NULL,
    sort_order INT NOT NULL,
    CONSTRAINT uk_order_template_item UNIQUE (template_id, medical_item_id),
    CONSTRAINT fk_order_template_item_template FOREIGN KEY (template_id) REFERENCES order_template(id),
    CONSTRAINT fk_order_template_item_catalog FOREIGN KEY (medical_item_id) REFERENCES medical_item(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Charge numbers come from this sequence only. Gaps are intentional; never reset it.
CREATE TABLE charge_number_sequence (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    allocated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE audit_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT,
    username VARCHAR(64),
    role_codes VARCHAR(255),
    action VARCHAR(64) NOT NULL,
    object_type VARCHAR(64) NOT NULL,
    object_id VARCHAR(64),
    result VARCHAR(16) NOT NULL,
    before_value JSON,
    after_value JSON,
    ip_address VARCHAR(64),
    trace_id VARCHAR(64),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT ck_audit_result CHECK (result IN ('SUCCESS','FAILURE')),
    CONSTRAINT fk_audit_user FOREIGN KEY (user_id) REFERENCES user_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
