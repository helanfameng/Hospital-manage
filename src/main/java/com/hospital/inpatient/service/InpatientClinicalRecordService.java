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
public class InpatientClinicalRecordService {
    private static final String DIAGNOSIS_SELECT = """
            SELECT d.id, d.admission_id, d.diagnosis_catalog_id, d.diagnosis_no,
                   d.diagnosis_type, d.clinical_status, d.present_on_admission,
                   d.diagnosed_at, d.diagnosis_note, d.resolved_at, d.resolution_note,
                   d.coding_system_snapshot, d.diagnosis_code_snapshot, d.diagnosis_name_snapshot,
                   d.diagnosed_by, d.recorded_by, d.resolved_by, d.created_at,
                   ds.name AS diagnosed_by_name,
                   recorder.display_name AS recorded_by_name,
                   resolver.display_name AS resolved_by_name
            FROM inpatient_diagnosis d
            JOIN staff ds ON ds.id = d.diagnosed_by
            JOIN user_account recorder ON recorder.id = d.recorded_by
            LEFT JOIN user_account resolver ON resolver.id = d.resolved_by
            WHERE d.admission_id = ?
            ORDER BY d.clinical_status = 'ACTIVE' DESC, d.diagnosed_at DESC, d.id DESC
            """;

    private static final String NOTE_SELECT = """
            SELECT n.id, n.admission_id, n.note_no, n.note_type, n.title, n.content,
                   n.note_status, n.amends_note_id, n.amends_note_signed_at, n.amendment_reason,
                   n.authored_by, n.recorded_by, n.signed_by,
                   n.authored_at, n.signed_at, n.created_at,
                   author.name AS authored_by_name,
                   recorder.display_name AS recorded_by_name,
                   signer.display_name AS signed_by_name,
                   EXISTS(SELECT 1 FROM inpatient_clinical_note child
                          WHERE child.amends_note_id = n.id) AS is_superseded
            FROM inpatient_clinical_note n
            JOIN staff author ON author.id = n.authored_by
            JOIN user_account recorder ON recorder.id = n.recorded_by
            JOIN user_account signer ON signer.id = n.signed_by
            WHERE n.admission_id = ?
            ORDER BY n.signed_at DESC, n.id DESC
            """;

    private final AccountGuard guard;
    private final AuditService audit;

    public InpatientClinicalRecordService(AccountGuard guard, AuditService audit) {
        this.guard = guard;
        this.audit = audit;
    }

    public List<Map<String, Object>> listCatalog() {
        return guard.jdbc.queryForList("""
                SELECT id, coding_system, code, name
                FROM diagnosis_catalog
                WHERE active = TRUE
                ORDER BY coding_system, code
                """);
    }

    public List<Map<String, Object>> listDiagnoses(long admissionId) {
        guard.requireRead(admissionId);
        return guard.jdbc.queryForList(DIAGNOSIS_SELECT, admissionId);
    }

