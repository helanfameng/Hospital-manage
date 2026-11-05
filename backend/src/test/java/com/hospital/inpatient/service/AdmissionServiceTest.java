package com.hospital.inpatient.service;

import com.hospital.inpatient.dto.Requests;
import com.hospital.inpatient.mapper.HospitalMapper;
import com.hospital.inpatient.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdmissionServiceTest {
    @Mock HospitalMapper mapper;
    @Mock AuditService auditService;
    AdmissionService service;

    @BeforeEach
    void setUp() {
        service = new AdmissionService(mapper, auditService, org.mockito.Mockito.mock(AccountGuard.class));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(9, "admission", List.of("ADMISSION")), null, List.of()));
    }

    @Test
    void admissionProcedureResultIsReturned() {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked") Map<String, Object> parameters = invocation.getArgument(0);
            assertThat(parameters).containsEntry("paymentMethod", "CARD");
            parameters.put("admissionId", 88L);
            return null;
        }).when(mapper).callAdmit(any());
        when(mapper.findAdmission(88)).thenReturn(Map.of("id", 88L, "status", "IN_HOSPITAL"));
        when(mapper.bedHistory(88)).thenReturn(List.of());

        var result = service.admit(new Requests.AdmissionCreate(1L, 2L, 3L, 4L, new BigDecimal("3000"), "CARD"));

        assertThat(result).containsEntry("id", 88L).containsEntry("status", "IN_HOSPITAL");
    }
}
