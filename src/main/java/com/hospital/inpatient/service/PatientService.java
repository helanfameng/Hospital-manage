package com.hospital.inpatient.service;

import com.hospital.inpatient.common.PageResult;
import com.hospital.inpatient.dto.Requests;
import com.hospital.inpatient.exception.BusinessException;
import com.hospital.inpatient.mapper.HospitalMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

@Service
public class PatientService {
    private final HospitalMapper mapper;
    private final AuditService auditService;
    private final AccountGuard guard;

    public PatientService(HospitalMapper mapper, AuditService auditService, AccountGuard guard) {
        this.mapper = mapper;
        this.auditService = auditService;
        this.guard = guard;
    }

    public PageResult<Map<String, Object>> list(String keyword, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 1);
        return new PageResult<>(
                mapper.listPatients(keyword, (safePage - 1) * safeSize, safeSize),
                mapper.countPatients(keyword),
                safePage,
                safeSize
        );
    }

    public Map<String, Object> detail(long id) {
        var patient = mapper.findPatient(id);
        if (patient == null) {
            throw new BusinessException("PATIENT_NOT_FOUND", "患者不存在", HttpStatus.NOT_FOUND);
        }
        var result = new HashMap<>(patient);
        result.put("admissions", mapper.patientAdmissions(id));
        return result;
    }

    @Transactional
    public Map<String, Object> create(Requests.PatientCreate request) {
        var values = new HashMap<String, Object>();
        values.put("patientNo", "P"
                + LocalDateTime.now(ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"))
                + java.util.UUID.randomUUID().toString().substring(0, 4).toUpperCase());
        values.put("name", request.name());
        values.put("gender", request.gender());
        values.put("birthDate", request.birthDate());
        values.put("identityNumber", request.identityNumber());
        values.put("phone", request.phone());
        values.put("address", request.address());
        values.put("emergencyContact", request.emergencyContact());
        values.put("emergencyPhone", request.emergencyPhone());
        mapper.insertPatient(values);
        auditService.success("PATIENT_CREATE", "patient", values.get("id"), null, values);
        return mapper.findPatient(((Number) values.get("id")).longValue());
    }

    @Transactional
    public Map<String, Object> update(long id, Requests.PatientUpdate request) {
        guard.row("SELECT id FROM patient WHERE id=? FOR UPDATE",id);
        if (!request.active() && guard.count("SELECT COUNT(*) FROM admission WHERE patient_id=? AND status IN ('PENDING','IN_HOSPITAL','PENDING_DISCHARGE')",id)>0) {
            throw new BusinessException("ACTIVE_ADMISSION", "患者仍有有效住院记录，不能停用");
        }
        var before = mapper.findPatient(id);
        if (before == null) {
            throw new BusinessException("PATIENT_NOT_FOUND", "患者不存在", HttpStatus.NOT_FOUND);
        }
        var values = new HashMap<String, Object>();
        values.put("id", id);
        values.put("name", request.name());
        values.put("gender", request.gender());
        values.put("birthDate", request.birthDate());
        values.put("phone", request.phone());
        values.put("address", request.address());
        values.put("emergencyContact", request.emergencyContact());
        values.put("emergencyPhone", request.emergencyPhone());
        values.put("active", request.active());
        mapper.updatePatient(values);
        var after = mapper.findPatient(id);
        auditService.success("PATIENT_UPDATE", "patient", id, before, after);
        return after;
    }
}
