package com.hospital.inpatient.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;

public final class Requests {
    private Requests() {
    }

    public record PatientCreate(
            @NotBlank(message = "患者姓名不能为空") @Size(max=100) String name,
            @NotBlank @Pattern(regexp = "MALE|FEMALE|OTHER", message = "患者性别不合法") String gender,
            @NotNull(message = "出生日期不能为空") @Past(message = "出生日期必须早于今天") LocalDate birthDate,
            @NotBlank(message = "证件号不能为空") @Size(max = 32) String identityNumber,
            @Size(max = 32) String phone,
            @Size(max = 255) String address,
            @Size(max = 100) String emergencyContact,
            @Size(max = 32) String emergencyPhone
    ) {
    }

    public record PatientUpdate(
            @NotBlank(message = "患者姓名不能为空") @Size(max=100) String name,
            @NotBlank @Pattern(regexp = "MALE|FEMALE|OTHER", message = "患者性别不合法") String gender,
            @NotNull(message = "出生日期不能为空") @Past(message = "出生日期必须早于今天") LocalDate birthDate,
            @Size(max = 32) String phone,
            @Size(max = 255) String address,
            @Size(max = 100) String emergencyContact,
            @Size(max = 32) String emergencyPhone,
            boolean active
    ) {
    }

    public record AdmissionCreate(
            @NotNull Long patientId,
            @NotNull Long departmentId,
            @NotNull Long doctorId,
            @NotNull Long bedId,
            @NotNull @Digits(integer=10,fraction=2) @DecimalMin(value = "0.00") BigDecimal initialDeposit
    ) {
    }

    public record TransferCreate(
            @NotNull Long targetBedId,
            @Positive Long targetDoctorId,
            @NotBlank(message = "转床原因不能为空") @Size(max = 500) String reason
    ) {
    }

    public record OrderCreate(
            @NotNull Long admissionId,
            @Size(max = 500) String note,
            @NotEmpty(message = "医嘱至少包含一个项目") List<@Valid OrderLine> items
    ) {
    }

    public record OrderLine(
            @NotNull Long medicalItemId,
            @Digits(integer=9,fraction=3) @DecimalMin(value = "0.001") BigDecimal dosage,
            @NotNull @Digits(integer=9,fraction=3) @DecimalMin(value = "0.001") BigDecimal quantity,
            @Size(max=64) String route,
            @Size(max=255) String instructions,
            @Size(max=20) String doseUnit,
            @Pattern(regexp="TEMPORARY|LONG_TERM") String orderType,
            Instant startTime,
            Instant endTime,
            @Size(max=100) String groupKey,
            @Min(1) @Max(1000) Integer plannedAdministrationCount,
            @Size(max=255) String excessDispenseReason
    ) {
        public OrderLine(Long medicalItemId, BigDecimal dosage, BigDecimal quantity, String route, String instructions,
                         String doseUnit, String orderType, Instant startTime, Instant endTime, String groupKey) {
            this(medicalItemId,dosage,quantity,route,instructions,doseUnit,orderType,startTime,endTime,groupKey,null,null);
        }
        public OrderLine(Long medicalItemId, BigDecimal dosage, BigDecimal quantity, String route, String instructions) {
            this(medicalItemId,dosage,quantity,route,instructions,null,null,null,null,null);
        }
    }

    public record OrderExecution(@NotBlank @Size(max=64) String requestNo, @NotNull Long orderItemId,
            @NotBlank @Pattern(regexp="DISPENSE|ADMINISTER|PERFORM") String phase,
            boolean notDone, @Size(max=500) String note) {}

    public record DepositCreate(
            @NotNull Long admissionId,
            @NotBlank @Pattern(regexp = "INITIAL|SUPPLEMENT|REFUND", message = "押金流水类型不合法") String type,
            @NotNull @Digits(integer=10,fraction=2) @DecimalMin("0.01") BigDecimal amount,
            @NotBlank @Pattern(regexp = "CASH|CARD|TRANSFER", message = "支付方式不合法") String paymentMethod,
            @Size(max = 255) String note,
            @NotBlank @Size(max=64) String requestNo
    ) {
    }

    public record CorrectionCreate(@NotBlank @Size(max = 255) String reason) {
    }

    public record BedStatusUpdate(
            @NotBlank @Pattern(regexp = "AVAILABLE|MAINTENANCE|DISABLED", message = "床位状态不合法") String status
    ) {
    }

    public record ResolveAlert(@Size(max = 255) String note) {
    }
}
