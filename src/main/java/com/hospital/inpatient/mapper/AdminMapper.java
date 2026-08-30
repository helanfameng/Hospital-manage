package com.hospital.inpatient.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface AdminMapper {
    List<Map<String, Object>> users();
    List<Map<String, Object>> roles();
    List<Map<String, Object>> permissions();
    void insertUser(Map<String, Object> values);
    void assignUserRole(@Param("userId") long userId, @Param("roleCode") String roleCode);
    int setUserEnabled(@Param("userId") long userId, @Param("enabled") boolean enabled);
}

