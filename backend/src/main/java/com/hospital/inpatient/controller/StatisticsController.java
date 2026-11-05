package com.hospital.inpatient.controller;

import com.hospital.inpatient.common.ApiResponse;
import com.hospital.inpatient.mapper.StatisticsMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;

@RestController
@RequestMapping("/api/statistics")
@PreAuthorize("hasAuthority('STATISTICS_READ')")
public class StatisticsController {
    private final StatisticsMapper mapper;
    @Value("${hospital.statistics.long-stay-days:14}")
    private int longStayDays;

    public StatisticsController(StatisticsMapper mapper) {
        this.mapper = mapper;
    }

    @GetMapping("/overview")
    public ApiResponse<?> overview(@RequestParam(required = false) LocalDate from,
                                   @RequestParam(required = false) LocalDate to,
                                   @RequestParam(required = false) Long departmentId) {
        LocalDate safeTo = to == null ? LocalDate.now(java.time.ZoneOffset.UTC) : to;
        LocalDate safeFrom = from == null ? safeTo.minusMonths(6) : from;
        if (safeFrom.isAfter(safeTo)) throw new com.hospital.inpatient.exception.BusinessException("INVALID_DATE_RANGE","开始日期不能晚于结束日期");
        if (ChronoUnit.DAYS.between(safeFrom, safeTo) > 730) throw new com.hospital.inpatient.exception.BusinessException("DATE_RANGE_TOO_LARGE","统计日期范围不能超过两年");
        var data = new LinkedHashMap<String, Object>();
        data.put("currentOperations", mapper.currentOperations(longStayDays));
        data.put("admissionTrend", mapper.admissionTrend(safeFrom, safeTo));
        data.put("departmentCensus", mapper.departmentCensus(safeFrom, safeTo));
        data.put("bedUtilization", mapper.bedUtilization());
        data.put("lengthOfStayAndDebt", mapper.lengthOfStayAndDebt(safeFrom, safeTo));
        data.put("lengthOfStayDistribution", mapper.lengthOfStayDistribution(safeFrom, safeTo));
        return ApiResponse.success(data);
    }
}
