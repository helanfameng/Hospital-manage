package com.hospital.inpatient.service;

import com.hospital.inpatient.common.PageResult;
import com.hospital.inpatient.dto.Requests;
import com.hospital.inpatient.exception.BusinessException;
import com.hospital.inpatient.mapper.FinancialMapper;
import com.hospital.inpatient.security.SecurityUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class FinancialService {
    private final FinancialMapper mapper;
    private final AuditService auditService;
    private final AccountGuard guard;

    public FinancialService(FinancialMapper mapper, AuditService auditService, AccountGuard guard) {
        this.mapper = mapper;
        this.auditService = auditService;
        this.guard = guard;
    }

    @Transactional
    public Map<String, Object> addDeposit(Requests.DepositCreate request) {
        if (request.amount()==null || request.amount().signum() <= 0 || request.amount().scale()>2) {
            throw new BusinessException("INVALID_AMOUNT", "押金金额不能为零或负数，且最多两位小数");
        }
        guard.lockOpen(request.admissionId());
        BigDecimal amount = request.amount();
        if ("INITIAL".equals(request.type()) && guard.count("SELECT COUNT(*) FROM deposit_transaction WHERE admission_id=? AND transaction_type='INITIAL'",request.admissionId())>0) {
            throw new BusinessException("INITIAL_EXISTS", "已有初始押金，请使用补交押金");
        }
        if ("REFUND".equals(request.type())) {
            if (amount.compareTo(guard.balance(request.admissionId()))>0) throw new BusinessException("REFUND_EXCEEDS_BALANCE", "退款不能超过扣除费用后的可用余额");
            amount = amount.negate();
        }
        var values = new HashMap<String, Object>();
        values.put("admissionId", request.admissionId());
        values.put("transactionNo", businessNo("D"));
        values.put("requestNo", request.requestNo());
        values.put("type", request.type());
        values.put("amount", amount);
        values.put("paymentMethod", request.paymentMethod());
        values.put("note", request.note());
        values.put("operatorId", SecurityUtils.currentUser().id());
        mapper.insertDeposit(values);
        guard.refreshAlerts(request.admissionId());
        auditService.success("DEPOSIT_CREATE", "deposit_transaction", values.get("id"), null, values);
        return values;
    }

    @Transactional
    public Map<String, Object> reverseDeposit(long id, String reason) {
        long admissionId = ((Number)guard.row("SELECT admission_id FROM deposit_transaction WHERE id=?", id).get("admission_id")).longValue();
        guard.lockOpen(admissionId);
        var original = mapper.lockDeposit(id);
        if (original == null) {
            throw new BusinessException("DEPOSIT_NOT_FOUND", "押金流水不存在", HttpStatus.NOT_FOUND);
        }
        if ("REVERSAL".equals(original.get("transaction_type")) || guard.count("SELECT COUNT(*) FROM deposit_transaction WHERE original_transaction_id=?",id)>0) {
            throw new BusinessException("ALREADY_REVERSED", "该流水已冲正，或本身为冲正流水，不能再次冲正");
        }
        BigDecimal deposit = guard.jdbc.queryForObject("SELECT deposit_balance FROM v_admission_deposit_balance WHERE admission_id=?", BigDecimal.class,admissionId);
        if (deposit.subtract((BigDecimal)original.get("amount")).signum()<0) throw new BusinessException("NEGATIVE_DEPOSIT", "冲正会使押金总额为负，请先核对已发生的退款");
        var values = new HashMap<String, Object>();
        values.put("admissionId", original.get("admission_id"));
        values.put("transactionNo", businessNo("DR"));
        values.put("amount", ((BigDecimal) original.get("amount")).negate());
        values.put("originalId", id);
        values.put("paymentMethod", original.get("payment_method"));
        values.put("reason", reason);
        values.put("operatorId", SecurityUtils.currentUser().id());
        mapper.insertDepositReversal(values);
        guard.refreshAlerts(admissionId);
        auditService.success("DEPOSIT_REVERSE", "deposit_transaction", id, original, values);
        return values;
    }

    public Map<String, Object> precheck(long admissionId) {
        guard.requireRead(admissionId);
        var value = mapper.settlementPrecheck(admissionId);
        if (value == null) {
            throw new BusinessException("ADMISSION_NOT_FOUND", "住院记录不存在", HttpStatus.NOT_FOUND);
        }
        return value;
    }

    @Transactional
    public Map<String, Object> settle(long admissionId) {
        guard.lockOpen(admissionId);
        var before = precheck(admissionId);
        var values = new HashMap<String, Object>();
        values.put("admissionId", admissionId);
        values.put("operatorId", SecurityUtils.currentUser().id());
        try {
            mapper.callSettlement(values);
        } catch (DataAccessException exception) {
            throw BusinessException.fromDatabase(exception,"SETTLEMENT_FAILED","出院结算失败");
        }
        var result = settlement(admissionId);
        auditService.success("DISCHARGE_SETTLEMENT", "settlement", values.get("settlementId"), before, result);
        return result;
    }

    public Map<String, Object> settlement(long admissionId) {
        guard.requireRead(admissionId);
        var row = mapper.findSettlement(admissionId);
        if (row == null) {
            throw new BusinessException("SETTLEMENT_NOT_FOUND", "结算单不存在", HttpStatus.NOT_FOUND);
        }
        var result = new HashMap<>(row);
        result.put("details", mapper.settlementDetails(((Number) row.get("id")).longValue()));
        return result;
    }

    public PageResult<Map<String, Object>> alerts(String status, String level, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return new PageResult<>(mapper.alerts(status, level, (safePage - 1) * safeSize, safeSize),
                mapper.countAlerts(status, level), safePage, safeSize);
    }

    @Transactional
    public void resolveAlert(long id) {
        long admissionId = ((Number)guard.row("SELECT admission_id FROM account_alert WHERE id=?",id).get("admission_id")).longValue();
        guard.lockOpen(admissionId);
        var alert = guard.row("SELECT threshold_snapshot FROM account_alert WHERE id=?",id);
        if (guard.balance(admissionId).compareTo((BigDecimal)alert.get("threshold_snapshot"))<0) throw new BusinessException("BALANCE_STILL_LOW", "余额仍低于提醒阈值，请先补交押金或核对费用");
        if (mapper.resolveAlert(id, SecurityUtils.currentUser().id()) != 1) {
            throw new BusinessException("ALERT_STATUS_CONFLICT", "提醒不存在或已经处理");
        }
        auditService.success("ALERT_RESOLVE", "account_alert", id, null, Map.of("status", "RESOLVED"));
    }

    private String businessNo(String prefix) {
        return prefix + Instant.now().toEpochMilli() + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    }
}
