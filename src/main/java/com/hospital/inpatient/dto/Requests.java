package com.hospital.inpatient.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.PastOrPresent;
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
            @Size(max=16) String frequencyCode,
            Instant firstDoseAt
    ) {
        public OrderLine(Long medicalItemId, BigDecimal dosage, BigDecimal quantity, String route, String instructions,
                         String doseUnit, String orderType, Instant startTime, Instant endTime, String groupKey) {
            this(medicalItemId,dosage,quantity,route,instructions,doseUnit,orderType,startTime,endTime,groupKey,
                    dosage == null ? null : "QD", null);
        }
        public OrderLine(Long medicalItemId, BigDecimal dosage, BigDecimal quantity, String route, String instructions) {
            this(medicalItemId,dosage,quantity,route,instructions,null,null,null,null,null,
                    dosage == null ? null : "QD", null);
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

    /** A point-in-time inpatient nursing observation. One request may carry any subset of vitals. */
    public record NursingObservationCreate(
            @NotBlank(message = "记录请求号不能为空") @Size(max = 64) String requestNo,
            @NotNull(message = "观察时间不能为空") @PastOrPresent(message = "观察时间不能晚于当前时间") Instant observedAt,
            @DecimalMin(value = "30.0", message = "体温不能低于30.0℃")
            @DecimalMax(value = "45.0", message = "体温不能高于45.0℃")
            @Digits(integer = 2, fraction = 1) BigDecimal temperatureC,
            @Min(value = 20, message = "脉搏范围应为20-250次/分") @Max(value = 250, message = "脉搏范围应为20-250次/分") Integer pulseRate,
            @Min(value = 5, message = "呼吸频率范围应为5-80次/分") @Max(value = 80, message = "呼吸频率范围应为5-80次/分") Integer respiratoryRate,
            @Min(value = 50, message = "收缩压范围应为50-300mmHg") @Max(value = 300, message = "收缩压范围应为50-300mmHg") Integer systolicPressure,
            @Min(value = 20, message = "舒张压范围应为20-200mmHg") @Max(value = 200, message = "舒张压范围应为20-200mmHg") Integer diastolicPressure,
            @DecimalMin(value = "0.0", message = "血氧饱和度不能低于0%")
            @DecimalMax(value = "100.0", message = "血氧饱和度不能高于100%")
            @Digits(integer = 3, fraction = 2) BigDecimal oxygenSaturation,
            @Pattern(regexp = "CLEAR|CONFUSED|DROWSY|UNRESPONSIVE", message = "意识状态不合法") String consciousness,
            @Size(max = 500, message = "护理备注不能超过500字") String note
    ) {
        @AssertTrue(message = "至少填写一项生命体征或意识状态")
        public boolean hasMeasurement() {
            return temperatureC != null || pulseRate != null || respiratoryRate != null
                    || systolicPressure != null || diastolicPressure != null
                    || oxygenSaturation != null || (consciousness != null && !consciousness.isBlank());
        }

        @AssertTrue(message = "收缩压和舒张压必须同时填写")
        public boolean hasCompleteBloodPressure() {
            return (systolicPressure == null) == (diastolicPressure == null);
        }
    }

    /** A final result for one analyte from a successful inpatient examination. */
    public record InpatientResultCreate(
            @NotBlank(message = "结果请求号不能为空") @Size(max = 64) String requestNo,
            @NotNull(message = "检查执行记录不能为空") Long executionId,
            @NotBlank(message = "检验项目编码不能为空") @Size(max = 32) String analyteCode,
            @NotBlank(message = "检验项目名称不能为空") @Size(max = 100) String analyteName,
            @Digits(integer = 12, fraction = 6) BigDecimal resultValue,
            @Size(max = 1000, message = "结果文本不能超过1000字") String resultText,
            @Size(max = 32) String resultUnit,
            @Size(max = 100) String referenceRange,
            @NotBlank(message = "异常标记不能为空") @Pattern(regexp = "NORMAL|LOW|HIGH|CRITICAL|UNMARKED", message = "异常标记不合法") String abnormalFlag,
            @NotNull(message = "报告时间不能为空") @PastOrPresent(message = "报告时间不能晚于当前时间") Instant reportedAt,
            Long amendsResultId,
            @Size(max = 255, message = "更正原因不能超过255字") String amendmentReason
    ) {
        @AssertTrue(message = "结果值和结果文本至少填写一项")
        public boolean hasResult() {
            return resultValue != null || (resultText != null && !resultText.isBlank());
        }

        @AssertTrue(message = "更正结果必须填写更正原因")
        public boolean hasAmendmentReason() {
            return amendsResultId == null || (amendmentReason != null && !amendmentReason.isBlank());
        }
    }

    /** A diagnosis assertion linked to the inpatient diagnosis catalog. */
    public record InpatientDiagnosisCreate(
            @NotBlank(message = "诊断请求号不能为空") @Size(max = 64) String requestNo,
            @NotNull(message = "诊断目录不能为空") Long diagnosisCatalogId,
            @NotBlank(message = "诊断类型不能为空")
            @Pattern(regexp = "ADMISSION|PRIMARY|SECONDARY|COMPLICATION|DISCHARGE", message = "诊断类型不合法")
            String diagnosisType,
            boolean presentOnAdmission,
            @NotNull(message = "诊断时间不能为空") @PastOrPresent(message = "诊断时间不能晚于当前时间") Instant diagnosedAt,
            @Positive(message = "诊断医生无效") Long diagnosedBy,
            @Size(max = 500, message = "诊断备注不能超过500字") String note
    ) {
    }

    public record InpatientDiagnosisResolve(
            @NotBlank(message = "诊断状态不能为空")
            @Pattern(regexp = "RESOLVED|RULED_OUT", message = "诊断解决状态不合法") String status,
            @NotNull(message = "解决时间不能为空") @PastOrPresent(message = "解决时间不能晚于当前时间") Instant resolvedAt,
            @Size(max = 500, message = "解决说明不能超过500字") String note
    ) {
    }

    /** A signed inpatient note. Corrections are submitted as a new note. */
    public record InpatientClinicalNoteCreate(
            @NotBlank(message = "临床记录请求号不能为空") @Size(max = 64) String requestNo,
            @NotBlank(message = "记录类型不能为空")
            @Pattern(regexp = "ADMISSION_ASSESSMENT|PROGRESS|CRITICAL_EVENT|DISCHARGE_SUMMARY|OTHER", message = "记录类型不合法")
            String noteType,
            @NotBlank(message = "记录标题不能为空") @Size(max = 200) String title,
            @NotBlank(message = "记录内容不能为空") @Size(max = 10000, message = "记录内容不能超过10000字") String content,
            @NotNull(message = "记录作者不能为空") @Positive Long authoredBy,
            @NotNull(message = "起草时间不能为空") @PastOrPresent(message = "起草时间不能晚于当前时间") Instant authoredAt,
            @NotNull(message = "签署时间不能为空") @PastOrPresent(message = "签署时间不能晚于当前时间") Instant signedAt,
            Long amendsNoteId,
            @Size(max = 500, message = "更正原因不能超过500字") String amendmentReason
    ) {
        @AssertTrue(message = "签署时间不能早于起草时间")
        public boolean hasOrderedTimes() {
            return authoredAt == null || signedAt == null || !authoredAt.isAfter(signedAt);
        }

        @AssertTrue(message = "更正临床记录必须填写更正原因")
        public boolean hasAmendmentReason() {
            return amendsNoteId == null || (amendmentReason != null && !amendmentReason.isBlank());
        }
    }
}