    @Transactional
    public Map<String, Object> createDiagnosis(long admissionId, Requests.InpatientDiagnosisCreate request) {
        Map<String, Object> admission = lockClinicalAdmission(admissionId);
        String type = cleanRequired(request.diagnosisType(), "DIAGNOSIS_TYPE_REQUIRED", "诊断类型不能为空");
        Map<String, Object> catalog = guard.row("""
                SELECT id, coding_system, code, name
                FROM diagnosis_catalog
                WHERE id = ? AND active = TRUE
                """, request.diagnosisCatalogId());
        long diagnosedBy = request.diagnosedBy() == null
                ? requiredCurrentStaff("诊断医生不能为空")
                : request.diagnosedBy();
        Long scopedDoctor = SecurityUtils.doctorScope();
        if (scopedDoctor != null && scopedDoctor.longValue() != diagnosedBy) {
            throw new BusinessException("DIAGNOSED_BY_SCOPE", "医生只能以本人身份登记住院诊断");
        }
        long recorderId = SecurityUtils.currentUser().id();
        try {
            guard.jdbc.update("""
                    INSERT INTO inpatient_diagnosis(
                        admission_id, diagnosis_catalog_id, diagnosis_no, diagnosis_type,
                        present_on_admission, diagnosed_at, diagnosis_note, diagnosis_code_snapshot,
                        diagnosis_name_snapshot, coding_system_snapshot, diagnosed_by, recorded_by
                    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    admissionId, request.diagnosisCatalogId(), cleanRequired(request.requestNo(), "DIAGNOSIS_NO_REQUIRED", "诊断请求号不能为空"),
                    type, request.presentOnAdmission(), Timestamp.from(request.diagnosedAt()), clean(request.note()),
                    catalog.get("code"), catalog.get("name"), catalog.get("coding_system"), diagnosedBy, recorderId);
        } catch (DataAccessException exception) {
            throw BusinessException.fromDatabase(exception, "DIAGNOSIS_FAILED", "住院诊断记录失败");
        }
        long diagnosisId = guard.count("SELECT LAST_INSERT_ID()");
        Map<String, Object> after = diagnosisById(diagnosisId, admissionId);
        audit.success("INPATIENT_DIAGNOSIS_CREATE", "inpatient_diagnosis", diagnosisId, null, after);
        return after;
    }

    @Transactional
    public Map<String, Object> resolveDiagnosis(long admissionId, long diagnosisId,
                                                 Requests.InpatientDiagnosisResolve request) {
        lockClinicalAdmission(admissionId);
        Map<String, Object> before = guard.row("""
                SELECT id, clinical_status, diagnosed_at, resolved_at, resolution_note, resolved_by
                FROM inpatient_diagnosis
                WHERE id = ? AND admission_id = ?
                FOR UPDATE
                """, diagnosisId, admissionId);
        if (!"ACTIVE".equals(before.get("clinical_status"))) {
            throw new BusinessException("DIAGNOSIS_ALREADY_CLOSED", "该诊断已经解决或排除");
        }
        Instant resolvedAt = request.resolvedAt();
        Instant diagnosedAt = ((Timestamp) before.get("diagnosed_at")).toInstant();
        if (resolvedAt.isBefore(diagnosedAt)) {
            throw new BusinessException("DIAGNOSIS_TIME_INVALID", "解决时间不能早于诊断时间");
        }
        long resolverId = SecurityUtils.currentUser().id();
        try {
            guard.jdbc.update("""
                    UPDATE inpatient_diagnosis
                    SET clinical_status=?, resolved_at=?, resolution_note=?, resolved_by=?
                    WHERE id=? AND admission_id=? AND clinical_status='ACTIVE'
                    """,
                    request.status(), Timestamp.from(resolvedAt), clean(request.note()), resolverId,
                    diagnosisId, admissionId);
        } catch (DataAccessException exception) {
            throw BusinessException.fromDatabase(exception, "DIAGNOSIS_RESOLVE_FAILED", "住院诊断状态更新失败");
        }
        Map<String, Object> after = diagnosisById(diagnosisId, admissionId);
        audit.success("INPATIENT_DIAGNOSIS_RESOLVE", "inpatient_diagnosis", diagnosisId, before, after);
        return after;
    }

    public List<Map<String, Object>> listNotes(long admissionId) {
        guard.requireRead(admissionId);
        return guard.jdbc.queryForList(NOTE_SELECT, admissionId);
    }

    @Transactional
    public Map<String, Object> createNote(long admissionId, Requests.InpatientClinicalNoteCreate request) {
        lockClinicalAdmission(admissionId);
        String type = cleanRequired(request.noteType(), "NOTE_TYPE_REQUIRED", "记录类型不能为空");
        long recorderId = SecurityUtils.currentUser().id();
        Long scopedDoctor = SecurityUtils.doctorScope();
        if (scopedDoctor != null && scopedDoctor.longValue() != request.authoredBy()) {
            throw new BusinessException("NOTE_AUTHOR_SCOPE", "医生只能以本人身份签署住院临床记录");
        }
        if (request.amendsNoteId() != null) {
            Map<String, Object> parent = guard.row("""
                    SELECT signed_at, note_type
                    FROM inpatient_clinical_note
                    WHERE id=? AND admission_id=?
                    """, request.amendsNoteId(), admissionId);
            if (!type.equals(parent.get("note_type"))) {
                throw new BusinessException("NOTE_AMENDMENT_TYPE_MISMATCH", "更正记录必须保持原临床记录类型");
            }
            if (request.signedAt().isBefore(((Timestamp) parent.get("signed_at")).toInstant())) {
                throw new BusinessException("NOTE_AMENDMENT_TIME_INVALID", "更正记录签署时间不能早于原记录");
            }
        }
        Timestamp parentSignedAt = null;
        if (request.amendsNoteId() != null) {
            parentSignedAt = (Timestamp) guard.row("""
                    SELECT signed_at FROM inpatient_clinical_note
                    WHERE id=? AND admission_id=?
                    """, request.amendsNoteId(), admissionId).get("signed_at");
        }
        try {
            guard.jdbc.update("""
                    INSERT INTO inpatient_clinical_note(
                        admission_id, note_no, note_type, title, content, note_status,
                        amends_note_id, amends_note_signed_at, amendment_reason, authored_by, recorded_by, signed_by,
                        authored_at, signed_at
                    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    admissionId,
                    cleanRequired(request.requestNo(), "NOTE_NO_REQUIRED", "临床记录请求号不能为空"),
                    type,
                    cleanRequired(request.title(), "NOTE_TITLE_REQUIRED", "记录标题不能为空"),
                    cleanRequired(request.content(), "NOTE_CONTENT_REQUIRED", "记录内容不能为空"),
                    request.amendsNoteId() == null ? "FINAL" : "AMENDED",
                    request.amendsNoteId(), parentSignedAt, clean(request.amendmentReason()), request.authoredBy(), recorderId, recorderId,
                    Timestamp.from(request.authoredAt()), Timestamp.from(request.signedAt()));
        } catch (DataAccessException exception) {
            throw BusinessException.fromDatabase(exception, "CLINICAL_NOTE_FAILED", "住院临床记录失败");
        }
        long noteId = guard.count("SELECT LAST_INSERT_ID()");
        Map<String, Object> after = noteById(noteId, admissionId);
        audit.success(request.amendsNoteId() == null ? "INPATIENT_NOTE_CREATE" : "INPATIENT_NOTE_AMEND",
                "inpatient_clinical_note", noteId, null, after);
        return after;
    }

    private Map<String, Object> lockClinicalAdmission(long admissionId) {
        Map<String, Object> admission = guard.row("""
                SELECT status, admission_time, department_id
                FROM admission WHERE id=? FOR UPDATE
                """, admissionId);
        guard.requireRead(admissionId);
        if (java.util.Set.of("PENDING", "CANCELLED").contains(admission.get("status"))) {
            throw new BusinessException("INVALID_ADMISSION_STATUS", "当前住院状态不能登记临床记录");
        }
        return admission;
    }

    private Map<String, Object> diagnosisById(long diagnosisId, long admissionId) {
        return guard.jdbc.queryForList(DIAGNOSIS_SELECT.replace("WHERE d.admission_id = ?", "WHERE d.id = ? AND d.admission_id = ?"),
                        diagnosisId, admissionId).stream().findFirst()
                .orElseThrow(() -> new BusinessException("DIAGNOSIS_NOT_FOUND", "住院诊断不存在"));
    }

    private Map<String, Object> noteById(long noteId, long admissionId) {
        return guard.jdbc.queryForList(NOTE_SELECT.replace("WHERE n.admission_id = ?", "WHERE n.id = ? AND n.admission_id = ?"),
                        noteId, admissionId).stream().findFirst()
                .orElseThrow(() -> new BusinessException("NOTE_NOT_FOUND", "住院临床记录不存在"));
    }

    private long requiredCurrentStaff(String message) {
        Long staffId = SecurityUtils.currentUser().staffId();
        if (staffId == null) throw new BusinessException("STAFF_REQUIRED", message);
        return staffId;
    }

    private String cleanRequired(String value, String code, String message) {
        String cleaned = clean(value);
        if (cleaned == null) throw new BusinessException(code, message);
        return cleaned;
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
