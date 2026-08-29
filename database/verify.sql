SET time_zone = '+00:00';

SELECT 'tables' AS metric, COUNT(*) AS actual, 25 AS expected_min
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
UNION ALL
SELECT 'views', COUNT(*), 6
FROM information_schema.views
WHERE table_schema = DATABASE()
UNION ALL
SELECT 'procedures', COUNT(*), 5
FROM information_schema.routines
WHERE routine_schema = DATABASE() AND routine_type = 'PROCEDURE'
UNION ALL
SELECT 'triggers', COUNT(*), 20
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
