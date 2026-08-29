package com.hospital.inpatient.service;

import com.hospital.inpatient.dto.Requests;
import com.hospital.inpatient.exception.BusinessException;
import com.hospital.inpatient.mapper.FinancialMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class FinancialServiceTest {
    @Test
    void zeroDepositIsRejectedBeforeDatabaseMutation() {
        FinancialService service = new FinancialService(mock(FinancialMapper.class), mock(AuditService.class), mock(AccountGuard.class));
        var request = new Requests.DepositCreate(1L, "SUPPLEMENT", BigDecimal.ZERO, "CASH", null, "zero-test");
        assertThatThrownBy(() -> service.addDeposit(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能为零");
    }
}
