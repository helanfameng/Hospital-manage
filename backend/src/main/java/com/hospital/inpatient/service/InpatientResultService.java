package com.hospital.inpatient.service;

import com.hospital.inpatient.dto.Requests;
import com.hospital.inpatient.exception.BusinessException;
import com.hospital.inpatient.security.SecurityUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class InpatientResultService {
    private static final String SELECT = """
            SELECT ir.id, ir.admission_id, ir.order_item_id, ir.execution_id, ir.result_no,
                   ir.analyte_code, ir.analyte_name, ir.result_value, ir.result_text,
                   ir.result_unit, ir.reference_range, ir.abnormal_flag, ir.result_status,
                   ir.amends_result_id, ir.amendment_reason, ir.performed_by,
                   ir.recorded_by, ir.verified_by, ir.reported_at, ir.verified_at, ir.created_at,
                   oi.item_name_snapshot AS item_name, oi.item_type_snapshot AS item_type,
                   mo.order_no, performer.name AS performed_by_name,
                   recorder.display_name AS recorded_by_name,
                   verifier.display_name AS verified_by_name,
                   EXISTS(SELECT 1 FROM inpatient_result child
                          WHERE child.amends_result_id = ir.id) AS is_superseded
            FROM inpatient_result ir
            JOIN order_item oi ON oi.id = ir.order_item_id
            JOIN medical_order mo ON mo.id = oi.medical_order_id
            JOIN staff performer ON performer.id = ir.performed_by
            JOIN user_account recorder ON recorder.id = ir.recorded_by
            JOIN user_account verifier ON verifier.id = ir.verified_by
            WHERE ir.admission_id = ?
            ORDER BY ir.reported_at DESC, ir.id DESC
            """;

    private final AccountGuard guard;
    private final AuditService audit;

    public InpatientResultService(AccountGuard guard, AuditService audit) {
        this.guard = guard;
        this.audit = audit;
    }

    public List<Map<String, Object>> list(long admissionId) {
        guard.requireRead(admissionId);
        return guard.jdbc.queryForList(SELECT, admissionId);
    }

    @Transactional
    public Map<String, Object> create(long admissionId, Requests.InpatientResultCreate request) {
        var admission = guard.row("SELECT status FROM admission WHERE id=? FOR UPDATE", admissionId);
        guard.requireRead(admissionId);
        if (java.util.Set.of("PENDING", "CANCELLED").contains(admission.get("status"))) {
            throw new BusinessException("INVALID_ADMISSION_STATUS", "当前住院状态不能登记检查检验结果");
        }
        boolean amendment = request.amendsResultId() != null;
        String amendmentReason = clean(request.amendmentReason());
        if (amendment && amendmentReason == null) {
            throw new BusinessException("AMENDMENT_REASON_REQUIRED", "更正结果必须填写更正原因");
        }
        if (!amendment && amendmentReason != null) {
            throw new BusinessException("AMENDMENT_REASON_UNEXPECTED", "原始结果不能填写更正原因");
        }
        if (amendment) {
            var parent = guard.row("SELECT execution_id, analyte_code, reported_at FROM inpatient_result WHERE id=?",
                    request.amendsResultId());
            if (!request.executionId().equals(((Number) parent.get("execution_id")).longValue())
                    || !request.analyteCode().trim().equals(parent.get("analyte_code"))) {
                throw new BusinessException("AMENDMENT_SOURCE_MISMATCH", "更正结果必须引用同一执行的同一检验项目");
            }
            Instant parentReportedAt = ((Timestamp) parent.get("reported_at")).toInstant();
            if (request.reportedAt().isBefore(parentReportedAt)) {
                throw new BusinessException("AMENDMENT_TIME_INVALID", "更正结果的报告时间不能早于原结果");
            }
        }
        long performerId = ((Number) guard.row("""
                SELECT er.executor_id
                FROM execution_record er
                JOIN order_item oi ON oi.id = er.order_item_id
                JOIN medical_order mo ON mo.id = oi.medical_order_id
                WHERE er.id=? AND mo.admission_id=?
                """, request.executionId(), admissionId).get("executor_id")).longValue();
        long recorderId = SecurityUtils.currentUser().id();
        try {
            guard.jdbc.update("""
                    INSERT INTO inpatient_result(
                        admission_id, order_item_id, execution_id, result_no,
                        analyte_code, analyte_name, result_value, result_text,
                        result_unit, reference_range, abnormal_flag, result_status,
                        amends_result_id, amendment_reason, performed_by,
                        recorded_by, verified_by, reported_at
                    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    admissionId, requestExecutionItem(request.executionId(), admissionId), request.executionId(),
                    cleanRequired(request.requestNo()), cleanRequired(request.analyteCode()),
                    cleanRequired(request.analyteName()), request.resultValue(), clean(request.resultText()),
                    clean(request.resultUnit()), clean(request.referenceRange()), request.abnormalFlag(),
                    amendment ? "AMENDED" : "FINAL", request.amendsResultId(), amendmentReason,
                    performerId, recorderId, recorderId, Timestamp.from(request.reportedAt()));
        } catch (DataAccessException exception) {
            throw BusinessException.fromDatabase(exception, "RESULT_FAILED", "检查检验结果记录失败");
        }
        long resultId = guard.count("SELECT LAST_INSERT_ID()");
        Map<String, Object> after = guard.row(SELECT.replace("WHERE ir.admission_id = ?", "WHERE ir.id = ?"), resultId);
        audit.success(amendment ? "INPATIENT_RESULT_AMEND" : "INPATIENT_RESULT_CREATE",
                "inpatient_result", resultId, null, after);
        return after;
    }

    private long requestExecutionItem(long executionId, long admissionId) {
        return ((Number) guard.row("""
                SELECT er.order_item_id
                FROM execution_record er
                JOIN medical_order mo ON mo.admission_id=?
                JOIN order_item oi ON oi.id=er.order_item_id AND oi.medical_order_id=mo.id
                WHERE er.id=?
                """, admissionId, executionId).get("order_item_id")).longValue();
    }

    private String cleanRequired(String value) {
        String cleaned = clean(value);
        if (cleaned == null) throw new BusinessException("RESULT_FIELD_REQUIRED", "检查检验结果必填项不能为空");
        return cleaned;
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
