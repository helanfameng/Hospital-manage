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
    }
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PatientService patients;
    @Autowired AdmissionService admissions;
    @Autowired OrderService orders;
    @Autowired TypedOrderService typedOrders;
    @Autowired InventoryService inventory;
    @Autowired FinancialService finance;
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
        jdbcTemplate.update("INSERT INTO medical_item(item_code,name,item_type,unit,unit_price,stock_quantity) VALUES(?,?,'DRUG','box',10,100)",tag,tag);
        drugId=number("SELECT id FROM medical_item WHERE item_code=?",tag);
        jdbcTemplate.update("UPDATE medical_item SET inventory_managed=TRUE WHERE id=?",drugId);
        jdbcTemplate.update("INSERT INTO drug_profile(medical_item_id,dose_unit,dose_units_per_stock,allowed_routes) VALUES(?,'片',10,'口服,静脉滴注')",drugId);
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
        var profile=jdbcTemplate.queryForList("SELECT dose_unit,allowed_routes FROM drug_profile WHERE medical_item_id=?",item);
        if(profile.isEmpty()) return new Requests.OrderLine(item,null,BigDecimal.ONE,null,"回归用例",null,type,null,null,null);
        String unit=(String)profile.get(0).get("dose_unit"), route=((String)profile.get(0).get("allowed_routes")).split(",")[0];
        return new Requests.OrderLine(item,BigDecimal.ONE,BigDecimal.ONE,route,"回归用例",unit,type,null,null,null);
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

    Requests.OrderLine typedDrug(long drug,String type,String ignoredGrouping) {
        return new Requests.OrderLine(drug,new BigDecimal("2"),BigDecimal.ONE,ignoredGrouping==null?"口服":"静脉滴注","测试指令","片",type,null,null,ignoredGrouping);
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

    @Test void chargesExposeSourceOrdersAndNewNumbersWithoutChangingHistory() {
        long a=admission(),o=typedOrder(a,typedDrug(drugId,"TEMPORARY",null)),line=item(o,drugId);
        orders.changeStatus(o,"submit");
        long execution=typedExecute(line,"DISPENSE");
        String number=value("SELECT charge_no FROM charge_detail WHERE execution_id=?",execution);
        assertThat(number).matches("C[0-9a-f]{30}");
        var normal=financialMapper.charges(a).get(0);
        assertThat(((Number)normal.get("order_id")).longValue()).isEqualTo(o);
        assertThat(normal.get("order_no")).isEqualTo(value("SELECT order_no FROM medical_order WHERE id=?",o));
        typedOrders.reverse(execution,"numbering regression");
        var charges=financialMapper.charges(a);
        assertThat(charges).hasSize(2);
        assertThat(charges.get(0).get("charge_no").toString()).matches("CR[0-9a-f]{30}");
        assertThat(charges).allSatisfy(c -> assertThat(c.get("order_no")).isEqualTo(normal.get("order_no")));
        assertThat(value("SELECT charge_no FROM charge_detail WHERE id=?",normal.get("id"))).isEqualTo(number);
        assertThat(money("SELECT SUM(amount) FROM charge_detail WHERE admission_id=?",a)).isEqualByComparingTo("0");
        // No execution source: retain the charge instead of dropping it through an inner join.
        jdbcTemplate.update("INSERT INTO charge_detail(admission_id,medical_item_id,charge_no,charge_type,unit_price_snapshot,quantity,amount) VALUES(?,?,?,'NORMAL',20,1,20)",a,examId,"OLD"+UUID.randomUUID().toString().replace("-","").substring(0,20));
        assertThat(financialMapper.charges(a)).hasSize(3).anySatisfy(c -> assertThat(c.get("order_no")).isNull());
    }

    @Test void chargeSequenceIsUniqueAcrossConcurrentAllocations() throws Exception {
        var allocated=new java.util.concurrent.ConcurrentLinkedQueue<String>();
        assertThat(race(() -> allocated.add(ChargeNumber.next(false)),
            () -> allocated.add(ChargeNumber.next(false)))).containsExactly(true,true);
        assertThat(allocated).hasSize(2).doesNotHaveDuplicates();
        assertThat(allocated).allSatisfy(n -> assertThat(n).matches("C[0-9a-f]{30}"));
    }

    @Test void typedMedicationSeparatesDispensingAdministrationAndCharges() {
        long a=admission(),o=typedOrder(a,typedDrug(drugId,"TEMPORARY",null)),line=item(o,drugId);
        assertThat(orders.detail(o)).doesNotContainKey("frequency");
        assertThat(number("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='medical_order' AND column_name='frequency'")).isZero();
        assertThat(number("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='order_item' AND column_name='administered_quantity'")).isZero();
        orders.changeStatus(o,"submit");
        rejects(() -> typedExecute(line,"ADMINISTER"));
        long dispense=typedExecute(line,"DISPENSE");
        assertThat(value("SELECT status FROM medical_order WHERE id=?",o)).isEqualTo("IN_PROGRESS");
        assertThat(number("SELECT COUNT(*) FROM charge_detail WHERE admission_id=?",a)).isEqualTo(1);
        assertThat(money("SELECT stock_quantity FROM medical_item WHERE id=?",drugId)).isEqualByComparingTo("99");
        long administer=typedExecute(line,"ADMINISTER");
        assertThat(value("SELECT status FROM medical_order WHERE id=?",o)).isEqualTo("COMPLETED");
        assertThat(number("SELECT COUNT(*) FROM charge_detail WHERE admission_id=?",a)).isEqualTo(1);
        assertThat(money("SELECT executed_quantity FROM order_item WHERE id=?",line)).isEqualByComparingTo("2");
        rejects(() -> typedExecute(line,"ADMINISTER"));
        rejects(() -> typedOrders.reverse(dispense,"used drug"));
        typedOrders.reverse(administer,"错误给药记录纠正，仅测试");
        typedOrders.reverse(dispense,"未使用整笔退药");
        assertThat(money("SELECT stock_quantity FROM medical_item WHERE id=?",drugId)).isEqualByComparingTo("100");
        assertThat(money("SELECT total_charge FROM v_admission_deposit_balance WHERE admission_id=?",a)).isEqualByComparingTo("0");
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
        long drugOrder=typedOrder(a,new Requests.OrderLine(drugId,BigDecimal.ONE,new BigDecimal("2"),"口服",null,"片","TEMPORARY",null,null));
        assertThat(money("SELECT requested_dispense_quantity FROM order_item WHERE medical_order_id=?",drugOrder)).isEqualByComparingTo("2");
        long otherUnit=typedOrder(a,new Requests.OrderLine(examId,null,new BigDecimal("2"),null,null,null,"TEMPORARY",null,null,null));
        assertThat(money("SELECT execution_quantity_per_operation FROM order_item WHERE medical_order_id=?",otherUnit)).isEqualByComparingTo("2");
    }
    Requests.OrderLine plannedDrug(long drug,int count,String quantity,String ignoredGrouping,String reason) {
        return new Requests.OrderLine(drug,BigDecimal.TEN,new BigDecimal(quantity),ignoredGrouping==null?"口服":"静脉滴注",null,"片","TEMPORARY",null,null,ignoredGrouping,count,reason);
    }
    @SuppressWarnings("unchecked")
    Map<String,Object> detailLine(long order,long line) {
        return ((List<Map<String,Object>>)orders.detail(order).get("items")).stream().filter(r->id(r)==line).findFirst().orElseThrow();
    }
    @Test void temporaryMedicationHasOneTaskAndReversalReopensIt() {
        long a=admission(),o=typedOrder(a,typedDrug(drugId,"TEMPORARY",null)),line=item(o,drugId);
        orders.changeStatus(o,"submit"); long dispense=typedExecute(line,"DISPENSE");
        long administration=typedExecute(line,"ADMINISTER");
        assertThat(value("SELECT item_status FROM order_item WHERE id=?",line)).isEqualTo("COMPLETED");
        assertThat(((Number)detailLine(o,line).get("administration_count")).intValue()).isEqualTo(1);
        assertThat(typedOrders.pending("ADMINISTER",null)).noneMatch(r->((Number)r.get("order_item_id")).longValue()==line);
        rejects(()->typedOrders.reverse(dispense,"不能整笔退回已用药品"));
        rejects(()->typedExecute(line,"ADMINISTER"));
        typedOrders.reverse(administration,"错误给药记录纠正");
        assertThat(value("SELECT status FROM medical_order WHERE id=?",o)).isEqualTo("SUBMITTED");
        assertThat(((Number)detailLine(o,line).get("administration_count")).intValue()).isZero();
        typedExecute(line,"ADMINISTER");
        assertThat(value("SELECT status FROM medical_order WHERE id=?",o)).isEqualTo("COMPLETED");
        assertThat(money("SELECT executed_quantity FROM order_item WHERE id=?",line)).isEqualByComparingTo("2");
        assertThat(number("SELECT COUNT(*) FROM charge_detail WHERE admission_id=?",a)).isEqualTo(1);
    }
    @Test void medicationTimingAndActualQuantityVarianceAreValidated() {
        long a=admission();
        rejects(()->typedOrder(a,new Requests.OrderLine(drugId,BigDecimal.ONE,BigDecimal.ONE,null,"口服",null,"片","LONG_TERM","SCHEDULED",null,null,null,null)));
        rejects(()->typedOrder(a,new Requests.OrderLine(drugId,BigDecimal.ONE,BigDecimal.ONE,null,"口服",null,"片","LONG_TERM","PRN",60,null,null,null)));
        long o=typedOrder(a,typedDrug(drugId,"TEMPORARY",null)),line=item(o,drugId);
        orders.changeStatus(o,"submit");
        rejects(()->typedOrders.execute(new Requests.OrderExecution(UUID.randomUUID().toString(),line,null,"DISPENSE",new BigDecimal("2"),null,false,null)));
        typedOrders.execute(new Requests.OrderExecution(UUID.randomUUID().toString(),line,null,"DISPENSE",new BigDecimal("2"),"按临床需要增加",false,null));
        assertThat(money("SELECT dispensed_quantity FROM order_item WHERE id=?",line)).isEqualByComparingTo("2");
    }
    @Test void lateScheduledMedicationNeedsReasonAndSkipsBacklog() {
        long a=admission();
        long o=id(orders.create(new Requests.OrderCreate(a,"迟发排程",List.of(new Requests.OrderLine(
                drugId,BigDecimal.ONE,BigDecimal.ONE,null,"口服","迟发测试","片","LONG_TERM","SCHEDULED",60,null,null,null)))));
        long line=item(o,drugId); orders.changeStatus(o,"submit"); typedExecute(line,"DISPENSE");
        jdbcTemplate.update("UPDATE medication_schedule SET scheduled_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 2 HOUR) WHERE order_item_id=?",line);
        rejects(()->typedOrders.execute(new Requests.OrderExecution(UUID.randomUUID().toString(),line,null,"ADMINISTER",BigDecimal.ONE,null,false,null)));
        typedOrders.execute(new Requests.OrderExecution(UUID.randomUUID().toString(),line,null,"ADMINISTER",BigDecimal.ONE,null,false,"病区交接延误"));
        java.sql.Timestamp next=jdbcTemplate.queryForObject("SELECT scheduled_at FROM medication_schedule WHERE order_item_id=? AND status='PENDING'",java.sql.Timestamp.class,line);
        assertThat(next.toInstant()).isAfterOrEqualTo(java.time.Instant.now().plusSeconds(55*60));
    }
    @Test void futureTaskPreviewExpandsScheduledTimesButCannotExecuteThem() {
        long a=admission();
        long o=id(orders.create(new Requests.OrderCreate(a,"未来预览",List.of(new Requests.OrderLine(
                drugId,BigDecimal.ONE,BigDecimal.ONE,null,"口服","预览测试","片","LONG_TERM","SCHEDULED",60,null,null,null)))));
        orders.changeStatus(o,"submit"); long line=item(o,drugId);
        var preview=typedOrders.tasks("ADMINISTER",null,"FUTURE",2,null,"SCHEDULED",null,null,null,null,null,"PLANNED_AT_ASC",1,20);
        assertThat(preview.items()).anySatisfy(row -> {
            assertThat(((Number)row.get("order_item_id")).longValue()).isEqualTo(line);
            assertThat(row.get("preview_only")).isEqualTo(true);
        });
    }
    @Test void infusionPlansAdvanceAndReverseIndependently() {
        long a=admission(); String code="GP"+UUID.randomUUID().toString().substring(0,20);
        jdbcTemplate.update("INSERT INTO medical_item(item_code,name,item_type,unit,unit_price,stock_quantity,inventory_managed) VALUES(?,?,'DRUG','box',30,10,TRUE)",code,code);
        long other=number("SELECT id FROM medical_item WHERE item_code=?",code);
        jdbcTemplate.update("INSERT INTO drug_profile(medical_item_id,dose_unit,dose_units_per_stock,allowed_routes) VALUES(?,'片',10,'静脉滴注')",other);
        long o=typedOrder(a,typedDrug(drugId,"TEMPORARY","组"),typedDrug(other,"TEMPORARY","组")),line=item(o,drugId);
        orders.changeStatus(o,"submit"); typedExecute(line,"DISPENSE"); typedExecute(line,"ADMINISTER");
        assertThat(number("SELECT COUNT(*) FROM order_item WHERE medical_order_id=? AND item_status='COMPLETED' AND executed_quantity=2",o)).isEqualTo(1);
        assertThat(((Number)detailLine(o,item(o,other)).get("administration_count")).intValue()).isZero();
    }
    @Test void concurrentFinalDoseCannotExceedPlan() throws Exception {
        long a=admission(),o=typedOrder(a,typedDrug(drugId,"TEMPORARY",null)),line=item(o,drugId);
        orders.changeStatus(o,"submit"); typedExecute(line,"DISPENSE");
        assertThat(race(()->typedExecute(line,"ADMINISTER"),()->typedExecute(line,"ADMINISTER"))).containsExactlyInAnyOrder(true,false);
        assertThat(((Number)detailLine(o,line).get("administration_count")).intValue()).isEqualTo(1);
        assertThat(value("SELECT status FROM medical_order WHERE id=?",o)).isEqualTo("COMPLETED");
        // The plan is complete, so leftover stock does not justify another dose.
        rejects(()->typedExecute(line,"ADMINISTER"));
    }
    @Test void typedValidationAndPriceSnapshots() {
        long a=admission();
        rejects(() -> typedOrder(a,new Requests.OrderLine(drugId,BigDecimal.ONE,BigDecimal.ONE,"口服",null,"mL","TEMPORARY",null,null,null)));
        rejects(() -> typedOrder(a,new Requests.OrderLine(drugId,BigDecimal.ONE,new BigDecimal("1.001"),"口服",null,"片","TEMPORARY",null,null,null)));
        long bedFee=number("SELECT MIN(id) FROM medical_item WHERE item_type='BED_FEE'");
        rejects(() -> typedOrder(a,new Requests.OrderLine(bedFee,null,BigDecimal.ONE,null,null,null,"TEMPORARY",null,null,null)));
        long o=typedOrder(a,new Requests.OrderLine(examId,null,BigDecimal.ONE,null,"检查",null,"TEMPORARY",null,null,null));
        jdbcTemplate.update("UPDATE medical_item SET unit_price=999 WHERE id=?",examId);
        orders.changeStatus(o,"submit"); typedExecute(item(o,examId),"PERFORM");
        assertThat(money("SELECT total_charge FROM v_admission_deposit_balance WHERE admission_id=?",a)).isEqualByComparingTo("20");
        assertThat(value("SELECT status FROM medical_order WHERE id=?",o)).isEqualTo("COMPLETED");
    }
    @Test void typedInfusionItemsRemainIndependentAndTemplatesRemainEditableLists() {
        long a=admission(); String code="G"+UUID.randomUUID().toString().substring(0,20);
        jdbcTemplate.update("INSERT INTO medical_item(item_code,name,item_type,unit,unit_price,stock_quantity,inventory_managed) VALUES(?,?,'DRUG','box',30,0,TRUE)",code,code);
        long other=number("SELECT id FROM medical_item WHERE item_code=?",code);
        jdbcTemplate.update("INSERT INTO drug_profile(medical_item_id,dose_unit,dose_units_per_stock,allowed_routes) VALUES(?,'片',10,'静脉滴注')",other);
        long o=typedOrder(a,typedDrug(drugId,"TEMPORARY","测试组"),typedDrug(other,"TEMPORARY","测试组")),line=item(o,drugId);
        orders.changeStatus(o,"submit");
        long dispense=typedExecute(line,"DISPENSE");
        assertThat(number("SELECT COUNT(*) FROM order_item WHERE medical_order_id=? AND dispensed_quantity=1",o)).isEqualTo(1);
        typedOrders.reverse(dispense,"单项退药测试");
        assertThat(number("SELECT COUNT(*) FROM order_item WHERE medical_order_id=? AND dispensed_quantity=0",o)).isEqualTo(2);
        typedExecute(line,"DISPENSE"); typedExecute(line,"ADMINISTER");
        assertThat(value("SELECT status FROM medical_order WHERE id=?",o)).isEqualTo("IN_PROGRESS");
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
        assertThat(jdbcTemplate.queryForList("SELECT routine_name FROM information_schema.routines WHERE routine_schema=DATABASE() AND routine_type='PROCEDURE'",String.class))
            .containsExactlyInAnyOrder("sp_admit_patient","sp_transfer_bed","sp_refresh_account_alert","sp_discharge_settlement");
        assertThat(number("SELECT COUNT(*) FROM patient")).isGreaterThanOrEqualTo(30);
        assertThat(number("SELECT COUNT(*) FROM execution_record")).isGreaterThanOrEqualTo(60);
        assertThat(number("SELECT COUNT(*) FROM charge_detail")).isGreaterThanOrEqualTo(60);
    }
    @Test void temporaryOrderRequiresEveryItemAndDoesNotRepeat() {
        long a=admission(), o=order(a,"TEMPORARY",drugId,examId);
        orders.changeStatus(o,"submit");
        long drugLine=item(o,drugId);
        String key=UUID.randomUUID().toString();
        var request=new Requests.OrderExecution(key,drugLine,"DISPENSE",false,"回归执行");
        typedOrders.execute(request);
        assertThat(value("SELECT status FROM medical_order WHERE id=?",o)).isEqualTo("IN_PROGRESS");
        rejects(() -> orders.changeStatus(o,"complete"));
        // The same request number is idempotent, and a temporary drug dispenses only once.
        rejects(() -> typedOrders.execute(request));
        rejects(() -> execute(drugLine));
        execute(item(o,examId));
        assertThat(number("SELECT COUNT(*) FROM charge_detail WHERE admission_id=?",a)).isEqualTo(2);
        assertThat(money("SELECT stock_quantity FROM medical_item WHERE id=?",drugId)).isEqualByComparingTo("99");
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
        var settled=finance.settle(a,"CARD");
        assertThat((BigDecimal)settled.get("refund_amount")).isEqualByComparingTo("980");
        assertThat(money("SELECT deposit_balance FROM v_admission_deposit_balance WHERE admission_id=?",a)).isEqualByComparingTo("20");
        assertThat(money("SELECT available_balance FROM v_admission_deposit_balance WHERE admission_id=?",a)).isEqualByComparingTo("0");
        assertThat(value("SELECT payment_method FROM deposit_transaction WHERE admission_id=? AND transaction_type='REFUND'",a)).isEqualTo("CARD");
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
        assertThat(admissions.list("ALL",null,null,null,false,1,20).total()).isZero();
        asAdmin();
        assertThat(admissions.detail(a)).containsEntry("id",a);
    }

    @Test void allOriginalChargesAreReversedOnceIncludingLegacySplitCharges() {
        long a=admission(),o=order(a,"TEMPORARY",drugId);
        orders.changeStatus(o,"submit"); long e=execute(item(o,drugId));
        jdbcTemplate.update("INSERT INTO charge_detail(admission_id,execution_id,medical_item_id,department_id,charge_no,charge_type,unit_price_snapshot,quantity,amount) VALUES(?,?,?,?,?,'NORMAL',10,1,10)",
            a,e,drugId,departmentId,"C"+UUID.randomUUID().toString().substring(0,25));
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
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0))
            .andExpect(jsonPath("$.data.total").value(0));
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

    @Test void inventoryInboundAdjustmentThresholdAndHistoryStayReconciled() {
        jdbcTemplate.update("UPDATE medical_item SET stock_quantity=0,low_stock_threshold=NULL WHERE id=?",drugId);
        var inbound=inventory.inbound(drugId,new BigDecimal("12"),"采购入库 TEST-001");
        assertThat((BigDecimal)inbound.get("stockQuantity")).isEqualByComparingTo("12");
        inventory.setThreshold(drugId,new BigDecimal("10"));
        assertThat(inventory.items(null).stream().filter(row -> id(row)==drugId).findFirst().orElseThrow())
            .containsEntry("stock_status","NORMAL");

        var adjustment=inventory.adjust(drugId,new BigDecimal("8"),"盘点少4盒");
        assertThat((BigDecimal)adjustment.get("stockQuantity")).isEqualByComparingTo("8");
        assertThat(inventory.items(null).stream().filter(row -> id(row)==drugId).findFirst().orElseThrow())
            .containsEntry("stock_status","LOW");
        var history=inventory.transactions(drugId,1,20);
        assertThat(history.total()).isEqualTo(2);
        assertThat(history.items()).extracting(row -> row.get("transaction_type"))
            .containsExactly("ADJUSTMENT","INBOUND");
        assertThat(number("SELECT COUNT(*) FROM drug_inventory_transaction WHERE medical_item_id=? AND after_quantity<>before_quantity+quantity",drugId)).isZero();
        rejects(() -> inventory.adjust(drugId,new BigDecimal("8"),"无变化"));
        rejects(() -> inventory.inbound(drugId,new BigDecimal("1.5"),"整数药品不允许小数"));
        assertThat(money("SELECT stock_quantity FROM medical_item WHERE id=?",drugId)).isEqualByComparingTo("8");
    }

    @Test void inpatientStatisticsExposeCurrentTrendAndLengthOfStayViews() {
        long admissionId=admission();
        var today=LocalDate.now(java.time.ZoneOffset.UTC);
        var current=statistics.currentOperations(14);
        assertThat(((Number)current.get("current_inpatients")).longValue()).isPositive();
        assertThat(current).containsKeys("available_beds","pending_discharge_count","long_stay_count","overdue_patient_count");
        var trend=statistics.admissionTrend(today.minusDays(2),today);
        assertThat(trend).hasSize(3).allSatisfy(row -> assertThat(row).containsKeys("stat_date","admitted_count","discharged_count","inpatient_census"));
        assertThat(statistics.lengthOfStayAndDebt(today.minusMonths(6),today))
            .containsKeys("average_length_of_stay","median_length_of_stay","p90_length_of_stay","discharged_sample_size");
        assertThat(statistics.lengthOfStayDistribution(today.minusMonths(6),today)).isNotNull();
        jdbcTemplate.update("UPDATE admission SET admission_time=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 20 DAY) WHERE id=?",admissionId);
        assertThat(admissions.list(null,null,null,14,false,1,20).items())
            .extracting(row -> ((Number)row.get("admission_id")).longValue()).contains(admissionId);
        assertThat(admissions.list("PENDING_DISCHARGE",null,null,null,true,1,20).items())
            .allSatisfy(row -> assertThat(row.get("status")).isEqualTo("PENDING_DISCHARGE"));

        context(new CurrentUser(adminId,"scoped-doctor",List.of("DOCTOR"),doctorId,departmentId));
        assertThat(((Number)statistics.currentOperations(14).get("current_inpatients")).longValue()).isPositive();
        context(new CurrentUser(adminId,"other-doctor",List.of("DOCTOR"),-1L,departmentId));
        assertThat(((Number)statistics.currentOperations(14).get("current_inpatients")).longValue()).isZero();
        context(new CurrentUser(adminId,"other-ward",List.of("NURSE"),-1L,departmentId+999999));
        assertThat(statistics.bedUtilization()).isEmpty();
        assertThat(statistics.departmentCensus(today.minusDays(2),today)).isEmpty();
        assertThat(statistics.admissionTrend(today.minusDays(2),today))
            .allSatisfy(row -> assertThat(((Number)row.get("inpatient_census")).longValue()).isZero());
        asAdmin();
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
}
