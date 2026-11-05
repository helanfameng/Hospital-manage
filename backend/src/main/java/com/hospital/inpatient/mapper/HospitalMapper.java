package com.hospital.inpatient.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface HospitalMapper {
    List<Map<String, Object>> listPatients(@Param("keyword") String keyword, @Param("offset") int offset, @Param("size") int size);
    long countPatients(@Param("keyword") String keyword);
    Map<String, Object> findPatient(@Param("id") long id);
    void insertPatient(Map<String, Object> values);
    int updatePatient(Map<String, Object> values);
    List<Map<String, Object>> patientAdmissions(@Param("patientId") long patientId);

    List<Map<String, Object>> listAdmissions(@Param("status") String status, @Param("departmentId") Long departmentId,
                                             @Param("keyword") String keyword, @Param("longStayDays") Integer longStayDays,
                                             @Param("settlementBlocked") Boolean settlementBlocked,
                                             @Param("offset") int offset, @Param("size") int size);
    long countAdmissions(@Param("status") String status, @Param("departmentId") Long departmentId,
                         @Param("keyword") String keyword, @Param("longStayDays") Integer longStayDays,
                         @Param("settlementBlocked") Boolean settlementBlocked);
    Map<String, Object> findAdmission(@Param("id") long id);
    List<Map<String, Object>> bedHistory(@Param("admissionId") long admissionId);
    int markPendingDischarge(@Param("admissionId") long admissionId);
    int cancelAdmission(@Param("admissionId") long admissionId);

    List<Map<String, Object>> listBeds(@Param("wardId") Long wardId, @Param("status") String status);
    int updateBedStatus(@Param("bedId") long bedId, @Param("status") String status);
    List<Map<String, Object>> listDepartments();
    List<Map<String, Object>> listStaff(@Param("staffType") String staffType, @Param("departmentId") Long departmentId);
    List<Map<String, Object>> listMedicalItems(@Param("itemType") String itemType, @Param("keyword") String keyword);

    Long staffIdByUserId(@Param("userId") long userId);
    void callAdmit(Map<String, Object> parameters);
    void callTransfer(Map<String, Object> parameters);
}
