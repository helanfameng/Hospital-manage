SET time_zone = '+00:00';

SELECT 'tables' AS metric, COUNT(*) AS actual, 39 AS expected_min
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
UNION ALL
SELECT 'views', COUNT(*), 7
FROM information_schema.views
WHERE table_schema = DATABASE()
UNION ALL
SELECT 'procedures', COUNT(*), 5
FROM information_schema.routines
WHERE routine_schema = DATABASE() AND routine_type = 'PROCEDURE'
UNION ALL
SELECT 'triggers', COUNT(*), 49
FROM information_schema.triggers
WHERE trigger_schema = DATABASE();

SELECT 'patients' AS metric, COUNT(*) AS actual, 30 AS expected_min FROM patient
UNION ALL SELECT 'admissions', COUNT(*), 30 FROM admission
UNION ALL SELECT 'orders', COUNT(*), 75 FROM medical_order
UNION ALL SELECT 'order_items', COUNT(*), 105 FROM order_item
UNION ALL SELECT 'executions', COUNT(*), 87 FROM execution_record
UNION ALL SELECT 'charges', COUNT(*), 73 FROM charge_detail
UNION ALL SELECT 'deposits', COUNT(*), 41 FROM deposit_transaction
UNION ALL SELECT 'transfers', COUNT(*), 1 FROM transfer_record;

SELECT
    SUM(status = 'OCCUPIED') AS occupied_bed_rows,
    (SELECT COUNT(*) FROM bed_usage WHERE end_time IS NULL) AS active_bed_usage_rows,
    (SELECT COUNT(*) FROM admission WHERE current_bed_id IS NOT NULL) AS admissions_with_bed
FROM bed;

SELECT * FROM v_available_beds LIMIT 5;
SELECT * FROM v_current_inpatients LIMIT 5;
SELECT * FROM v_overdue_accounts LIMIT 5;

-- V6-V14 integrity checks: every result below should be zero.
SELECT 'cross_order_group_references' AS check_name, COUNT(*) AS violations
FROM order_item oi
JOIN order_group og ON og.id = oi.group_id
WHERE oi.medical_order_id <> og.medical_order_id
UNION ALL
SELECT 'duplicate_open_alerts', COUNT(*)
FROM (
    SELECT admission_id FROM account_alert WHERE status = 'OPEN'
    GROUP BY admission_id HAVING COUNT(*) > 1
) duplicated
UNION ALL
SELECT 'inventory_delta_mismatches', COUNT(*)
FROM drug_inventory_transaction
WHERE after_quantity <> before_quantity + quantity
UNION ALL
SELECT 'nursing_observations_outside_stay', COUNT(*)
FROM nursing_observation ob
JOIN admission a ON a.id = ob.admission_id
WHERE ob.observed_at < a.admission_time
   OR (a.discharge_time IS NOT NULL AND ob.observed_at > a.discharge_time)
UNION ALL
SELECT 'nursing_observation_missing_measurement', COUNT(*)
FROM nursing_observation
WHERE temperature_c IS NULL AND pulse_rate IS NULL AND respiratory_rate IS NULL
  AND systolic_pressure IS NULL AND diastolic_pressure IS NULL
  AND oxygen_saturation IS NULL AND consciousness IS NULL
UNION ALL
SELECT 'inpatient_result_missing_source', COUNT(*)
FROM inpatient_result ir
LEFT JOIN execution_record er ON er.id = ir.execution_id
LEFT JOIN order_item oi ON oi.id = ir.order_item_id
JOIN medical_order mo ON mo.id = oi.medical_order_id
WHERE er.id IS NULL OR oi.id IS NULL OR mo.admission_id <> ir.admission_id
   OR er.order_item_id <> ir.order_item_id OR er.phase <> 'PERFORM' OR er.status <> 'SUCCESS'
UNION ALL
SELECT 'inpatient_result_missing_value', COUNT(*)
FROM inpatient_result
WHERE result_value IS NULL AND (result_text IS NULL OR CHAR_LENGTH(TRIM(result_text)) = 0)
UNION ALL
SELECT 'inpatient_diagnosis_missing_catalog_snapshot', COUNT(*)
FROM inpatient_diagnosis d
LEFT JOIN diagnosis_catalog c
  ON c.id = d.diagnosis_catalog_id
 AND c.coding_system = d.coding_system_snapshot
 AND c.code = d.diagnosis_code_snapshot
WHERE c.id IS NULL
UNION ALL
SELECT 'duplicate_active_primary_diagnoses', COUNT(*)
FROM (
    SELECT admission_id FROM inpatient_diagnosis
    WHERE diagnosis_type = 'PRIMARY' AND clinical_status = 'ACTIVE'
    GROUP BY admission_id HAVING COUNT(*) > 1
) duplicated_primary
UNION ALL
SELECT 'clinical_note_invalid_amendment_parent', COUNT(*)
FROM inpatient_clinical_note n
LEFT JOIN inpatient_clinical_note parent ON parent.id = n.amends_note_id
WHERE n.amends_note_id IS NOT NULL
  AND (parent.id IS NULL OR parent.admission_id <> n.admission_id OR parent.note_type <> n.note_type)
