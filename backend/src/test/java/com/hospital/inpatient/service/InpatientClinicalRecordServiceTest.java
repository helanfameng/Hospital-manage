package com.hospital.inpatient.service;

import com.hospital.inpatient.dto.Requests;
import com.hospital.inpatient.security.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InpatientClinicalRecordServiceTest {
    @Mock JdbcTemplate jdbc;
    @Mock AuditService audit;

    private AccountGuard guard;
    private InpatientClinicalRecordService service;

    @BeforeEach
    void setUp() {
        guard = org.mockito.Mockito.spy(new AccountGuard(jdbc, BigDecimal.ZERO));
        service = new InpatientClinicalRecordService(guard, audit);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new CurrentUser(7L, "admin", List.of("ADMIN"), 8L, 2L),
                        null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void diagnosisInsertBindsEveryColumn() {
        Instant now = Instant.now().minusSeconds(1);
        stubAdmission(now);
        doReturn(Map.of("id", 4L, "coding_system", "ICD10", "code", "J18.9", "name", "肺炎，未特指"))
                .when(guard).row(contains("FROM diagnosis_catalog"), eq(4L));
        doReturn(99L).when(guard).count("SELECT LAST_INSERT_ID()");
        when(jdbc.queryForList(contains("WHERE d.id = ?"), eq(99L), eq(1L)))
                .thenReturn(List.of(Map.of("id", 99L)));

        service.createDiagnosis(1L, new Requests.InpatientDiagnosisCreate(
                "DX-1", 4L, "PRIMARY", true, now, 8L, "入院诊断"));

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        ArgumentCaptor<String> statement = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(statement.capture(), arguments.capture());
        assertThat(statement.getValue()).contains("INSERT INTO inpatient_diagnosis");
        assertThat(arguments.getValue()).hasSize(countPlaceholders(statement.getValue()));
        assertThat(arguments.getValue()).hasSize(12);
    }

    @Test
    void clinicalNoteInsertBindsEveryColumn() {
        Instant signedAt = Instant.now().minusSeconds(1);
        stubAdmission(signedAt.minusSeconds(10));
        doReturn(100L).when(guard).count("SELECT LAST_INSERT_ID()");
        when(jdbc.queryForList(contains("WHERE n.id = ?"), eq(100L), eq(1L)))
                .thenReturn(List.of(Map.of("id", 100L)));

        service.createNote(1L, new Requests.InpatientClinicalNoteCreate(
                "NOTE-1", "PROGRESS", "病程记录", "继续观察。", 8L,
                signedAt.minusSeconds(5), signedAt, null, null));

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        ArgumentCaptor<String> statement = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(statement.capture(), arguments.capture());
        assertThat(statement.getValue()).contains("INSERT INTO inpatient_clinical_note");
        assertThat(arguments.getValue()).hasSize(countPlaceholders(statement.getValue()));
        assertThat(arguments.getValue()).hasSize(14);
    }

    private void stubAdmission(Instant admissionTime) {
        doReturn(Map.of(
                "status", "IN_HOSPITAL",
                "admission_time", Timestamp.from(admissionTime.minusSeconds(60)),
                "department_id", 2L))
                .when(guard).row(contains("SELECT status, admission_time, department_id"), eq(1L));
        doNothing().when(guard).requireRead(1L);
    }

    private int countPlaceholders(String sql) {
        return (int) sql.chars().filter(character -> character == '?').count();
    }
}
