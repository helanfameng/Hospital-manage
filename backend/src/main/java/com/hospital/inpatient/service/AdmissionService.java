package com.hospital.inpatient.service;

import com.hospital.inpatient.common.PageResult;
import com.hospital.inpatient.dto.Requests;
import com.hospital.inpatient.exception.BusinessException;
import com.hospital.inpatient.mapper.HospitalMapper;
import com.hospital.inpatient.security.SecurityUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@Service
public class AdmissionService {
    private final HospitalMapper mapper;
    private final AuditService auditService;
    private final AccountGuard guard;

    public AdmissionService(HospitalMapper mapper, AuditService auditService, AccountGuard guard) {
        this.mapper = mapper;
        this.auditService = auditService;
        this.guard = guard;
    }

    public PageResult<Map<String, Object>> list(String status, Long departmentId, String keyword,
                                                Integer longStayDays, Boolean settlementBlocked, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 1);
        Integer safeLongStayDays = longStayDays == null ? null : Math.min(Math.max(longStayDays, 1), 3650);
        return new PageResult<>(
                mapper.listAdmissions(status, departmentId, keyword, safeLongStayDays, Boolean.TRUE.equals(settlementBlocked), (safePage - 1) * safeSize, safeSize),
                mapper.countAdmissions(status, departmentId, keyword, safeLongStayDays, Boolean.TRUE.equals(settlementBlocked)),
                safePage,
                safeSize
        );
    }

    public Map<String, Object> detail(long id) {
        guard.requireRead(id);
        var detail = mapper.findAdmission(id);
        if (detail == null) {
            throw new BusinessException("ADMISSION_NOT_FOUND", "住院记录不存在", HttpStatus.NOT_FOUND);
        }
        var result = new HashMap<>(detail);
        result.put("bedHistory", mapper.bedHistory(id));
        return result;
    }

    @Transactional
    public Map<String, Object> admit(Requests.AdmissionCreate request) {
        var parameters = new HashMap<String, Object>();
        parameters.put("patientId", request.patientId());
        parameters.put("departmentId", request.departmentId());
        parameters.put("doctorId", request.doctorId());
        parameters.put("bedId", request.bedId());
        parameters.put("initialDeposit", request.initialDeposit());
        parameters.put("paymentMethod", request.paymentMethod());
        parameters.put("operatorId", SecurityUtils.currentUser().id());
        try {
            mapper.callAdmit(parameters);
        } catch (DataAccessException exception) {
            throw translate(exception, "ADMISSION_FAILED", "入院登记失败");
        }
        long admissionId = ((Number) parameters.get("admissionId")).longValue();
        guard.refreshAlerts(admissionId);
        var result = detail(admissionId);
        auditService.success("ADMISSION_CREATE", "admission", admissionId, null, result);
        return result;
    }

    @Transactional
    public Map<String, Object> transfer(long admissionId, Requests.TransferCreate request) {
        guard.lockInHospital(admissionId);
        var before = detail(admissionId);
        var parameters = new HashMap<String, Object>();
        parameters.put("admissionId", admissionId);
        parameters.put("targetBedId", request.targetBedId());
        parameters.put("targetDoctorId", request.targetDoctorId());
        parameters.put("reason", request.reason());
        parameters.put("operatorId", SecurityUtils.currentUser().id());
        try {
            mapper.callTransfer(parameters);
        } catch (DataAccessException exception) {
            throw translate(exception, "TRANSFER_FAILED", "转床失败");
        }
        var after = detail(admissionId);
        auditService.success("BED_TRANSFER", "admission", admissionId, before, after);
        return after;
    }

    @Transactional
    public void requestDischarge(long admissionId) {
        guard.lockInHospital(admissionId);
        if (guard.count("SELECT COUNT(*) FROM medical_order WHERE admission_id=? AND status IN ('DRAFT','SUBMITTED','IN_PROGRESS')", admissionId)>0) {
            throw new BusinessException("PENDING_ORDERS", "请先完成、停止或撤销未完成医嘱，再申请出院");
        }
        if (mapper.markPendingDischarge(admissionId) != 1) {
            throw new BusinessException("INVALID_ADMISSION_STATUS", "仅住院中的患者可以申请出院");
        }
        auditService.success("DISCHARGE_REQUEST", "admission", admissionId, null, Map.of("status", "PENDING_DISCHARGE"));
    }

    @Transactional
    public void withdrawDischarge(long id) {
        var before = guard.lockOpen(id);
        if (!"PENDING_DISCHARGE".equals(before.get("status"))) throw new BusinessException("INVALID_STATUS", "仅待出院记录可以撤回申请");
        guard.jdbc.update("UPDATE admission SET status='IN_HOSPITAL',version=version+1 WHERE id=?",id);
        auditService.success("DISCHARGE_WITHDRAW", "admission", id, before, Map.of("status","IN_HOSPITAL"));
    }

    private BusinessException translate(DataAccessException exception, String code, String fallback) {
        return BusinessException.fromDatabase(exception,code,fallback);
    }
}