UNION ALL
SELECT 'clinical_note_empty_content', COUNT(*)
FROM inpatient_clinical_note
WHERE CHAR_LENGTH(TRIM(content)) = 0
UNION ALL
SELECT 'drug_item_missing_medication_plan', COUNT(*)
FROM order_item oi
LEFT JOIN medication_plan mp ON mp.order_item_id = oi.id
WHERE oi.item_type_snapshot = 'DRUG' AND mp.order_item_id IS NULL
UNION ALL
SELECT 'non_drug_with_medication_plan', COUNT(*)
FROM order_item oi
JOIN medication_plan mp ON mp.order_item_id = oi.id
WHERE oi.item_type_snapshot <> 'DRUG'
UNION ALL
SELECT 'medication_plan_outside_order_window', COUNT(*)
FROM medication_plan mp
JOIN order_item oi ON oi.id = mp.order_item_id
WHERE mp.first_dose_at < oi.start_time
   OR (oi.end_time IS NOT NULL AND mp.first_dose_at >= oi.end_time)
UNION ALL
SELECT 'medication_group_frequency_mismatch', COUNT(*)
FROM order_item oi
JOIN order_group og ON og.id = oi.group_id
JOIN medication_plan mp ON mp.order_item_id = oi.id
WHERE og.frequency_code <> mp.frequency_code
   OR NOT (og.start_time <=> mp.first_dose_at)
UNION ALL
SELECT 'medication_execution_outside_plan_window', COUNT(*)
FROM execution_record er
JOIN order_item oi ON oi.id = er.order_item_id
JOIN medication_plan mp ON mp.order_item_id = oi.id
WHERE er.phase = 'ADMINISTER'
  AND (er.execution_time < mp.first_dose_at
       OR (oi.end_time IS NOT NULL AND er.execution_time >= oi.end_time))
UNION ALL
SELECT 'drug_profile_missing_route', COUNT(*)
FROM drug_profile dp
LEFT JOIN drug_profile_route dpr ON dpr.medical_item_id = dp.medical_item_id
WHERE dpr.medical_item_id IS NULL
UNION ALL
SELECT 'drug_profile_route_invalid_reference', COUNT(*)
FROM drug_profile_route dpr
LEFT JOIN drug_profile dp ON dp.medical_item_id = dpr.medical_item_id
LEFT JOIN medication_route mr ON mr.code = dpr.route_code
WHERE dp.medical_item_id IS NULL OR mr.code IS NULL
UNION ALL
SELECT 'charge_missing_category_snapshot', COUNT(*)
FROM charge_detail
WHERE item_type_snapshot IS NULL OR CHAR_LENGTH(TRIM(item_type_snapshot)) = 0
UNION ALL
SELECT 'charge_execution_category_mismatch', COUNT(*)
FROM charge_detail c
JOIN execution_record er ON er.id = c.execution_id
JOIN order_item oi ON oi.id = er.order_item_id
WHERE c.charge_type = 'NORMAL'
  AND c.item_type_snapshot <> oi.item_type_snapshot
UNION ALL
SELECT 'charge_reversal_category_mismatch', COUNT(*)
FROM charge_detail reversal
JOIN charge_detail original ON original.id = reversal.original_charge_id
WHERE reversal.charge_type = 'REVERSAL'
  AND reversal.item_type_snapshot <> original.item_type_snapshot
UNION ALL
SELECT 'charge_missing_catalog_snapshot', COUNT(*)
FROM charge_detail
WHERE CHAR_LENGTH(TRIM(item_code_snapshot)) = 0
   OR CHAR_LENGTH(TRIM(item_name_snapshot)) = 0
   OR CHAR_LENGTH(TRIM(unit_snapshot)) = 0
UNION ALL
SELECT 'charge_execution_catalog_snapshot_mismatch', COUNT(*)
FROM charge_detail c
JOIN execution_record er ON er.id = c.execution_id
JOIN order_item oi ON oi.id = er.order_item_id
WHERE c.charge_type = 'NORMAL'
  AND (c.item_name_snapshot <> oi.item_name_snapshot
       OR c.unit_snapshot <> oi.unit_snapshot)
UNION ALL
SELECT 'legacy_order_item_plan_columns', COUNT(*)
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'order_item'
  AND column_name IN ('planned_administration_count','excess_dispense_reason');

SELECT 'legacy_drug_route_column', COUNT(*) AS violations
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'drug_profile'
  AND column_name = 'allowed_routes';

SELECT 'legacy_prn_frequency_rows' AS check_name, COUNT(*) AS violations
FROM medication_frequency
WHERE code = 'PRN6H'
UNION ALL
SELECT 'legacy_as_needed_column', COUNT(*)
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'medication_frequency'
  AND column_name = 'as_needed';
