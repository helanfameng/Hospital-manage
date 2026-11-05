package com.hospital.inpatient.service;

import com.hospital.inpatient.mapper.ClinicalMapper;
import com.hospital.inpatient.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {
    @Mock ClinicalMapper mapper;
    @Mock AccountGuard guard;
    @Mock TypedOrderService typed;
    OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderService(mapper, guard, typed);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(5, "nurse", List.of("NURSE")), null, List.of()));
    }

    @Test
    void currentStatusChangeDelegatesToTypedWorkflow() {
        when(typed.current(9L)).thenReturn(true);
        when(guard.orderAdmission(9L)).thenReturn(1L);
        when(mapper.findOrder(9L)).thenReturn(java.util.Map.of("id",9L));
        when(mapper.findOrderItems(9L)).thenReturn(List.of());
        service.changeStatus(9L,"submit");
        verify(typed).change(9L,"submit",null);
    }
}
