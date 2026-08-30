package com.hospital.inpatient;

import com.hospital.inpatient.dto.Requests;
import com.hospital.inpatient.exception.BusinessException;
import com.hospital.inpatient.security.CurrentUser;
import com.hospital.inpatient.security.JwtService;
import com.hospital.inpatient.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Creates uniquely named fixtures only in an explicitly configured local test database.
 * No outer test transaction: each service call really commits or rolls back, including races. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.test.context.ContextConfiguration(initializers = DatabaseIntegrationTest.SafeDatabase.class)
@EnabledIfEnvironmentVariable(named = "HOSPITAL_TEST_DB_ENABLED", matches = "true")
class DatabaseIntegrationTest {
    public static class SafeDatabase implements org.springframework.context.ApplicationContextInitializer<org.springframework.context.ConfigurableApplicationContext> {
        @Override public void initialize(org.springframework.context.ConfigurableApplicationContext context) {
            String url=System.getenv("HOSPITAL_TEST_DB_URL");
            if (url==null || !url.startsWith("jdbc:mysql://")) throw new IllegalStateException("An explicit MySQL test URL is required before Flyway starts");
            var uri=java.net.URI.create(url.substring(5));
            if (!Set.of("localhost","127.0.0.1").contains(uri.getHost()) || !uri.getPath().endsWith("_test") || url.equals(System.getenv("HOSPITAL_DB_URL"))) {
                throw new IllegalStateException("Database integration tests require a separate local database ending in _test");
            }
        }

        static void verifyFlywayPrerequisites(String url) {
            String username = Optional.ofNullable(System.getenv("HOSPITAL_DB_USERNAME")).orElse("hospital_app");
            String password = Optional.ofNullable(System.getenv("HOSPITAL_DB_PASSWORD")).orElse("");
            try {
                Class.forName("com.mysql.cj.jdbc.Driver");
                try (var connection = DriverManager.getConnection(url, username, password)) {
                    List<String> problems = new ArrayList<>();
                    try (var statement = connection.createStatement();
                         var result = statement.executeQuery("SHOW VARIABLES LIKE 'log_bin_trust_function_creators'")) {
                        if (result.next()) {
                            String value = result.getString("Value");
                            if ("OFF".equalsIgnoreCase(value) || "0".equals(value)) {
                                problems.add("log_bin_trust_function_creators 当前为 OFF；请由数据库管理员在测试实例允许时执行 SET GLOBAL log_bin_trust_function_creators = 1");
                            }
                        }
                    }
                    try (var statement = connection.prepareStatement(
                            "SELECT version FROM flyway_schema_history WHERE success = FALSE ORDER BY installed_rank DESC LIMIT 1")) {
                        try (var result = statement.executeQuery()) {
                            if (result.next()) {
                                problems.add("发现失败的 Flyway 迁移记录 V" + result.getString("version")
                                        + "；请先修复隔离 *_test 数据库的迁移元数据（可显式运行 backend/tools/RepairTestMigration.java）");
                            }
                        }
                    } catch (SQLException e) {
                        // A new, empty test database has no Flyway history table yet.
                        if (!("42S02".equals(e.getSQLState()) || e.getErrorCode() == 1146)) {
                            throw e;
                        }
                    }
                    if (!problems.isEmpty()) {
                        throw new IllegalStateException("数据库集成测试前置检查未通过："
                                + String.join("；", problems)
                                + "。测试不会自动修改 MySQL 全局配置或迁移记录");
                    }
                }
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("未找到 MySQL JDBC 驱动，无法执行 Flyway 前置检查");
            } catch (SQLException e) {
                throw new IllegalStateException("无法连接测试数据库并执行 Flyway 前置检查；请检查 HOSPITAL_TEST_DB_URL、账号权限和数据库状态");
            }
        }
    }

    @BeforeAll
    static void verifyIntegrationDatabasePrerequisites() {
        SafeDatabase.verifyFlywayPrerequisites(System.getenv("HOSPITAL_TEST_DB_URL"));
    }

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PatientService patients;
    @Autowired AdmissionService admissions;
    @Autowired OrderService orders;
    @Autowired TypedOrderService typedOrders;
    @Autowired FinancialService finance;
    @Autowired NursingObservationService nursingObservations;
    @Autowired InpatientResultService inpatientResults;
    @Autowired InpatientClinicalRecordService clinicalRecords;
    @Autowired AdminService admins;
    @Autowired AuthService auth;
    @Autowired JwtService jwt;
    @Autowired MockMvc mvc;
    @Autowired com.hospital.inpatient.mapper.StatisticsMapper statistics;
    @Autowired com.hospital.inpatient.mapper.FinancialMapper financialMapper;
    long adminId;
    long departmentId;
    long doctorId;
    long bedId;
    long drugId;
    long examId;

