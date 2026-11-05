package com.hospital.inpatient.service;

import com.hospital.inpatient.dto.Requests;
import com.hospital.inpatient.exception.BusinessException;
import com.hospital.inpatient.security.SecurityUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

@Service
public class NursingObservationService {
    private static final String SELECT = """
            SELECT ob.id, ob.admission_id, ob.request_no, ob.observed_at, ob.recorded_by,
                   ob.temperature_c, ob.pulse_rate, ob.respiratory_rate,
                   ob.systolic_pressure, ob.diastolic_pressure, ob.oxygen_saturation,
                   ob.consciousness, ob.note, ob.created_at,
                   ua.display_name AS recorded_by_name, s.name AS recorded_staff_name
            FROM nursing_observation ob
            JOIN user_account ua ON ua.id = ob.recorded_by
            JOIN staff s ON s.id = ua.staff_id
            WHERE ob.admission_id = ?
            ORDER BY ob.observed_at DESC, ob.id DESC
            """;

    private final AccountGuard guard;
    private final AuditService audit;

    public NursingObservationService(AccountGuard guard, AuditService audit) {
        this.guard = guard;
        this.audit = audit;
    }

    public List<Map<String, Object>> list(long admissionId) {
        guard.requireRead(admissionId);
        return guard.jdbc.queryForList(SELECT, admissionId);
    }

    @Transactional
    public Map<String, Object> create(long admissionId, Requests.NursingObservationCreate request) {
        guard.lockOpen(admissionId);
        if (!request.hasMeasurement()) {
            throw new BusinessException("OBSERVATION_EMPTY", "至少填写一项生命体征或意识状态");
        }
        if ((request.systolicPressure() == null) != (request.diastolicPressure() == null)) {
            throw new BusinessException("OBSERVATION_PRESSURE_INCOMPLETE", "收缩压和舒张压必须同时填写");
        }
        if (request.systolicPressure() != null && request.diastolicPressure() != null
                && request.systolicPressure() <= request.diastolicPressure()) {
            throw new BusinessException("OBSERVATION_PRESSURE_INVALID", "收缩压必须高于舒张压");
        }
        long recorderId = SecurityUtils.currentUser().id();
        try {
            guard.jdbc.update("""
                    INSERT INTO nursing_observation(
                        admission_id, request_no, observed_at, recorded_by,
                        temperature_c, pulse_rate, respiratory_rate,
                        systolic_pressure, diastolic_pressure, oxygen_saturation,
                        consciousness, note
                    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    admissionId, request.requestNo(), Timestamp.from(request.observedAt()), recorderId,
                    request.temperatureC(), request.pulseRate(), request.respiratoryRate(),
                    request.systolicPressure(), request.diastolicPressure(), request.oxygenSaturation(),
                    emptyToNull(request.consciousness()), emptyToNull(request.note()));
        } catch (DataAccessException exception) {
            throw BusinessException.fromDatabase(exception, "OBSERVATION_FAILED", "护理观察记录失败");
        }
        long observationId = guard.count("SELECT LAST_INSERT_ID()");
        Map<String, Object> after = guard.row(SELECT.replace("WHERE ob.admission_id = ?", "WHERE ob.id = ?"), observationId);
        audit.success("NURSING_OBSERVATION_CREATE", "nursing_observation", observationId, null, after);
        return after;
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
