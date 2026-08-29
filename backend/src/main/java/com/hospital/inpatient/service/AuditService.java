package com.hospital.inpatient.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.inpatient.common.TraceIds;
import com.hospital.inpatient.mapper.AuditMapper;
import com.hospital.inpatient.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.HashMap;

@Service
public class AuditService {
    private final AuditMapper auditMapper;
    private final ObjectMapper objectMapper;
    private final HttpServletRequest request;

    public AuditService(AuditMapper auditMapper, ObjectMapper objectMapper, HttpServletRequest request) {
        this.auditMapper = auditMapper;
        this.objectMapper = objectMapper;
        this.request = request;
    }

    public void success(String action, String objectType, Object objectId, Object before, Object after) {
        record(action,objectType,objectId,before,after,"SUCCESS");
    }

    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void failure(String code) {
        record("REQUEST_FAILED", "request", null, null, java.util.Map.of("path",request.getRequestURI(),"code",code), "FAILURE");
    }

    private void record(String action, String objectType, Object objectId, Object before, Object after, String result) {
        var user = SecurityUtils.currentUser();
        var values = new HashMap<String, Object>();
        values.put("userId", user.id() == 0 ? null : user.id());
        values.put("username", user.username());
        values.put("roleCodes", String.join(",", user.roles()));
        values.put("action", action);
        values.put("objectType", objectType);
        values.put("objectId", objectId == null ? null : objectId.toString());
        values.put("result", result);
        values.put("beforeValue", json(before));
        values.put("afterValue", json(after));
        values.put("ipAddress", request.getRemoteAddr());
        values.put("traceId", TraceIds.current());
        auditMapper.insert(values);
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return "{\"serializationError\":true}";
        }
    }
}
