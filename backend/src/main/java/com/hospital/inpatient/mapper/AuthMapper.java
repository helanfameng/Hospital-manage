package com.hospital.inpatient.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface AuthMapper {
    Map<String, Object> findUserByUsername(@Param("username") String username);
    List<String> findRolesByUserId(@Param("userId") long userId);
    List<String> findPermissionsByUserId(@Param("userId") long userId);
    void updateLastLogin(@Param("userId") long userId);
    int countUsers();
    void insertBootstrapUser(Map<String, Object> values);
    void assignRole(@Param("userId") long userId, @Param("roleCode") String roleCode);
}

