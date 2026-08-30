package com.hospital.inpatient.service;

import com.hospital.inpatient.exception.BusinessException;
import com.hospital.inpatient.security.SecurityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.Map;

/** All financial/clinical mutations acquire the admission lock before any child lock. */
@Service
public class AccountGuard {
    public final JdbcTemplate jdbc;
    private final BigDecimal threshold;
    public AccountGuard(JdbcTemplate jdbc, @Value("${hospital.account.alert-threshold}") BigDecimal threshold) {
        this.jdbc = jdbc;
        this.threshold = threshold;
    }
    public Map<String,Object> row(String sql, Object... args) {
        var rows = jdbc.queryForList(sql, args);
        if (rows.isEmpty()) throw new BusinessException("NOT_FOUND", "记录不存在", HttpStatus.NOT_FOUND);
        return rows.get(0);
    }
    public long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    public void requireRead(long admissionId) {
        var a = row("SELECT attending_doctor_id,department_id FROM admission WHERE id=?", admissionId);
        Long doctor = SecurityUtils.doctorScope();
        Long department = SecurityUtils.departmentScope();
        if ((doctor != null && doctor != ((Number)a.get("attending_doctor_id")).longValue()) ||
            (department != null && department != ((Number)a.get("department_id")).longValue())) {
            throw new BusinessException("FORBIDDEN", "当前账号无权访问该患者的住院记录", HttpStatus.FORBIDDEN);
        }
    }
    public Map<String,Object> lockOpen(long admissionId) {
        var a = row("SELECT * FROM admission WHERE id=? FOR UPDATE", admissionId);
        requireRead(admissionId);
        if (!java.util.List.of("IN_HOSPITAL", "PENDING_DISCHARGE").contains(a.get("status")) ||
            count("SELECT COUNT(*) FROM settlement WHERE admission_id=?", admissionId)>0) {
            throw new BusinessException("ACCOUNT_CLOSED", "已结算或无效住院不能再变更业务记录");
        }
        return a;
    }
    public Map<String,Object> lockInHospital(long admissionId) {
        var a = lockOpen(admissionId);
        if (!"IN_HOSPITAL".equals(a.get("status"))) throw new BusinessException("DISCHARGE_PENDING", "请先撤回出院申请再进行治疗或转床");
        return a;
    }
    public long orderAdmission(long id) {
        return ((Number)row("SELECT admission_id FROM medical_order WHERE id=?", id).get("admission_id")).longValue();
    }
    public long executionAdmission(long id) {
        return ((Number)row("SELECT mo.admission_id FROM execution_record er JOIN order_item oi ON oi.id=er.order_item_id JOIN medical_order mo ON mo.id=oi.medical_order_id WHERE er.id=?", id).get("admission_id")).longValue();
    }
    public BigDecimal balance(long id) {
        return jdbc.queryForObject("SELECT available_balance FROM v_admission_deposit_balance WHERE admission_id=?", BigDecimal.class,id);
    }
    public void refreshAlerts(long id) { jdbc.update("CALL sp_refresh_account_alert(?,?,?)",id,threshold,SecurityUtils.currentUser().id()); }
}
