package com.hospital.inpatient.mapper;

import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface StatisticsMapper {
    Map<String, Object> currentOperations(@Param("longStayDays") int longStayDays);
    List<Map<String, Object>> admissionTrend(@Param("from") LocalDate from, @Param("to") LocalDate to);
    List<Map<String, Object>> departmentCensus(@Param("from") LocalDate from, @Param("to") LocalDate to);
    List<Map<String, Object>> bedUtilization();
    List<Map<String, Object>> revenueComposition(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                                  @Param("departmentId") Long departmentId);
    List<Map<String, Object>> drugUsage(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                        @Param("limit") int limit);
    Map<String, Object> lengthOfStayAndDebt(@Param("from") LocalDate from, @Param("to") LocalDate to);
    List<Map<String, Object>> lengthOfStayDistribution(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
