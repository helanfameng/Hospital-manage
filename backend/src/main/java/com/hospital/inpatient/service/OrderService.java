package com.hospital.inpatient.service;

import com.hospital.inpatient.common.PageResult;
import com.hospital.inpatient.dto.Requests;
import com.hospital.inpatient.exception.BusinessException;
import com.hospital.inpatient.mapper.ClinicalMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@Service
public class OrderService {
    private final ClinicalMapper mapper;
    private final AccountGuard guard;
    private final TypedOrderService typed;

    public OrderService(ClinicalMapper mapper, AccountGuard guard, TypedOrderService typed) {
        this.mapper = mapper;
        this.guard = guard;
        this.typed = typed;
    }

    public PageResult<Map<String, Object>> list(Long admissionId, String status, String patientName, String orderNo, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        String name = patientName == null ? null : patientName.trim();
        String number = orderNo == null ? null : orderNo.trim();
        return new PageResult<>(mapper.listOrders(admissionId, status, name, number, (safePage - 1) * safeSize, safeSize),
                mapper.countOrders(admissionId, status, name, number), safePage, safeSize);
    }

    public Map<String, Object> detail(long id) {
        guard.requireRead(guard.orderAdmission(id));
        var order = mapper.findOrder(id);
        if (order == null) {
            throw new BusinessException("ORDER_NOT_FOUND", "医嘱不存在", HttpStatus.NOT_FOUND);
        }
        var result = new HashMap<>(order);
        result.put("items", mapper.findOrderItems(id));
        return result;
    }

    @Transactional
    public Map<String, Object> create(Requests.OrderCreate request) {
        return detail(typed.create(request));
    }

    @Transactional
    public Map<String, Object> changeStatus(long id, String action) {
        if (!typed.current(id)) throw new BusinessException("ORDER_NOT_FOUND","医嘱不存在",HttpStatus.NOT_FOUND);
        typed.change(id,action,null);
        return detail(id);
    }
}
