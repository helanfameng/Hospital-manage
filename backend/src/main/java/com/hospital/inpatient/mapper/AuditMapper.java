package com.hospital.inpatient.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface AuditMapper {
    void insert(Map<String, Object> values);
    List<Map<String, Object>> list(@Param("username") String username, @Param("action") String action,
                                   @Param("offset") int offset, @Param("size") int size);
    long count(@Param("username") String username, @Param("action") String action);
}

