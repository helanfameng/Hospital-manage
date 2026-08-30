package com.hospital.inpatient.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface FinancialMapper {
    List<Map<String, Object>> deposits(@Param("admissionId") long admissionId);
    void insertDeposit(Map<String, Object> values);
    Map<String, Object> lockDeposit(@Param("id") long id);
    void insertDepositReversal(Map<String, Object> values);
    List<Map<String, Object>> charges(@Param("admissionId") long admissionId);
    Map<String, Object> settlementPrecheck(@Param("admissionId") long admissionId);
    void callSettlement(Map<String, Object> values);
    Map<String, Object> findSettlement(@Param("admissionId") long admissionId);
    List<Map<String, Object>> settlementDetails(@Param("settlementId") long settlementId);
    List<Map<String, Object>> alerts(@Param("status") String status, @Param("level") String level,
                                     @Param("offset") int offset, @Param("size") int size);
    long countAlerts(@Param("status") String status, @Param("level") String level);
    int resolveAlert(@Param("id") long id, @Param("userId") long userId);
}

