package com.hospital.inpatient.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface ClinicalMapper {
    Map<String, Object> findOrder(@Param("id") long id);
    List<Map<String, Object>> findOrderItems(@Param("orderId") long orderId);
    List<Map<String, Object>> listOrders(@Param("admissionId") Long admissionId, @Param("status") String status,
                                         @Param("patientName") String patientName, @Param("orderNo") String orderNo,
                                         @Param("offset") int offset, @Param("size") int size);
    long countOrders(@Param("admissionId") Long admissionId, @Param("status") String status,
                     @Param("patientName") String patientName, @Param("orderNo") String orderNo);
}
