package com.hospital.inpatient.controller;

import com.hospital.inpatient.common.ApiResponse;
import com.hospital.inpatient.mapper.StatisticsMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;

@RestController
@RequestMapping("/api/statistics")
@PreAuthorize("hasAuthority('STATISTICS_READ')")
public class StatisticsController {
    private final StatisticsMapper mapper;

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
        var data = new LinkedHashMap<String, Object>();
        data.put("departmentCensus", mapper.departmentCensus(safeFrom, safeTo));
        data.put("bedUtilization", mapper.bedUtilization());
        data.put("revenueComposition", mapper.revenueComposition(safeFrom, safeTo, departmentId));
        data.put("drugUsage", mapper.drugUsage(safeFrom, safeTo, 10));
        data.put("lengthOfStayAndDebt", mapper.lengthOfStayAndDebt(safeFrom, safeTo));
        return ApiResponse.success(data);
    }
}
