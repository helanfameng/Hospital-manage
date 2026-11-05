CREATE OR REPLACE VIEW v_current_inpatients AS
SELECT
    a.id AS admission_id,
    a.admission_no,
    p.id AS patient_id,
    p.patient_no,
    p.name AS patient_name,
    p.gender,
    d.id AS department_id,
    d.name AS department_name,
    s.name AS attending_doctor_name,
    w.name AS ward_name,
    r.room_no,
    b.id AS bed_id,
    b.bed_no,
    a.admission_time,
    a.status
FROM admission a
JOIN patient p ON p.id = a.patient_id
JOIN department d ON d.id = a.department_id
JOIN staff s ON s.id = a.attending_doctor_id
LEFT JOIN bed b ON b.id = a.current_bed_id
LEFT JOIN room r ON r.id = b.room_id
LEFT JOIN ward w ON w.id = r.ward_id
WHERE a.status IN ('IN_HOSPITAL', 'PENDING_DISCHARGE');

CREATE OR REPLACE VIEW v_available_beds AS
SELECT
    b.id AS bed_id,
    d.id AS department_id,
    d.name AS department_name,
    w.id AS ward_id,
    w.name AS ward_name,
    r.id AS room_id,
    r.room_no,
    b.bed_no,
    b.status
FROM bed b
JOIN room r ON r.id = b.room_id
JOIN ward w ON w.id = r.ward_id
JOIN department d ON d.id = w.department_id
WHERE b.status = 'AVAILABLE' AND b.active = TRUE AND r.active = TRUE AND w.active = TRUE;

CREATE OR REPLACE VIEW v_admission_charge_summary AS
SELECT
    a.id AS admission_id,
    a.admission_no,
    COALESCE(SUM(c.amount), 0.00) AS total_charge,
    COALESCE(SUM(CASE WHEN mi.item_type = 'DRUG' THEN c.amount ELSE 0 END), 0.00) AS drug_charge,
    COALESCE(SUM(CASE WHEN mi.item_type = 'EXAMINATION' THEN c.amount ELSE 0 END), 0.00) AS examination_charge,
    COALESCE(SUM(CASE WHEN mi.item_type = 'LAB' THEN c.amount ELSE 0 END), 0.00) AS lab_charge,
    COALESCE(SUM(CASE WHEN mi.item_type = 'TREATMENT' THEN c.amount ELSE 0 END), 0.00) AS treatment_charge,
    COALESCE(SUM(CASE WHEN mi.item_type = 'BED_FEE' THEN c.amount ELSE 0 END), 0.00) AS bed_charge
FROM admission a
LEFT JOIN charge_detail c ON c.admission_id = a.id
LEFT JOIN medical_item mi ON mi.id = c.medical_item_id
GROUP BY a.id, a.admission_no;

CREATE OR REPLACE VIEW v_admission_deposit_balance AS
SELECT
    a.id AS admission_id,
    a.admission_no,
    COALESCE(SUM(dt.amount), 0.00) AS deposit_balance,
    cs.total_charge,
    COALESCE(SUM(dt.amount), 0.00) - cs.total_charge AS available_balance
FROM admission a
JOIN v_admission_charge_summary cs ON cs.admission_id = a.id
LEFT JOIN deposit_transaction dt ON dt.admission_id = a.id
GROUP BY a.id, a.admission_no, cs.total_charge;

CREATE OR REPLACE VIEW v_overdue_accounts AS
SELECT
    db.admission_id,
    db.admission_no,
    p.patient_no,
    p.name AS patient_name,
    d.name AS department_name,
    db.deposit_balance,
    db.total_charge,
    db.available_balance
FROM v_admission_deposit_balance db
JOIN admission a ON a.id = db.admission_id
JOIN patient p ON p.id = a.patient_id
JOIN department d ON d.id = a.department_id
WHERE a.status IN ('IN_HOSPITAL','PENDING_DISCHARGE') AND db.available_balance < 0;

-- Revenue follows the department recorded on the charge, so later transfers never move history.
CREATE OR REPLACE VIEW v_department_revenue AS
SELECT
    d.id AS department_id,
    d.name AS department_name,
    DATE(c.charged_at) AS revenue_date,
    mi.item_type,
    COUNT(*) AS charge_count,
    SUM(c.amount) AS revenue
FROM charge_detail c
JOIN department d ON d.id = c.department_id
JOIN medical_item mi ON mi.id = c.medical_item_id
GROUP BY d.id, d.name, DATE(c.charged_at), mi.item_type;

-- Dispensing is recorded in stock units and administration in dose units.
-- This is the reconciliation source for medication remaining quantities.
CREATE OR REPLACE VIEW v_order_item_medication_balance AS
SELECT
    oi.id AS order_item_id,
    oi.medical_order_id,
    oi.medical_item_id,
    oi.unit_snapshot AS stock_unit,
    oi.dose_unit,
    oi.dose_units_per_stock,
    oi.dispensed_quantity,
    oi.executed_quantity AS administered_dose_quantity,
    oi.dispensed_quantity * oi.dose_units_per_stock AS dispensed_dose_quantity,
    GREATEST(oi.dispensed_quantity * oi.dose_units_per_stock - oi.executed_quantity, 0) AS remaining_dose_quantity,
    GREATEST(oi.dispensed_quantity - oi.executed_quantity / oi.dose_units_per_stock, 0) AS remaining_stock_quantity
FROM order_item oi
WHERE oi.item_type_snapshot = 'DRUG';