    @BeforeEach void fixture() {
        String url = System.getenv("HOSPITAL_TEST_DB_URL");
        assertThat(url).as("A separate test database must be explicitly configured").isNotBlank();
        var uri = java.net.URI.create(url.substring(5));
        assertThat(uri.getHost()).isIn("localhost","127.0.0.1");
        assertThat(uri.getPath()).endsWith("_test");
        assertThat(url).isNotEqualTo(System.getenv("HOSPITAL_DB_URL"));
        adminId = number("SELECT id FROM user_account WHERE username='admin'");
        asAdmin();
        String tag = "T" + UUID.randomUUID().toString().replace("-","").substring(0,20);
        jdbcTemplate.update("INSERT INTO department(code,name) VALUES(?,?)",tag,tag);
        departmentId=number("SELECT id FROM department WHERE code=?",tag);
        jdbcTemplate.update("INSERT INTO staff(staff_no,name,department_id,staff_type) VALUES(?,?,?,'DOCTOR')",tag,tag,departmentId);
        doctorId=number("SELECT id FROM staff WHERE staff_no=?",tag);
        jdbcTemplate.update("INSERT INTO ward(code,name,department_id) VALUES(?,?,?)",tag,tag,departmentId);
        long ward=number("SELECT id FROM ward WHERE code=?",tag);
        jdbcTemplate.update("INSERT INTO room(ward_id,room_no) VALUES(?,'101')",ward);
        long room=number("SELECT id FROM room WHERE ward_id=?",ward);
        jdbcTemplate.update("INSERT INTO bed(room_id,bed_no) VALUES(?,'A'),(?,'B')",room,room);
        bedId=number("SELECT MIN(id) FROM bed WHERE room_id=?",room);
        jdbcTemplate.update("INSERT INTO medical_item(item_code,name,item_type,unit,unit_price,stock_quantity,inventory_managed) VALUES(?,?,'DRUG','box',10,100,TRUE)",tag,tag);
        drugId=number("SELECT id FROM medical_item WHERE item_code=?",tag);
        jdbcTemplate.update("INSERT INTO drug_profile(medical_item_id,dose_unit,dose_units_per_stock) VALUES(?,'片',10)",drugId);
        jdbcTemplate.update("INSERT INTO drug_profile_route(medical_item_id,route_code,sort_order) SELECT ?,code,? FROM medication_route WHERE name=?",drugId,1,"口服");
        jdbcTemplate.update("INSERT INTO drug_profile_route(medical_item_id,route_code,sort_order) SELECT ?,code,? FROM medication_route WHERE name=?",drugId,2,"静脉滴注");
        jdbcTemplate.update("INSERT INTO medical_item(item_code,name,item_type,unit,unit_price) VALUES(?,?,'EXAMINATION','time',20)",tag+"E",tag);
        examId=number("SELECT id FROM medical_item WHERE item_code=?",tag+"E");
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); RequestContextHolder.resetRequestAttributes(); }
    void asAdmin() { context(new CurrentUser(adminId,"admin",List.of("ADMIN"))); }
    void context(CurrentUser user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of()));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }
    long number(String sql,Object... args) { return jdbcTemplate.queryForObject(sql,Long.class,args); }
    BigDecimal money(String sql,Object... args) { return jdbcTemplate.queryForObject(sql,BigDecimal.class,args); }
    String value(String sql,Object... args) { return jdbcTemplate.queryForObject(sql,String.class,args); }
    long id(Map<String,Object> row) { return ((Number)row.get("id")).longValue(); }
    long patient() {
        return id(patients.create(new Requests.PatientCreate("验证患者","OTHER",LocalDate.of(1990,1,1),
            UUID.randomUUID().toString().replace("-",""),null,null,null,null)));
    }
    long admit(long patient,long bed) {
        return id(admissions.admit(new Requests.AdmissionCreate(patient,departmentId,doctorId,bed,new BigDecimal("1000.00"))));
    }
    long admission() { return admit(patient(),bedId); }
    long order(long admission,String type,long... items) {
        var lines=new java.util.ArrayList<Requests.OrderLine>();
        for(long item:items) lines.add(line(item,type));
        return id(orders.create(new Requests.OrderCreate(admission,"回归用例",lines)));
    }
    /** Non-drug items carry no dosage or route; drugs need the catalog's own dose unit. */
    Requests.OrderLine line(long item,String type) {
        var profile=jdbcTemplate.queryForList("""
            SELECT dp.dose_unit,mr.name route
            FROM drug_profile dp
            JOIN drug_profile_route dpr ON dpr.medical_item_id=dp.medical_item_id
            JOIN medication_route mr ON mr.code=dpr.route_code
            WHERE dp.medical_item_id=?
            ORDER BY dpr.sort_order
            """,item);
        if(profile.isEmpty()) return new Requests.OrderLine(item,null,BigDecimal.ONE,null,"回归用例",null,type,null,null,null);
        String unit=(String)profile.get(0).get("dose_unit"), route=(String)profile.get(0).get("route");
        return new Requests.OrderLine(item,BigDecimal.ONE,BigDecimal.ONE,route,"回归用例",unit,type,null,null,null,type.equals("LONG_TERM")?"QD":"ONCE",null);
    }
    long item(long order,long medicalItem) {
        return number("SELECT id FROM order_item WHERE medical_order_id=? AND medical_item_id=?",order,medicalItem);
    }
    /** Drugs bill on dispensing, everything else on performing; both are one execution here. */
    long execute(long item) {
        String phase="DRUG".equals(value("SELECT item_type_snapshot FROM order_item WHERE id=?",item)) ? "DISPENSE" : "PERFORM";
        return typedExecute(item,phase);
    }
    Requests.DepositCreate deposit(long a,String type,String amount) {
        return new Requests.DepositCreate(a,type,new BigDecimal(amount),"CASH","regression",UUID.randomUUID().toString());
    }
    void rejects(Runnable action) { assertThatThrownBy(action::run).isInstanceOfAny(BusinessException.class,DataAccessException.class); }

    Requests.OrderLine typedDrug(long drug,String type,String group) {
        return new Requests.OrderLine(drug,new BigDecimal("2"),BigDecimal.ONE,group==null?"口服":"静脉滴注","测试指令","片",type,null,null,group,type.equals("LONG_TERM")?"QD":"ONCE",null);
    }
    long typedOrder(long admission,Requests.OrderLine... lines) {
        return id(orders.create(new Requests.OrderCreate(admission,"新版回归",List.of(lines))));
    }
    long typedExecute(long line,String phase) {
        var result=typedOrders.execute(new Requests.OrderExecution(UUID.randomUUID().toString(),line,phase,false,"测试执行"));
        return ((Number)((List<?>)result.get("executionIds")).get(0)).longValue();
    }
    @Test void orderFiltersCombinePartialNamesNumbersAndStatusWithConsistentPagination() {
        long p=patient(),a=admit(p,bedId);
        String name="筛选%_"+UUID.randomUUID().toString().substring(0,8);
        jdbcTemplate.update("UPDATE patient SET name=? WHERE id=?",name,p);
        long first=typedOrder(a,typedDrug(drugId,"TEMPORARY",null));
        long second=typedOrder(a,typedDrug(drugId,"TEMPORARY",null));
        long legacy=order(a,"TEMPORARY",examId);
        orders.changeStatus(second,"submit");
        var page1=orders.list(null,null,"  "+name+"  ",null,1,1);
        assertThat(page1.total()).isEqualTo(3);
        assertThat(page1.items()).extracting(this::id).containsExactly(legacy);
        var page2=orders.list(null,null,name,null,2,1);
        assertThat(page2.total()).isEqualTo(3);
        assertThat(page2.items()).extracting(this::id).containsExactly(second);
        String code=value("SELECT order_no FROM medical_order WHERE id=?",second);
        var match=orders.list(null,"SUBMITTED",name,"  "+code.substring(4)+"  ",1,20);
        assertThat(match.total()).isEqualTo(1);
        assertThat(match.items()).extracting(this::id).containsExactly(second);
        assertThat(orders.list(null,"DRAFT",name,code,1,20).total()).isZero();
        assertThat(orders.list(null,null,"不存在的"+name,null,1,20).items()).isEmpty();
        String oldCode=value("SELECT order_no FROM medical_order WHERE id=?",legacy);
        assertThat(orders.list(a,null,null,oldCode.substring(3),1,20).items()).extracting(this::id).containsExactly(legacy);
        // Special characters are literal substring searches, never SQL wildcards.
        assertThat(orders.list(a,null,"%_",null,1,20).total()).isEqualTo(3);
        assertThat(orders.list(a,null,null,"%",1,20).total()).isZero();
        assertThat(orders.list(a,null,null,"' OR 1=1 --",1,20).total()).isZero();
        assertThat(orders.list(a,null,"  ","  ",1,20).total()).isEqualTo(3);
    }

    @Test void orderFiltersKeepDoctorAndWardPermissionsForRowsAndCounts() {
        long a=admission(),o=typedOrder(a,typedDrug(drugId,"TEMPORARY",null));
        String code=value("SELECT order_no FROM medical_order WHERE id=?",o);
        context(new CurrentUser(adminId,"scoped-doctor",List.of("DOCTOR"),doctorId,departmentId));
        assertThat(orders.list(null,null,"验证",code,1,20).items()).extracting(this::id).containsExactly(o);
        context(new CurrentUser(adminId,"other-doctor",List.of("DOCTOR"),-1L,departmentId));
        var denied=orders.list(null,null,"验证",code,1,20);
        assertThat(denied.total()).isZero();
        assertThat(denied.items()).isEmpty();
        context(new CurrentUser(adminId,"ward-nurse",List.of("NURSE"),-1L,departmentId));
        assertThat(orders.list(null,null,"验证",code,1,20).total()).isEqualTo(1);
        context(new CurrentUser(adminId,"other-ward",List.of("NURSE"),-1L,departmentId+999999));
        var otherWard=orders.list(null,null,"验证",code,1,20);
        assertThat(otherWard.total()).isZero();
        assertThat(otherWard.items()).isEmpty();
    }

    @Test void chargesExposeSourceOrdersAndCategorySnapshotsWithoutChangingHistory() {
        long a=admission(),o=typedOrder(a,typedDrug(drugId,"TEMPORARY",null)),line=item(o,drugId);
        orders.changeStatus(o,"submit");
        long execution=typedExecute(line,"DISPENSE");
        String number=value("SELECT charge_no FROM charge_detail WHERE execution_id=?",execution);
        assertThat(number).matches("C[0-9]{8}-[0-9]{8,19}");
        var normal=financialMapper.charges(a).get(0);
        assertThat(((Number)normal.get("order_id")).longValue()).isEqualTo(o);
        assertThat(normal.get("order_no")).isEqualTo(value("SELECT order_no FROM medical_order WHERE id=?",o));
        assertThat(normal.get("item_type")).isEqualTo("DRUG");
        String drugCode=value("SELECT item_code FROM medical_item WHERE id=?",drugId);
        String drugName=value("SELECT name FROM medical_item WHERE id=?",drugId);
        assertThat(normal).containsEntry("item_code",drugCode).containsEntry("item_name",drugName).containsEntry("unit","box");
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO charge_detail(admission_id,execution_id,medical_item_id,item_code_snapshot,item_name_snapshot,unit_snapshot,department_id,charge_no,charge_type,item_type_snapshot,unit_price_snapshot,quantity,amount) VALUES(?,?,?,?,?,?,?,?,'NORMAL','TREATMENT',10,1,10)",
            a,execution,drugId,drugCode,drugName,"box",departmentId,"C"+UUID.randomUUID().toString().substring(0,25))).isInstanceOf(DataAccessException.class);
        jdbcTemplate.update("UPDATE medical_item SET item_code=?,name=?,unit=? WHERE id=?",drugCode+"-NEW",drugName+"-新目录","盒",drugId);
        typedOrders.reverse(execution,"numbering regression");
        var charges=financialMapper.charges(a);
        assertThat(charges).hasSize(2);
        assertThat(charges.get(0).get("charge_no").toString()).matches("CR[0-9]{8}-[0-9]{8,19}");
        assertThat(charges).allSatisfy(c -> assertThat(c.get("order_no")).isEqualTo(normal.get("order_no")));
        assertThat(value("SELECT charge_no FROM charge_detail WHERE id=?",normal.get("id"))).isEqualTo(number);
        assertThat(money("SELECT SUM(amount) FROM charge_detail WHERE admission_id=?",a)).isEqualByComparingTo("0");
        // No execution source: retain the charge instead of dropping it through an inner join.
        String examCode=value("SELECT item_code FROM medical_item WHERE id=?",examId);
        String examName=value("SELECT name FROM medical_item WHERE id=?",examId);
        String examUnit=value("SELECT unit FROM medical_item WHERE id=?",examId);
        jdbcTemplate.update("INSERT INTO charge_detail(admission_id,medical_item_id,item_code_snapshot,item_name_snapshot,unit_snapshot,charge_no,charge_type,item_type_snapshot,unit_price_snapshot,quantity,amount) VALUES(?,?,?,?,?,?,'NORMAL','EXAMINATION',20,1,20)",a,examId,examCode,examName,examUnit,"OLD"+UUID.randomUUID().toString().replace("-","").substring(0,20));
        assertThat(financialMapper.charges(a)).hasSize(3).anySatisfy(c -> {
            assertThat(c.get("order_no")).isNull();
            assertThat(c.get("item_type")).as("manual charge keeps its category snapshot").isEqualTo("EXAMINATION");
        });
        jdbcTemplate.update("UPDATE medical_item SET item_type='TREATMENT' WHERE id=?",examId);
        jdbcTemplate.update("UPDATE medical_item SET item_code=?,name=?,unit=? WHERE id=?",examCode+"-NEW",examName+"-新目录","次",examId);
        assertThat(financialMapper.charges(a)).anySatisfy(c -> {
            assertThat(c.get("order_no")).isNull();
            assertThat(c.get("item_type")).isEqualTo("EXAMINATION");
            assertThat(c.get("item_code")).isEqualTo(examCode);
            assertThat(c.get("item_name")).isEqualTo(examName);
            assertThat(c.get("unit")).isEqualTo(examUnit);
        });
        var summary=jdbcTemplate.queryForMap("SELECT examination_charge,treatment_charge FROM v_admission_charge_summary WHERE admission_id=?",a);
        assertThat((BigDecimal) summary.get("examination_charge")).isEqualByComparingTo("20.00");
        assertThat((BigDecimal) summary.get("treatment_charge")).isEqualByComparingTo("0.00");
    }

    @Test void chargeSequenceIsUniqueAcrossConcurrentAllocations() throws Exception {
        var allocated=new java.util.concurrent.ConcurrentLinkedQueue<String>();
        assertThat(race(() -> allocated.add(ChargeNumber.next(jdbcTemplate,false)),
            () -> allocated.add(ChargeNumber.next(jdbcTemplate,false)))).containsExactly(true,true);
        assertThat(allocated).hasSize(2).doesNotHaveDuplicates();
        String today=value("SELECT DATE_FORMAT(DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),'%Y%m%d')");
        assertThat(allocated).allSatisfy(n -> assertThat(n).startsWith("C"+today+"-").matches("C[0-9]{8}-[0-9]{8,19}"));
    }

    @Test void databaseHardeningRejectsCrossAggregateAndLedgerShapeErrors() {
        long a=admission(),b=admit(patient(),bedId+1);
        long grouped=typedOrder(a,typedDrug(drugId,"TEMPORARY","hardening-group"),typedDrug(drugId,"TEMPORARY","hardening-group"));
        long other=typedOrder(b,typedDrug(drugId,"TEMPORARY",null));
        long group=number("SELECT id FROM order_group WHERE medical_order_id=?",grouped);

        // The composite foreign key prevents a group from another medical order
        // being smuggled into an otherwise valid order item.
        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO order_item(medical_order_id,medical_item_id,group_id,dosage,quantity_per_execution,route,
                item_name_snapshot,item_type_snapshot,unit_snapshot,dose_unit,dose_units_per_stock,price_snapshot,
                billable,inventory_managed,execution_role,order_type,item_status,start_time)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,? ,UTC_TIMESTAMP(3))
            """,other,drugId,group,BigDecimal.ONE,BigDecimal.ONE,"口服","跨医嘱组测试","DRUG","box","片",
                BigDecimal.TEN,BigDecimal.TEN,true,true,"NURSE","TEMPORARY","DRAFT"))
            .isInstanceOf(DataAccessException.class);

        // The generated key makes the one-open-alert rule a database invariant,
        // even when an integration bypasses the alert refresh procedure.
        jdbcTemplate.update("INSERT INTO account_alert(admission_id,level,balance_snapshot,threshold_snapshot,status) VALUES(?,'CRITICAL',-1,0,'OPEN')",a);
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO account_alert(admission_id,level,balance_snapshot,threshold_snapshot,status) VALUES(?,'CRITICAL',-2,0,'OPEN')",a))
            .isInstanceOf(DataAccessException.class);

        long line=item(grouped,drugId);
        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO execution_record(request_no,order_item_id,quantity,status,executor_id,phase,unit_snapshot)
            VALUES(? ,?,1,'SUCCESS',?,'PERFORM','盒')
            """,UUID.randomUUID().toString(),line,doctorId))
            .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO drug_inventory_transaction(medical_item_id,transaction_type,quantity,before_quantity,after_quantity,operated_by)
            VALUES(?,'ADJUSTMENT',1,100,100,?)
            """,drugId,adminId))
            .isInstanceOf(DataAccessException.class);

        String code="BAD"+UUID.randomUUID().toString().replace("-","").substring(0,20);
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO medical_item(item_code,name,item_type,unit,unit_price,stock_quantity) VALUES(?,?,'EXAMINATION','次',10,1)",code,code))
            .isInstanceOf(DataAccessException.class);
    }

    @Test void typedMedicationSeparatesDispensingAdministrationAndCharges() {
        long a=admission(),o=typedOrder(a,typedDrug(drugId,"TEMPORARY",null)),line=item(o,drugId);
        assertThat(orders.detail(o)).doesNotContainKey("frequency");
        assertThat(number("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='medical_order' AND column_name='frequency'")).isZero();
        orders.changeStatus(o,"submit");
        rejects(() -> typedExecute(line,"ADMINISTER"));
        long dispense=typedExecute(line,"DISPENSE");
        assertThat(value("SELECT status FROM medical_order WHERE id=?",o)).isEqualTo("IN_PROGRESS");
        assertThat(number("SELECT COUNT(*) FROM charge_detail WHERE admission_id=?",a)).isEqualTo(1);
        assertThat(money("SELECT stock_quantity FROM medical_item WHERE id=?",drugId)).isEqualByComparingTo("99");
        long administer=typedExecute(line,"ADMINISTER");
        assertThat(value("SELECT status FROM medical_order WHERE id=?",o)).isEqualTo("COMPLETED");
        assertThat(number("SELECT COUNT(*) FROM charge_detail WHERE admission_id=?",a)).isEqualTo(1);
        assertThat(money("SELECT administered_quantity FROM order_item WHERE id=?",line)).isEqualByComparingTo("2");
        rejects(() -> typedExecute(line,"ADMINISTER"));
        rejects(() -> typedOrders.reverse(dispense,"used drug"));
        typedOrders.reverse(administer,"错误给药记录纠正，仅测试");
        typedOrders.reverse(dispense,"未使用整笔退药");
        assertThat(money("SELECT stock_quantity FROM medical_item WHERE id=?",drugId)).isEqualByComparingTo("100");
        assertThat(money("SELECT total_charge FROM v_admission_deposit_balance WHERE admission_id=?",a)).isEqualByComparingTo("0");
    }

    @Test void medicationRoutesAreNormalizedAndCatalogOrderIsPreserved() {
        assertThat(number("SELECT COUNT(*) FROM medication_route")).isEqualTo(11);
        assertThat(number("SELECT COUNT(*) FROM medication_frequency WHERE code='PRN6H'")).isZero();
        assertThat(number("""
            SELECT COUNT(*)
            FROM information_schema.columns
            WHERE table_schema=DATABASE() AND table_name='medication_frequency' AND column_name='as_needed'
            """)).isZero();
        assertThat(number("""
            SELECT COUNT(*)
            FROM drug_profile dp
            LEFT JOIN drug_profile_route dpr ON dpr.medical_item_id=dp.medical_item_id
            WHERE dpr.medical_item_id IS NULL
            """)).isZero();
        var routeRows=jdbcTemplate.queryForList("""
            SELECT mr.name
            FROM drug_profile_route dpr
            JOIN medication_route mr ON mr.code=dpr.route_code
            WHERE dpr.medical_item_id=?
            ORDER BY dpr.sort_order
            """,drugId);
        assertThat(routeRows.stream().map(row -> (String)row.get("name")).toList())
            .containsExactly("口服","静脉滴注");
        var catalogItem=typedOrders.catalog().stream()
            .filter(row -> ((Number)row.get("id")).longValue()==drugId)
            .findFirst().orElseThrow();
        assertThat(catalogItem.get("allowed_routes")).isEqualTo("口服,静脉滴注");
        assertThat(number("""
            SELECT COUNT(*)
            FROM information_schema.columns
            WHERE table_schema=DATABASE() AND table_name='drug_profile' AND column_name='allowed_routes'
            """)).isZero();
    }
    @Test void typedNonbillableInstructionsAndLongTermLifecycle() {
        long a=admission(),nursing=number("SELECT id FROM medical_item WHERE item_code='NURS002'");
        long o=typedOrder(a,new Requests.OrderLine(nursing,null,BigDecimal.ONE,null,"观察记录",null,"LONG_TERM",null,null,null)),line=item(o,nursing);
        orders.changeStatus(o,"submit");
        typedOrders.execute(new Requests.OrderExecution(UUID.randomUUID().toString(),line,"PERFORM",true,"暂未执行"));
        assertThat(value("SELECT item_status FROM order_item WHERE id=?",line)).isEqualTo("SUBMITTED");
        typedExecute(line,"PERFORM"); typedExecute(line,"PERFORM");
        assertThat(value("SELECT status FROM medical_order WHERE id=?",o)).isEqualTo("IN_PROGRESS");
        assertThat(number("SELECT COUNT(*) FROM charge_detail WHERE admission_id=?",a)).isZero();
        typedOrders.change(o,"stop",line);
        rejects(() -> typedExecute(line,"PERFORM"));
        assertThat(value("SELECT status FROM medical_order WHERE id=?",o)).isEqualTo("STOPPED");
    }
    @Test void perOccurrenceServicesRejectMultipleQuantityAndChargeOnePerExecution() {
        long a=admission();
        for(String category:List.of("EXAMINATION","LAB","TREATMENT")) {
            String code="S"+UUID.randomUUID().toString().substring(0,20);
            jdbcTemplate.update("INSERT INTO medical_item(item_code,name,item_type,unit,unit_price,allow_fractional_quantity) VALUES(?,?,?,'次',20,TRUE)",code,code,category);
            long service=number("SELECT id FROM medical_item WHERE item_code=?",code);
            long before=number("SELECT COUNT(*) FROM medical_order WHERE admission_id=?",a);
            for(String quantity:List.of("2","0.5")) rejects(() -> typedOrder(a,new Requests.OrderLine(service,null,new BigDecimal(quantity),null,null,null,"TEMPORARY",null,null,null)));
            assertThat(number("SELECT COUNT(*) FROM medical_order WHERE admission_id=?",a)).isEqualTo(before);
            long temporary=typedOrder(a,new Requests.OrderLine(service,null,BigDecimal.ONE,null,null,null,"TEMPORARY",null,null,null));
            orders.changeStatus(temporary,"submit"); long tempLine=item(temporary,service);
            typedExecute(tempLine,"PERFORM"); rejects(() -> typedExecute(tempLine,"PERFORM"));
            assertThat(value("SELECT item_status FROM order_item WHERE id=?",tempLine)).isEqualTo("COMPLETED");
            long ongoing=typedOrder(a,new Requests.OrderLine(service,null,BigDecimal.ONE,null,null,null,"LONG_TERM",null,null,null));
            orders.changeStatus(ongoing,"submit"); long ongoingLine=item(ongoing,service);
            typedExecute(ongoingLine,"PERFORM"); typedExecute(ongoingLine,"PERFORM");
            assertThat(value("SELECT item_status FROM order_item WHERE id=?",ongoingLine)).isEqualTo("IN_PROGRESS");
            assertThat(number("SELECT COUNT(*) FROM charge_detail WHERE admission_id=? AND medical_item_id=? AND quantity=1 AND amount=20",a,service)).isEqualTo(3);
        }
        assertThat(money("SELECT total_charge FROM v_admission_deposit_balance WHERE admission_id=?",a)).isEqualByComparingTo("180");
        long drugOrder=typedOrder(a,new Requests.OrderLine(drugId,BigDecimal.ONE,new BigDecimal("2"),"口服",null,"片","TEMPORARY",null,null,null,"ONCE",null));
        assertThat(money("SELECT quantity_per_execution FROM order_item WHERE medical_order_id=?",drugOrder)).isEqualByComparingTo("2");
        long otherUnit=typedOrder(a,new Requests.OrderLine(examId,null,new BigDecimal("2"),null,null,null,"TEMPORARY",null,null,null));
        assertThat(money("SELECT quantity_per_execution FROM order_item WHERE medical_order_id=?",otherUnit)).isEqualByComparingTo("2");
    }
    Requests.OrderLine scheduledDrug(long drug,String frequency,String quantity,String group,Instant firstDose) {
        Instant start=firstDose.minusSeconds(60);
        Instant end="ONCE".equals(frequency) ? null : firstDose.plusSeconds(86400);
        return new Requests.OrderLine(drug,BigDecimal.TEN,new BigDecimal(quantity),group==null?"口服":"静脉滴注",null,"片","TEMPORARY",start,end,group,frequency,firstDose);
    }
    @SuppressWarnings("unchecked")
    Map<String,Object> detailLine(long order,long line) {
        return ((List<Map<String,Object>>)orders.detail(order).get("items")).stream().filter(r->id(r)==line).findFirst().orElseThrow();
    }
    @Test void medicationPlanUsesActualAdministrationFactsAndReversalRestoresProgress() {
        Instant first=Instant.now().minusSeconds(60);
        long a=admission(),o=typedOrder(a,scheduledDrug(drugId,"ONCE","2",null,first)),line=item(o,drugId);
        orders.changeStatus(o,"submit"); long dispense=typedExecute(line,"DISPENSE");
        typedOrders.execute(new Requests.OrderExecution(UUID.randomUUID().toString(),line,"ADMINISTER",true,"暂未给药"));
        assertThat(((Number)detailLine(o,line).get("administration_count")).intValue()).isZero();
        typedExecute(line,"ADMINISTER");
        assertThat(value("SELECT item_status FROM order_item WHERE id=?",line)).isEqualTo("COMPLETED");
        assertThat(((Number)detailLine(o,line).get("administration_count")).intValue()).isEqualTo(1);
        assertThat(typedOrders.pending("ADMINISTER",null)).noneSatisfy(r->assertThat(((Number)r.get("order_item_id")).longValue()).isEqualTo(line));
        rejects(()->typedOrders.reverse(dispense,"不能整笔退回已用药品"));
        assertThat(value("SELECT status FROM medical_order WHERE id=?",o)).isEqualTo("COMPLETED");
        long administer=number("SELECT id FROM execution_record WHERE order_item_id=? AND phase='ADMINISTER' AND status='SUCCESS'",line);
        typedOrders.reverse(administer,"错误给药记录纠正");
        assertThat(value("SELECT status FROM medical_order WHERE id=?",o)).isEqualTo("SUBMITTED");
        assertThat(((Number)detailLine(o,line).get("administration_count")).intValue()).isZero();
        rejects(()->typedOrders.reverse(administer,"重复纠正"));
        typedExecute(line,"ADMINISTER");
        assertThat(value("SELECT status FROM medical_order WHERE id=?",o)).isEqualTo("COMPLETED");
        assertThat(money("SELECT administered_quantity FROM order_item WHERE id=?",line)).isEqualByComparingTo("10");
        assertThat(money("SELECT stock_quantity FROM medical_item WHERE id=?",drugId)).isEqualByComparingTo("98");
        assertThat(number("SELECT COUNT(*) FROM charge_detail WHERE admission_id=?",a)).isEqualTo(1);
        assertThat(money("SELECT SUM(amount) FROM charge_detail WHERE admission_id=?",a)).isEqualByComparingTo("20");
    }
    @Test void medicationPlanValidatesFrequencyAndTreatsExtraDispensingAsInventoryBatch() {
        long a=admission();
        Instant first=Instant.now().minusSeconds(60);
        rejects(()->typedOrder(a,scheduledDrug(drugId,"Q4H","1",null,first)));
        rejects(()->typedOrder(a,new Requests.OrderLine(drugId,BigDecimal.ONE,BigDecimal.ONE,"口服",null,"片","TEMPORARY",null,null,null,null,null)));
        rejects(()->typedOrder(a,new Requests.OrderLine(drugId,BigDecimal.ONE,BigDecimal.ONE,"口服",null,"片","LONG_TERM",null,null,null,"ONCE",null)));
        long o=typedOrder(a,scheduledDrug(drugId,"QD","3",null,first)),line=item(o,drugId);
        orders.changeStatus(o,"submit"); typedExecute(line,"DISPENSE"); typedExecute(line,"ADMINISTER");
        assertThat(value("SELECT status FROM medical_order WHERE id=?",o)).isEqualTo("IN_PROGRESS");
        assertThat(((Number)detailLine(o,line).get("administration_count")).intValue()).isEqualTo(1);
        assertThat(typedOrders.pending("ADMINISTER",null)).noneSatisfy(r->assertThat(((Number)r.get("order_item_id")).longValue()).isEqualTo(line));
        assertThat(money("SELECT dispensed_quantity*dose_units_per_stock-administered_quantity FROM order_item WHERE id=?",line)).isEqualByComparingTo("20");
    }
    @Test void infusionPlansHaveMatchingCountsAndAdvanceAndReverseAtomically() {
        long a=admission(); String code="GP"+UUID.randomUUID().toString().substring(0,20);
        jdbcTemplate.update("INSERT INTO medical_item(item_code,name,item_type,unit,unit_price,stock_quantity,inventory_managed) VALUES(?,?,'DRUG','box',30,10,TRUE)",code,code);
        long other=number("SELECT id FROM medical_item WHERE item_code=?",code);
        jdbcTemplate.update("INSERT INTO drug_profile(medical_item_id,dose_unit,dose_units_per_stock) VALUES(?,'片',10)",other);
        jdbcTemplate.update("INSERT INTO drug_profile_route(medical_item_id,route_code,sort_order) SELECT ?,code,1 FROM medication_route WHERE name='静脉滴注'",other);
        Instant first=Instant.now().minusSeconds(60);
        rejects(()->typedOrder(a,scheduledDrug(drugId,"ONCE","2","组",first),scheduledDrug(other,"QD","2","组",first)));
        long o=typedOrder(a,scheduledDrug(drugId,"ONCE","2","组",first),scheduledDrug(other,"ONCE","2","组",first)),line=item(o,drugId);
        orders.changeStatus(o,"submit"); typedExecute(line,"DISPENSE"); typedExecute(line,"ADMINISTER");
        assertThat(number("SELECT COUNT(*) FROM order_item WHERE medical_order_id=? AND item_status='COMPLETED' AND administered_quantity=10",o)).isEqualTo(2);
        assertThat(value("SELECT status FROM medical_order WHERE id=?",o)).isEqualTo("COMPLETED");
        long second=number("SELECT MAX(id) FROM execution_record WHERE batch_key IS NOT NULL AND order_item_id IN (SELECT id FROM order_item WHERE medical_order_id=?) AND phase='ADMINISTER'",o);
        typedOrders.reverse(second,"整组纠错");
        assertThat(number("SELECT COUNT(*) FROM order_item WHERE medical_order_id=? AND item_status='SUBMITTED' AND administered_quantity=0",o)).isEqualTo(2);
        assertThat(((Number)detailLine(o,item(o,other)).get("administration_count")).intValue()).isZero();
    }
    @Test void concurrentFinalDoseCannotExceedPlan() throws Exception {
        Instant first=Instant.now().minusSeconds(60);
        long a=admission(),o=typedOrder(a,scheduledDrug(drugId,"ONCE","2",null,first)),line=item(o,drugId);
        orders.changeStatus(o,"submit"); typedExecute(line,"DISPENSE"); typedExecute(line,"ADMINISTER");
        assertThat(race(()->typedExecute(line,"ADMINISTER"),()->typedExecute(line,"ADMINISTER"))).containsExactlyInAnyOrder(true,false);
        assertThat(((Number)detailLine(o,line).get("administration_count")).intValue()).isEqualTo(1);
        assertThat(value("SELECT status FROM medical_order WHERE id=?",o)).isEqualTo("COMPLETED");
        // A completed once-only plan cannot be reopened by leftover stock.
        rejects(()->typedExecute(line,"ADMINISTER"));
    }
    @Test void typedValidationAndPriceSnapshots() {
        long a=admission();
        rejects(() -> typedOrder(a,new Requests.OrderLine(drugId,BigDecimal.ONE,BigDecimal.ONE,"口服",null,"mL","TEMPORARY",null,null,null)));
        rejects(() -> typedOrder(a,new Requests.OrderLine(drugId,BigDecimal.ONE,new BigDecimal("1.001"),"口服",null,"片","TEMPORARY",null,null,null)));
        rejects(() -> typedOrder(a,typedDrug(drugId,"TEMPORARY","孤立组")));
        long bedFee=number("SELECT MIN(id) FROM medical_item WHERE item_type='BED_FEE'");
        rejects(() -> typedOrder(a,new Requests.OrderLine(bedFee,null,BigDecimal.ONE,null,null,null,"TEMPORARY",null,null,null)));
        long o=typedOrder(a,new Requests.OrderLine(examId,null,BigDecimal.ONE,null,"检查",null,"TEMPORARY",null,null,null));
        jdbcTemplate.update("UPDATE medical_item SET unit_price=999 WHERE id=?",examId);
        orders.changeStatus(o,"submit"); typedExecute(item(o,examId),"PERFORM");
        assertThat(money("SELECT total_charge FROM v_admission_deposit_balance WHERE admission_id=?",a)).isEqualByComparingTo("20");
        assertThat(value("SELECT status FROM medical_order WHERE id=?",o)).isEqualTo("COMPLETED");
    }
    @Test void typedInfusionGroupIsAtomicAndTemplatesRemainEditableLists() {
        long a=admission(); String code="G"+UUID.randomUUID().toString().substring(0,20);
        jdbcTemplate.update("INSERT INTO medical_item(item_code,name,item_type,unit,unit_price,stock_quantity,inventory_managed) VALUES(?,?,'DRUG','box',30,0,TRUE)",code,code);
        long other=number("SELECT id FROM medical_item WHERE item_code=?",code);
        jdbcTemplate.update("INSERT INTO drug_profile(medical_item_id,dose_unit,dose_units_per_stock) VALUES(?,'片',10)",other);
        jdbcTemplate.update("INSERT INTO drug_profile_route(medical_item_id,route_code,sort_order) SELECT ?,code,1 FROM medication_route WHERE name='静脉滴注'",other);
        long o=typedOrder(a,typedDrug(drugId,"TEMPORARY","测试组"),typedDrug(other,"TEMPORARY","测试组")),line=item(o,drugId);
        orders.changeStatus(o,"submit"); rejects(() -> typedExecute(line,"DISPENSE"));
        assertThat(money("SELECT stock_quantity FROM medical_item WHERE id=?",drugId)).isEqualByComparingTo("100");
        assertThat(number("SELECT COUNT(*) FROM charge_detail WHERE admission_id=?",a)).isZero();
        jdbcTemplate.update("UPDATE medical_item SET stock_quantity=5 WHERE id=?",other);
        long dispense=typedExecute(line,"DISPENSE");
        assertThat(number("SELECT COUNT(*) FROM order_item WHERE medical_order_id=? AND dispensed_quantity=1",o)).isEqualTo(2);
        typedOrders.reverse(dispense,"整组退药测试");
        assertThat(number("SELECT COUNT(*) FROM order_item WHERE medical_order_id=? AND dispensed_quantity=0",o)).isEqualTo(2);
        typedExecute(line,"DISPENSE"); typedExecute(line,"ADMINISTER");
        assertThat(value("SELECT status FROM medical_order WHERE id=?",o)).isEqualTo("COMPLETED");
        var template=typedOrders.saveTemplate(new com.hospital.inpatient.controller.TypedOrderController.Template("测试清单",null,List.of(drugId,examId)));
        assertThat(number("SELECT COUNT(*) FROM order_template_item WHERE template_id=?",id(template))).isEqualTo(2);
        typedOrders.disableTemplate(id(template));
        assertThat(number("SELECT COUNT(*) FROM order_template WHERE id=? AND active=TRUE",id(template))).isZero();
    }
    @Test void typedRolesCannotCrossDispenseAdministrationOrPatientScope() throws Exception {
        long a=admission(),o=typedOrder(a,typedDrug(drugId,"TEMPORARY",null)),line=item(o,drugId);
        orders.changeStatus(o,"submit");
        String username="nurse-v8-"+UUID.randomUUID().toString().substring(0,8);
        jdbcTemplate.update("INSERT INTO staff(staff_no,name,department_id,staff_type) VALUES(?,?,?,'NURSE')",username,username,departmentId);
        long nurse=number("SELECT id FROM staff WHERE staff_no=?",username);
        long nurseUser=id(admins.createUser(nurse,username,"测试护士","Verify-Only-123!",List.of("NURSE")));
        context(new CurrentUser(nurseUser,username,List.of("NURSE"),nurse,departmentId));
        assertThat(typedOrders.pending("DISPENSE",null)).isEmpty();
        rejects(() -> typedExecute(line,"DISPENSE"));
        asAdmin(); typedExecute(line,"DISPENSE");
        context(new CurrentUser(nurseUser,username,List.of("NURSE"),nurse,departmentId+999999));
        rejects(() -> typedExecute(line,"ADMINISTER"));
        context(new CurrentUser(nurseUser,username,List.of("NURSE"),nurse,departmentId));
        assertThat(typedOrders.pending("ADMINISTER",null)).extracting(r->((Number)r.get("order_item_id")).longValue()).contains(line);
        typedExecute(line,"ADMINISTER");
        asAdmin();
        String token=(String)auth.login(username,"Verify-Only-123!").get("token");
        mvc.perform(get("/api/orders/tasks").param("phase","ADMINISTER").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        mvc.perform(post("/api/orders").header("Authorization","Bearer "+token).contentType("application/json")
            .content("{\"admissionId\":"+a+",\"orderType\":\"TEMPORARY\",\"items\":[{\"medicalItemId\":"+examId+",\"quantity\":1}]}"))
            .andExpect(status().isForbidden());
        asAdmin();
    }
    @Test void concurrentTypedOrdersHaveUniqueNumericNumbersAndPreserveExistingOnes() throws Exception {
        long a=admission(),b=admit(patient(),bedId+1);
        long legacy=order(a,"TEMPORARY",examId);
        String original=value("SELECT order_no FROM medical_order WHERE id=?",legacy);
        var line=new Requests.OrderLine(examId,null,BigDecimal.ONE,null,"numbering test",null,"TEMPORARY",null,null,null);
        assertThat(race(() -> typedOrder(a,line),() -> typedOrder(b,line))).containsExactly(true,true);
        var rows=jdbcTemplate.queryForList("SELECT id,order_no,created_at FROM medical_order WHERE admission_id IN (?,?)",a,b);
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(r->r.get("order_no")).doesNotHaveDuplicates();
        for(var row:rows) {
            assertThat((String)row.get("order_no")).matches("[0-9]{16,27}")
                .isEqualTo(OrderNumber.format(((java.sql.Timestamp)row.get("created_at")).toInstant(),id(row)));
        }
        assertThat(value("SELECT order_no FROM medical_order WHERE id=?",legacy)).isEqualTo(original);
    }
    @Test void typedConcurrentRequestDoesNotDoubleCharge() throws Exception {
        long a=admission(),o=typedOrder(a,typedDrug(drugId,"LONG_TERM",null)),line=item(o,drugId);
        orders.changeStatus(o,"submit");
        var request=new Requests.OrderExecution(UUID.randomUUID().toString(),line,"DISPENSE",false,null);
        assertThat(race(() -> typedOrders.execute(request),() -> typedOrders.execute(request))).containsExactlyInAnyOrder(true,false);
        assertThat(number("SELECT COUNT(*) FROM charge_detail WHERE admission_id=?",a)).isEqualTo(1);
    }
    @Test void flywayCreatesCourseDesignObjectsAndDemoScale() {
        assertThat(number("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'")).isGreaterThanOrEqualTo(25);
        assertThat(number("SELECT COUNT(*) FROM information_schema.views WHERE table_schema=DATABASE()")).isGreaterThanOrEqualTo(6);
        assertThat(number("SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema=DATABASE() AND routine_type='PROCEDURE'")).isGreaterThanOrEqualTo(5);
        assertThat(number("SELECT COUNT(*) FROM patient")).isGreaterThanOrEqualTo(30);
        assertThat(number("SELECT COUNT(*) FROM execution_record")).isGreaterThanOrEqualTo(60);
        assertThat(number("SELECT COUNT(*) FROM charge_detail")).isGreaterThanOrEqualTo(60);
    }
    @Test void temporaryOrderRequiresEveryItemAndAllowsAdditionalDispenseBatches() {
        long a=admission(), o=order(a,"TEMPORARY",drugId,examId);
        orders.changeStatus(o,"submit");
        long drugLine=item(o,drugId);
        String key=UUID.randomUUID().toString();
        var request=new Requests.OrderExecution(key,drugLine,"DISPENSE",false,"回归执行");
        typedOrders.execute(request);
        assertThat(value("SELECT status FROM medical_order WHERE id=?",o)).isEqualTo("IN_PROGRESS");
        rejects(() -> orders.changeStatus(o,"complete"));
        // The same request number is idempotent, while a new request may record another stock batch.
        rejects(() -> typedOrders.execute(request));
        execute(drugLine);
        execute(item(o,examId));
        assertThat(number("SELECT COUNT(*) FROM charge_detail WHERE admission_id=?",a)).isEqualTo(3);
        assertThat(money("SELECT stock_quantity FROM medical_item WHERE id=?",drugId)).isEqualByComparingTo("98");
    }
    @Test void insufficientStockLeavesNoPartialRecords() {
        long a=admission(),o=order(a,"TEMPORARY",drugId);
        orders.changeStatus(o,"submit");
        long line=item(o,drugId);
        jdbcTemplate.update("UPDATE medical_item SET stock_quantity=0 WHERE id=?",drugId);
        rejects(() -> execute(line));
        assertThat(number("SELECT COUNT(*) FROM execution_record WHERE order_item_id=?",line)).isZero();
        assertThat(number("SELECT COUNT(*) FROM charge_detail WHERE admission_id=?",a)).isZero();
        assertThat(money("SELECT executed_quantity FROM order_item WHERE id=?",line)).isEqualByComparingTo("0");
        assertThat(value("SELECT status FROM medical_order WHERE id=?",o)).isEqualTo("SUBMITTED");
    }
    @Test void reversalRestoresInventoryReopensOrderAndCanCancelNetZero() {
        long a=admission(),o=order(a,"TEMPORARY",drugId);
        orders.changeStatus(o,"submit");
        long execution=execute(item(o,drugId));
        typedOrders.reverse(execution,"mistake");
        rejects(() -> typedOrders.reverse(execution,"again"));
        assertThat(money("SELECT SUM(amount) FROM charge_detail WHERE admission_id=?",a)).isEqualByComparingTo("0");
        assertThat(money("SELECT stock_quantity FROM medical_item WHERE id=?",drugId)).isEqualByComparingTo("100");
        assertThat(money("SELECT SUM(quantity) FROM drug_inventory_transaction WHERE medical_item_id=?",drugId)).isEqualByComparingTo("0");
        assertThat(money("SELECT executed_quantity FROM order_item WHERE id=?",item(o,drugId))).isEqualByComparingTo("0");
        assertThat(number("SELECT COUNT(*) FROM execution_record WHERE reversed_execution_id=?",execution)).isEqualTo(1);
        orders.changeStatus(o,"cancel");
        admissions.requestDischarge(a);
        finance.settle(a);
    }
    @Test void depositRequestAndReversalCannotBeAppliedTwice() {
        long a=admission();
        var request=deposit(a,"SUPPLEMENT","100.00");
        long original=id(finance.addDeposit(request));
        rejects(() -> finance.addDeposit(request));
        long reversal=id(finance.reverseDeposit(original,"wrong receipt"));
        rejects(() -> finance.reverseDeposit(original,"again"));
        rejects(() -> finance.reverseDeposit(reversal,"reverse reversal"));
        rejects(() -> finance.addDeposit(deposit(a,"REFUND","1001.00")));
        assertThat(money("SELECT deposit_balance FROM v_admission_deposit_balance WHERE admission_id=?",a)).isEqualByComparingTo("1000");
        assertThat(number("SELECT COUNT(*) FROM deposit_transaction WHERE admission_id=?",a)).isEqualTo(3);
    }
    @Test void dischargeRequiresClosedOrdersAndSettledLedgerIsImmutable() {
        long a=admission(),o=order(a,"TEMPORARY",examId);
        rejects(() -> admissions.requestDischarge(a));
        rejects(() -> finance.settle(a));
        orders.changeStatus(o,"submit");
        long execution=execute(item(o,examId));
        admissions.requestDischarge(a);
        rejects(() -> order(a,"TEMPORARY",drugId));
        admissions.withdrawDischarge(a);
        assertThat(value("SELECT status FROM admission WHERE id=?",a)).isEqualTo("IN_HOSPITAL");
        admissions.requestDischarge(a);
        var settled=finance.settle(a);
        assertThat((BigDecimal)settled.get("refund_amount")).isEqualByComparingTo("980");
        assertThat(money("SELECT deposit_balance FROM v_admission_deposit_balance WHERE admission_id=?",a)).isEqualByComparingTo("20");
        assertThat(money("SELECT available_balance FROM v_admission_deposit_balance WHERE admission_id=?",a)).isEqualByComparingTo("0");
        assertThat(value("SELECT status FROM bed WHERE id=?",bedId)).isEqualTo("AVAILABLE");
        assertThat(number("SELECT COUNT(*) FROM bed_usage WHERE admission_id=? AND end_time IS NULL",a)).isZero();
        long initial=number("SELECT id FROM deposit_transaction WHERE admission_id=? AND transaction_type='INITIAL'",a);
        rejects(() -> finance.addDeposit(deposit(a,"SUPPLEMENT","10.00")));
        rejects(() -> finance.reverseDeposit(initial,"closed"));
        rejects(() -> typedOrders.reverse(execution,"closed"));
        rejects(() -> finance.settle(a));
        rejects(() -> admissions.withdrawDischarge(a));
        rejects(() -> jdbcTemplate.update("UPDATE charge_detail SET note='changed' WHERE admission_id=?",a));
        rejects(() -> jdbcTemplate.update("DELETE FROM settlement WHERE admission_id=?",a));
        rejects(() -> jdbcTemplate.update("INSERT INTO deposit_transaction(admission_id,transaction_no,transaction_type,amount,payment_method) VALUES(?,?,'SUPPLEMENT',10,'CASH')",a,UUID.randomUUID().toString()));
    }
    @Test void crossDepartmentTransferRequiresDoctorAndPreservesRevenueAttribution() {
        long a=admission(),o=order(a,"TEMPORARY",examId);
        long oldDept=departmentId,oldBed=bedId;
        // Build a second independent ward and keep the existing account.
        fixture();
        long targetDept=departmentId,targetBed=bedId,targetDoctor=doctorId;
        rejects(() -> admissions.transfer(a,new Requests.TransferCreate(targetBed,targetDoctor,"unfinished")));
        orders.changeStatus(o,"submit");
        long originalItem=number("SELECT id FROM order_item WHERE medical_order_id=?",o);
        execute(originalItem);
        rejects(() -> admissions.transfer(a,new Requests.TransferCreate(targetBed,null,"no doctor")));
        assertThat(number("SELECT current_bed_id FROM admission WHERE id=?",a)).isEqualTo(oldBed);
        admissions.transfer(a,new Requests.TransferCreate(targetBed,targetDoctor,"transfer"));
        assertThat(number("SELECT department_id FROM admission WHERE id=?",a)).isEqualTo(targetDept);
        assertThat(number("SELECT attending_doctor_id FROM admission WHERE id=?",a)).isEqualTo(targetDoctor);
        assertThat(number("SELECT department_id FROM charge_detail WHERE admission_id=?",a)).isEqualTo(oldDept);
        assertThat(number("SELECT COUNT(*) FROM bed_usage WHERE admission_id=?",a)).isEqualTo(2);
        assertThat(value("SELECT status FROM bed WHERE id=?",oldBed)).isEqualTo("AVAILABLE");
        assertThat(number("SELECT COUNT(*) FROM transfer_record WHERE admission_id=? AND from_department_id=? AND to_department_id=?",a,oldDept,targetDept)).isEqualTo(1);
    }
    @Test void activePatientCannotBeDisabledAndForeignDoctorCannotRead() {
        long p=patient(),a=admit(p,bedId);
        rejects(() -> patients.update(p,new Requests.PatientUpdate("name","OTHER",LocalDate.of(1990,1,1),null,null,null,null,false)));
        context(new CurrentUser(adminId,"scoped-doctor",List.of("DOCTOR"),-1L,departmentId));
        rejects(() -> admissions.detail(a));
        rejects(() -> patients.detail(p));
        rejects(() -> finance.precheck(a));
        rejects(() -> typedOrders.history(a));
        assertThat(admissions.list("ALL",null,null,1,20).total()).isZero();
        asAdmin();
        assertThat(admissions.detail(a)).containsEntry("id",a);
    }

    @Test void allOriginalChargesAreReversedOnceIncludingLegacySplitCharges() {
        long a=admission(),o=order(a,"TEMPORARY",drugId);
        orders.changeStatus(o,"submit"); long e=execute(item(o,drugId));
        String code=value("SELECT item_code FROM medical_item WHERE id=?",drugId);
        String name=value("SELECT name FROM medical_item WHERE id=?",drugId);
        jdbcTemplate.update("INSERT INTO charge_detail(admission_id,execution_id,medical_item_id,item_code_snapshot,item_name_snapshot,unit_snapshot,department_id,charge_no,charge_type,item_type_snapshot,unit_price_snapshot,quantity,amount) VALUES(?,?,?,?,?,?,?,?,'NORMAL','DRUG',10,1,10)",
            a,e,drugId,code,name,"box",departmentId,"C"+UUID.randomUUID().toString().substring(0,25));
        typedOrders.reverse(e,"split charge reversal");
        assertThat(number("SELECT COUNT(*) FROM charge_detail WHERE admission_id=? AND charge_type='REVERSAL'",a)).isEqualTo(2);
        assertThat(money("SELECT total_charge FROM v_admission_deposit_balance WHERE admission_id=?",a)).isEqualByComparingTo("0");
        assertThat(money("SELECT stock_quantity FROM medical_item WHERE id=?",drugId)).isEqualByComparingTo("100");
    }

    @Test void alertTracksBalanceAndCannotBeDismissedWhileStillLow() {
        long a=id(admissions.admit(new Requests.AdmissionCreate(patient(),departmentId,doctorId,bedId,BigDecimal.ZERO)));
        long o=order(a,"TEMPORARY",examId);
        orders.changeStatus(o,"submit");
        execute(item(o,examId));
        long alert=number("SELECT id FROM account_alert WHERE admission_id=? AND status='OPEN'",a);
        rejects(() -> finance.resolveAlert(alert));
        finance.addDeposit(deposit(a,"SUPPLEMENT","250.00"));
        assertThat(value("SELECT status FROM account_alert WHERE id=?",alert)).isEqualTo("RESOLVED");
        jdbcTemplate.update("UPDATE medical_item SET unit_price=250 WHERE id=?",examId);
        long debtOrder=order(a,"TEMPORARY",examId);
        orders.changeStatus(debtOrder,"submit");
        execute(item(debtOrder,examId));
        assertThat(number("SELECT COUNT(*) FROM account_alert WHERE admission_id=? AND status='OPEN'",a)).isEqualTo(1);
        assertThat(money("SELECT balance_snapshot FROM account_alert WHERE admission_id=? AND status='OPEN'",a)).isEqualByComparingTo("-20");
    }

    @Test void sameBedTransferAndNegativeDepositReversalRollbackCompletely() {
        long a=admission();
        rejects(() -> admissions.transfer(a,new Requests.TransferCreate(bedId,doctorId,"same bed")));
        assertThat(number("SELECT COUNT(*) FROM transfer_record WHERE admission_id=?",a)).isZero();
        assertThat(number("SELECT COUNT(*) FROM bed_usage WHERE admission_id=? AND end_time IS NULL",a)).isEqualTo(1);
        long initial=number("SELECT id FROM deposit_transaction WHERE admission_id=?",a);
        finance.addDeposit(deposit(a,"REFUND","900.00"));
        rejects(() -> finance.reverseDeposit(initial,"would become negative"));
        assertThat(money("SELECT deposit_balance FROM v_admission_deposit_balance WHERE admission_id=?",a)).isEqualByComparingTo("100");
    }

    @Test void nurseScopeAndDrugAuthorityAreEnforcedAtHttpBoundary() throws Exception {
        long a=admission(),o=order(a,"TEMPORARY",drugId);
        orders.changeStatus(o,"submit");
        String username="nurse-"+UUID.randomUUID().toString().substring(0,10);
        jdbcTemplate.update("INSERT INTO staff(staff_no,name,department_id,staff_type) VALUES(?,? ,?,'NURSE')",username,username,departmentId);
        long staff=number("SELECT id FROM staff WHERE staff_no=?",username);
        admins.createUser(staff,username,"验证护士","Verify-Only-123!",List.of("NURSE"));
        String token=(String)auth.login(username,"Verify-Only-123!").get("token");
        mvc.perform(get("/api/admissions/"+a).header("Authorization","Bearer "+token)).andExpect(status().isOk());
        mvc.perform(get("/api/orders/tasks").param("phase","DISPENSE").param("itemType","DRUG").header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(post("/api/orders/executions").header("Authorization","Bearer "+token).contentType("application/json")
            .content("{\"requestNo\":\""+UUID.randomUUID()+"\",\"orderItemId\":"+item(o,drugId)+",\"phase\":\"DISPENSE\",\"notDone\":false}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/deposits").header("Authorization","Bearer "+token).contentType("application/json")
            .content("{\"admissionId\":"+a+",\"type\":\"SUPPLEMENT\",\"amount\":1,\"paymentMethod\":\"CASH\",\"requestNo\":\""+UUID.randomUUID()+"\"}"))
            .andExpect(status().isForbidden());
        assertThat(number("SELECT COUNT(*) FROM charge_detail WHERE admission_id=?",a)).isZero();
        asAdmin();
    }

    @Test void invalidHttpInputIsRejectedBeforeAnyLedgerWriteAndFailuresAreAudited() throws Exception {
        long a=admission();
        int version=(int)number("SELECT token_version FROM user_account WHERE id=?",adminId);
        String token=jwt.createToken(adminId,"admin",List.of("ADMIN"),List.of(),version);
        mvc.perform(get("/api/statistics/overview").param("from","2026-08-29").param("to","2026-08-28")
            .header("Authorization","Bearer "+token)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/deposits").header("Authorization","Bearer "+token).contentType("application/json")
            .content("{\"admissionId\":"+a+",\"type\":\"SUPPLEMENT\",\"amount\":-10,\"paymentMethod\":\"CASH\",\"requestNo\":\""+UUID.randomUUID()+"\"}"))
            .andExpect(status().isBadRequest());
        long before=number("SELECT COUNT(*) FROM audit_log WHERE result='FAILURE'");
        mvc.perform(post("/api/admissions/"+a+"/transfer").header("Authorization","Bearer "+token).contentType("application/json")
            .content("{\"targetBedId\":"+bedId+",\"reason\":\"same bed\"}")).andExpect(status().isBadRequest());
        assertThat(number("SELECT COUNT(*) FROM audit_log WHERE result='FAILURE'")).isGreaterThan(before);
        assertThat(number("SELECT COUNT(*) FROM deposit_transaction WHERE admission_id=?",a)).isEqualTo(1);
        asAdmin();
    }

    @Test void bedUtilizationExcludesMaintenanceAndDrugUsageNetsReturns() {
        long a=admission(),o=order(a,"TEMPORARY",drugId);
        orders.changeStatus(o,"submit"); long e=execute(item(o,drugId)); typedOrders.reverse(e,"return");
        jdbcTemplate.update("UPDATE bed SET status='MAINTENANCE' WHERE id=?",bedId+1);
        long ward=number("SELECT r.ward_id FROM bed b JOIN room r ON r.id=b.room_id WHERE b.id=?",bedId);
        var row=statistics.bedUtilization().stream().filter(x -> ((Number)x.get("ward_id")).longValue()==ward).findFirst().orElseThrow();
        assertThat((BigDecimal)row.get("utilization_rate")).isEqualByComparingTo("100");
        var drugs=statistics.drugUsage(LocalDate.now(java.time.ZoneOffset.UTC),LocalDate.now(java.time.ZoneOffset.UTC),10000);
        var drug=drugs.stream().filter(x -> ((Number)x.get("medical_item_id")).longValue()==drugId).findFirst().orElseThrow();
        assertThat((BigDecimal)drug.get("used_quantity")).isEqualByComparingTo("0");
    }
    List<Boolean> race(Runnable first,Runnable second) throws Exception {
        var pool=Executors.newFixedThreadPool(2);
        var ready=new CountDownLatch(2); var start=new CountDownLatch(1);
        try {
            var tasks=List.of(first,second).stream().map(action -> pool.submit(() -> {
                asAdmin(); ready.countDown();
                try {
                    if (!start.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                    action.run(); return true;
                } catch (BusinessException | DataAccessException conflict) { return false; }
                finally { clear(); }
            })).toList();
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); start.countDown();
            return List.of(tasks.get(0).get(30,TimeUnit.SECONDS),tasks.get(1).get(30,TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
    @Test void concurrentAdmissionOfSamePatientUsesOnlyOneBed() throws Exception {
        long p=patient(),otherBed=bedId+1;
        assertThat(race(() -> admit(p,bedId),() -> admit(p,otherBed))).containsExactlyInAnyOrder(true,false);
        assertThat(number("SELECT COUNT(*) FROM admission WHERE patient_id=?",p)).isEqualTo(1);
        assertThat(number("SELECT COUNT(*) FROM bed WHERE id IN (?,?) AND status='OCCUPIED'",bedId,otherBed)).isEqualTo(1);
    }
    @Test void concurrentAdmissionToSameBedAdmitsOnlyOnePatient() throws Exception {
        long p1=patient(),p2=patient();
        assertThat(race(() -> admit(p1,bedId),() -> admit(p2,bedId))).containsExactlyInAnyOrder(true,false);
        assertThat(number("SELECT COUNT(*) FROM admission WHERE patient_id IN (?,?)",p1,p2)).isEqualTo(1);
        assertThat(number("SELECT COUNT(*) FROM bed_usage WHERE bed_id=? AND end_time IS NULL",bedId)).isEqualTo(1);
    }
    @Test void concurrentExecutionAndReversalEachHaveOneWinner() throws Exception {
        long a=admission(),o=order(a,"TEMPORARY",drugId);
        orders.changeStatus(o,"submit"); long line=item(o,drugId);
        assertThat(race(() -> execute(line),() -> execute(line))).containsExactlyInAnyOrder(true,false);
        long e=number("SELECT id FROM execution_record WHERE order_item_id=? AND status='SUCCESS'",line);
        assertThat(race(() -> typedOrders.reverse(e,"one"),() -> typedOrders.reverse(e,"two"))).containsExactlyInAnyOrder(true,false);
        assertThat(money("SELECT stock_quantity FROM medical_item WHERE id=?",drugId)).isEqualByComparingTo("100");
        assertThat(money("SELECT total_charge FROM v_admission_deposit_balance WHERE admission_id=?",a)).isEqualByComparingTo("0");
    }
    @Test void concurrentDepositReversalAndSettlementSerializeLedger() throws Exception {
        long a=admission(),d=id(finance.addDeposit(deposit(a,"SUPPLEMENT","100.00")));
        assertThat(race(() -> finance.reverseDeposit(d,"one"),() -> finance.reverseDeposit(d,"two"))).containsExactlyInAnyOrder(true,false);
        admissions.requestDischarge(a);
        var result=race(() -> finance.settle(a),() -> finance.addDeposit(deposit(a,"SUPPLEMENT","30.00")));
        assertThat(result.get(0)).isTrue();
        assertThat(value("SELECT status FROM admission WHERE id=?",a)).isEqualTo("DISCHARGED");
        assertThat(money("SELECT available_balance FROM v_admission_deposit_balance WHERE admission_id=?",a)).isEqualByComparingTo("0");
        assertThat(money("SELECT refund_amount FROM settlement WHERE admission_id=?",a))
            .isEqualByComparingTo(result.get(1) ? "1030" : "1000");
    }
    @Test void disabledAndPasswordChangedAccountsRejectOldTokensAndReloadPermissions() throws Exception {
        String username="verify-"+UUID.randomUUID().toString().substring(0,12);
        String password="Verify-Only-123!";
        long user=id(admins.createUser(doctorId,username,"验证医生",password,List.of("DOCTOR")));
        String token=(String)auth.login(username,password).get("token");
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.roles[0]").value("DOCTOR"));
        asAdmin(); admins.setEnabled(user,false);
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        asAdmin(); admins.setEnabled(user,true);
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        String fresh=(String)auth.login(username,password).get("token");
        mvc.perform(get("/api/admin/users").header("Authorization","Bearer "+fresh)).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/password").header("Authorization","Bearer "+fresh).contentType("application/json")
            .content("{\"oldPassword\":\"Verify-Only-123!\",\"newPassword\":\"New-Verify-123!\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+fresh)).andExpect(status().isUnauthorized());
        String renewed=(String)auth.login(username,"New-Verify-123!").get("token");
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+renewed)).andExpect(status().isOk());
        asAdmin();
        assertThat(number("SELECT COUNT(*) FROM audit_log WHERE object_type='user_account' AND object_id=? AND action='PASSWORD_CHANGE'",String.valueOf(user))).isEqualTo(1);
    }

    @Test void nursingObservationIsScopedIdempotentAndAppendOnly() {
        long admission = admission();
        var observedAt = java.time.Instant.now().minusSeconds(1);
        String requestNo = "OBS-" + UUID.randomUUID();
        var request = new Requests.NursingObservationCreate(requestNo, observedAt,
            new BigDecimal("36.8"), 72, 18, 120, 80, new BigDecimal("98.00"), "CLEAR", "夜间观察");

        Map<String,Object> created = nursingObservations.create(admission, request);
        assertThat(created).containsEntry("request_no", requestNo).containsEntry("recorded_by_name", "系统管理员");
        assertThat(nursingObservations.list(admission)).hasSize(1);

        var incompletePressure = new Requests.NursingObservationCreate("OBS-" + UUID.randomUUID(),
            observedAt.plusMillis(1), null, null, null, 120, null, null, null, "血压必须成对");
        assertThatThrownBy(() -> nursingObservations.create(admission, incompletePressure))
            .isInstanceOf(BusinessException.class)
            .hasMessage("收缩压和舒张压必须同时填写");

        var duplicateRequest = new Requests.NursingObservationCreate("OBS-" + UUID.randomUUID(), observedAt,
            new BigDecimal("36.9"), null, null, null, null, null, "CLEAR", "重复时间应被拒绝");
        assertThatThrownBy(() -> nursingObservations.create(admission, duplicateRequest))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE nursing_observation SET note='篡改' WHERE id=?", id(created)))
            .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM nursing_observation WHERE id=?", id(created)))
            .isInstanceOf(DataAccessException.class);
    }

    @Test void inpatientResultIsBoundToExecutionAndCorrectionsAreAppended() {
        long admission = admission();
        long order = order(admission, "TEMPORARY", examId);
        orders.changeStatus(order, "submit");
        long item = item(order, examId);
        long execution = execute(item);
        var reportedAt = java.time.Instant.now().minusSeconds(1);
        var request = new Requests.InpatientResultCreate("RES-" + UUID.randomUUID(), execution,
            "WBC", "白细胞", new BigDecimal("5.200000"), null, "10^9/L", "4.0-10.0",
            "NORMAL", reportedAt, null, null);

        Map<String,Object> first = inpatientResults.create(admission, request);
        assertThat(first).containsEntry("result_status", "FINAL").containsEntry("analyte_code", "WBC");
        assertThat(inpatientResults.list(admission)).hasSize(1);

        var duplicate = new Requests.InpatientResultCreate("RES-" + UUID.randomUUID(), execution,
            "WBC", "白细胞", new BigDecimal("5.300000"), null, "10^9/L", "4.0-10.0",
            "NORMAL", reportedAt.plusMillis(1), null, null);
        assertThatThrownBy(() -> inpatientResults.create(admission, duplicate))
            .isInstanceOf(BusinessException.class);

        var amendment = new Requests.InpatientResultCreate("RES-" + UUID.randomUUID(), execution,
            "WBC", "白细胞", new BigDecimal("5.300000"), null, "10^9/L", "4.0-10.0",
            "NORMAL", java.time.Instant.now().minusMillis(200), id(first), "修正录入错误");
        Map<String,Object> amended = inpatientResults.create(admission, amendment);
        assertThat(amended).containsEntry("result_status", "AMENDED")
            .containsEntry("amends_result_id", id(first));
        assertThat(inpatientResults.list(admission)).hasSize(2);
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE inpatient_result SET result_text='篡改' WHERE id=?", id(first)))
            .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM inpatient_result WHERE id=?", id(first)))
            .isInstanceOf(DataAccessException.class);
    }

    @Test void inpatientDiagnosisAndClinicalNotesKeepSnapshotsAndSignedHistory() {
        long admission = admission();
        long diagnosisCatalog = number("SELECT id FROM diagnosis_catalog WHERE code='J18.9'");
        var diagnosedAt = java.time.Instant.now().minusSeconds(1);
        var diagnosis = new Requests.InpatientDiagnosisCreate("DX-" + UUID.randomUUID(), diagnosisCatalog,
            "PRIMARY", true, diagnosedAt, doctorId, "入院时初步诊断");

        Map<String,Object> createdDiagnosis = clinicalRecords.createDiagnosis(admission, diagnosis);
        assertThat(createdDiagnosis).containsEntry("diagnosis_type", "PRIMARY")
            .containsEntry("clinical_status", "ACTIVE")
            .containsEntry("diagnosis_name_snapshot", "肺炎，未特指");
        assertThat(clinicalRecords.listDiagnoses(admission)).hasSize(1);

        var resolvedAt = java.time.Instant.now().minusMillis(200);
        Map<String,Object> resolved = clinicalRecords.resolveDiagnosis(admission, id(createdDiagnosis),
            new Requests.InpatientDiagnosisResolve("RESOLVED", resolvedAt, "症状缓解"));
        assertThat(resolved).containsEntry("clinical_status", "RESOLVED")
            .containsEntry("resolution_note", "症状缓解");
        assertThatThrownBy(() -> clinicalRecords.resolveDiagnosis(admission, id(createdDiagnosis),
            new Requests.InpatientDiagnosisResolve("RULED_OUT", java.time.Instant.now().minusMillis(100), "重复关闭")))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM inpatient_diagnosis WHERE id=?", id(createdDiagnosis)))
            .isInstanceOf(DataAccessException.class);

        var signedAt = java.time.Instant.now().minusMillis(100);
        var note = new Requests.InpatientClinicalNoteCreate("NOTE-" + UUID.randomUUID(), "PROGRESS",
            "入院后首次病程记录", "患者一般情况稳定，继续观察治疗反应。", doctorId,
            signedAt.minusSeconds(5), signedAt, null, null);
        Map<String,Object> createdNote = clinicalRecords.createNote(admission, note);
        assertThat(createdNote).containsEntry("note_status", "FINAL")
            .containsEntry("note_type", "PROGRESS");

        var correction = new Requests.InpatientClinicalNoteCreate("NOTE-" + UUID.randomUUID(), "PROGRESS",
            "入院后首次病程记录（更正）", "更正：患者一般情况稳定，继续观察治疗反应。", doctorId,
            signedAt, java.time.Instant.now().minusMillis(50), id(createdNote), "修正文字录入错误");
        Map<String,Object> amendedNote = clinicalRecords.createNote(admission, correction);
        assertThat(amendedNote).containsEntry("note_status", "AMENDED")
            .containsEntry("amends_note_id", id(createdNote));
        assertThat(clinicalRecords.listNotes(admission)).hasSize(2);
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE inpatient_clinical_note SET content='篡改' WHERE id=?", id(createdNote)))
            .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM inpatient_clinical_note WHERE id=?", id(createdNote)))
            .isInstanceOf(DataAccessException.class);
    }
}
