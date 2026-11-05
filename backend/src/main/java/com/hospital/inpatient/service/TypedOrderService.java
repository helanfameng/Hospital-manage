package com.hospital.inpatient.service;

import com.hospital.inpatient.common.PageResult;
import com.hospital.inpatient.dto.Requests;
import com.hospital.inpatient.exception.BusinessException;
import com.hospital.inpatient.security.SecurityUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Current orders separate prescribing, dispensing, administration and non-drug work. */
@Service
public class TypedOrderService {
    private static final long LATE_TOLERANCE_SECONDS=30*60L;
    private final AccountGuard g;
    private final AuditService audit;
    public TypedOrderService(AccountGuard g, AuditService audit) { this.g=g; this.audit=audit; }
    static long id(Object value) { return ((Number)value).longValue(); }
    static BigDecimal decimal(Object value) { return value==null ? BigDecimal.ZERO : new BigDecimal(value.toString()); }
    static boolean yes(Object value) { return Boolean.TRUE.equals(value) || "1".equals(String.valueOf(value)); }
    static void require(boolean ok,String message) { if(!ok) throw new BusinessException("INVALID_ORDER",message); }
    boolean role(String role) { return SecurityUtils.currentUser().roles().contains("ADMIN") || SecurityUtils.currentUser().roles().contains(role); }
    long staff() { return id(g.row("SELECT staff_id FROM user_account WHERE id=? AND staff_id IS NOT NULL",SecurityUtils.currentUser().id()).get("staff_id")); }
    long insert(String sql,Object... args) { g.jdbc.update(sql,args); return g.count("SELECT LAST_INSERT_ID()"); }
    String serial(String prefix) { return prefix+UUID.randomUUID().toString().replace("-","").substring(0,30); }
    public boolean current(long orderId) { return g.count("SELECT COUNT(*) FROM medical_order WHERE id=?",orderId)>0; }

    long successfulAdministrations(Object line) {
        return g.count("SELECT COUNT(*) FROM execution_record er WHERE er.order_item_id=? AND er.phase='ADMINISTER' AND er.status='SUCCESS' AND NOT EXISTS(SELECT 1 FROM execution_record rev WHERE rev.reversed_execution_id=er.id)",line);
    }
    void ensureInitialSchedule(Map<String,Object> line) {
        if(!"DRUG".equals(line.get("item_type_snapshot"))) return;
        g.jdbc.update("INSERT IGNORE INTO medication_schedule(order_item_id,scheduled_at) VALUES(?,?)",
                line.get("id"),line.get("effective_start_time"));
    }
    Instant nextRegularAt(Map<String,Object> line,Instant floor) {
        Instant start=((Timestamp)line.get("effective_start_time")).toInstant();
        long step=((Number)line.get("administration_interval_minutes")).longValue()*60_000L;
        if(!floor.isAfter(start)) return start;
        long offset=floor.toEpochMilli()-start.toEpochMilli();
        long jumps=(offset+step-1)/step;
        return start.plusMillis(jumps*step);
    }
    void createNextSchedule(Map<String,Object> line,Timestamp previousScheduledAt,boolean notDone,boolean late,Instant resolvedAt) {
        if(!"DRUG".equals(line.get("item_type_snapshot")) || "ONCE".equals(line.get("administration_mode"))) return;
        int minutes=((Number)line.get("administration_interval_minutes")).intValue();
        Instant next;
        if("PRN".equals(line.get("administration_mode"))) next=resolvedAt.plusSeconds(minutes*60L);
        else if(late) next=nextRegularAt(line,resolvedAt.plusSeconds(minutes*60L));
        else if(notDone) next=nextRegularAt(line,resolvedAt.plusMillis(1));
        else next=previousScheduledAt.toInstant().plusSeconds(minutes*60L);
        Timestamp end=(Timestamp)line.get("effective_end_time");
        if(end!=null && !next.isBefore(end.toInstant())) return;
        g.jdbc.update("INSERT IGNORE INTO medication_schedule(order_item_id,scheduled_at) VALUES(?,?)",line.get("id"),Timestamp.from(next));
    }

    public List<Map<String,Object>> catalog() {
        return g.jdbc.queryForList("""
            SELECT mi.*,dp.dose_unit,dp.dose_units_per_stock,dp.allowed_routes
            FROM medical_item mi LEFT JOIN drug_profile dp ON dp.medical_item_id=mi.id
            WHERE mi.active=TRUE AND mi.item_type<>'BED_FEE' ORDER BY mi.item_type,mi.id
            """);
    }

    @Transactional
    public Map<String,Object> saveCatalog(com.hospital.inpatient.controller.TypedOrderController.Catalog body) {
        boolean drug=body.category().equals("DRUG");
        require(!body.billable() || body.price().signum()>0,"收费项目必须填写正数价格");
        require(body.billable() || body.price().signum()==0,"非收费项目价格应为零");
        require(!Set.of("NURSING","DIET","OTHER").contains(body.category()) || !body.billable(),"护理、饮食、其他指令不自动计费");
        if(drug) require(body.doseUnit()!=null && !body.doseUnit().isBlank() && body.doseUnitsPerStock()!=null && body.allowedRoutes()!=null && !body.allowedRoutes().isBlank(),"药品必须配置用量单位、换算关系和给药途径");
        if(drug) for(String route:body.allowedRoutes().split(",")) require(Set.of("口服","静脉滴注","静脉注射","肌内注射","皮下注射","外用","吸入","滴眼","滴耳","鼻用","其他").contains(route),"给药途径不合法");
        long item;
        if(body.id()==null) {
            item=insert("INSERT INTO medical_item(item_code,name,item_type,unit,unit_price,specification,dosage_form,stock_quantity,billable,inventory_managed,execution_role,allow_fractional_quantity) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                body.code(),body.name(),body.category(),body.unit(),body.price(),body.specification(),body.dosageForm(),drug?BigDecimal.ZERO:null,body.billable(),drug,body.executionRole()==null?"NURSE":body.executionRole(),body.allowFractionalQuantity());
        } else {
            item=body.id(); var before=g.row("SELECT * FROM medical_item WHERE id=? FOR UPDATE",item);
            require(before.get("unit").equals(body.unit()) && before.get("item_type").equals(body.category()),"已有项目的分类和库存计价单位不能直接更换，请另建项目");
            g.jdbc.update("UPDATE medical_item SET item_code=?,name=?,unit_price=?,specification=?,dosage_form=?,billable=?,execution_role=?,allow_fractional_quantity=? WHERE id=?",body.code(),body.name(),body.price(),body.specification(),body.dosageForm(),body.billable(),body.executionRole()==null?"NURSE":body.executionRole(),body.allowFractionalQuantity(),item);
        }
        if(drug) g.jdbc.update("INSERT INTO drug_profile(medical_item_id,dose_unit,dose_units_per_stock,allowed_routes) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE dose_unit=VALUES(dose_unit),dose_units_per_stock=VALUES(dose_units_per_stock),allowed_routes=VALUES(allowed_routes)",item,body.doseUnit(),body.doseUnitsPerStock(),body.allowedRoutes());
        audit.success("ORDER_CATALOG_SAVE","medical_item",item,null,Map.of("name",body.name()));
        return Map.of("id",item);
    }

    public List<Map<String,Object>> templates() {
        var rows=g.jdbc.queryForList("SELECT * FROM order_template WHERE active=TRUE ORDER BY id DESC");
        for(var row:rows) row.put("items",g.jdbc.queryForList("SELECT ti.medical_item_id,mi.name,mi.item_type,mi.active FROM order_template_item ti JOIN medical_item mi ON mi.id=ti.medical_item_id WHERE ti.template_id=? ORDER BY ti.sort_order",row.get("id")));
        return rows;
    }
    @Transactional
    public Map<String,Object> saveTemplate(com.hospital.inpatient.controller.TypedOrderController.Template body) {
        require(body.medicalItemIds().stream().distinct().count()==body.medicalItemIds().size(),"模板项目不能重复");
        long template=insert("INSERT INTO order_template(name,description,created_by) VALUES(?,?,?)",body.name(),body.description(),SecurityUtils.currentUser().id());
        int index=0;
        for(long item:body.medicalItemIds()) {
            require(g.count("SELECT COUNT(*) FROM medical_item WHERE id=? AND active=TRUE AND item_type<>'BED_FEE'",item)==1,"模板包含不可用项目");
            g.jdbc.update("INSERT INTO order_template_item(template_id,medical_item_id,sort_order) VALUES(?,?,?)",template,item,index++);
        }
        audit.success("ORDER_TEMPLATE_CREATE","order_template",template,null,Map.of("name",body.name()));
        return Map.of("id",template);
    }
    @Transactional
    public void disableTemplate(long template) {
        var row=g.row("SELECT * FROM order_template WHERE id=? FOR UPDATE",template);
        require(role("ADMIN") || id(row.get("created_by"))==SecurityUtils.currentUser().id(),"只能停用自己创建的模板");
        g.jdbc.update("UPDATE order_template SET active=FALSE WHERE id=?",template);
        audit.success("ORDER_TEMPLATE_DISABLE","order_template",template,row,Map.of("active",false));
    }

    @Transactional
    public long create(Requests.OrderCreate request) {
        g.lockInHospital(request.admissionId());
        require(request.items()!=null && !request.items().isEmpty(),"请至少添加一个医嘱项目");
        long order=insert("INSERT INTO medical_order(order_no,admission_id,status,start_time,note,prescribed_by) VALUES(?,?,'DRAFT',UTC_TIMESTAMP(3),?,?)",
            serial("O"),request.admissionId(),request.note(),staff());
        // The provisional identifier is replaced inside this transaction, before exposure or commit.
        Instant createdAt=((Timestamp)g.row("SELECT created_at FROM medical_order WHERE id=?",order).get("created_at")).toInstant();
        g.jdbc.update("UPDATE medical_order SET order_no=? WHERE id=?",OrderNumber.format(createdAt,order),order);
        Instant now=Instant.now();
        for(var line:request.items()) {
            var mi=g.row("SELECT * FROM medical_item WHERE id=? AND active=TRUE FOR UPDATE",line.medicalItemId());
            String category=(String)mi.get("item_type");
            require(!category.equals("BED_FEE"),"床位费不在医嘱中开立，请使用费用管理");
            boolean drug=category.equals("DRUG"), billable=yes(mi.get("billable"));
            String type=line.orderType();
            require(Set.of("TEMPORARY","LONG_TERM").contains(type),"医嘱类型不合法");
            require(!billable || decimal(mi.get("unit_price")).signum()>0,"收费项目价格无效");
            BigDecimal executionQuantity=line.executionQuantityPerOperation();
            if(!drug) require(executionQuantity!=null && executionQuantity.signum()>0,"非药品项目执行数量必须大于零");
            if(Set.of("EXAMINATION","LAB","TREATMENT").contains(category) && "次".equals(mi.get("unit")))
                require(executionQuantity.compareTo(BigDecimal.ONE)==0,"按次计价的检查、检验和治疗每次仅记录一次执行，数量必须为1");
            if(!drug && !yes(mi.get("allow_fractional_quantity"))) require(executionQuantity.stripTrailingZeros().scale()<=0,"该项目数量必须为整数，单位："+mi.get("unit"));
            require(!Set.of("NURSING","DIET","OTHER").contains(category) || !billable,"护理、饮食及其他指令不得自动计费");
            require(drug || line.dosePerAdministration()==null,"非药品项目不填写剂量");
            BigDecimal factor=null;
            String doseUnit=null;
            BigDecimal requestedDispense=null;
            String administrationMode=null;
            Integer interval=null,maxPerDay=null;
            if(drug) {
                var profile=g.row("SELECT * FROM drug_profile WHERE medical_item_id=?",line.medicalItemId());
                doseUnit=(String)profile.get("dose_unit"); factor=decimal(profile.get("dose_units_per_stock"));
                require(factor.signum()>0,"请先维护有效的药品单位换算资料");
                require(line.dosePerAdministration()!=null && line.dosePerAdministration().signum()>0 && Objects.equals(doseUnit,line.doseUnit()),"请按药品资料填写单次用量与单位");
                require(line.route()!=null && Arrays.asList(((String)profile.get("allowed_routes")).split(",")).contains(line.route()),"给药途径与药品资料不匹配");
                administrationMode=type.equals("TEMPORARY")?"ONCE":line.administrationMode();
                require(type.equals("TEMPORARY") || Set.of("SCHEDULED","PRN").contains(administrationMode),"长期药品必须选择定时或按需给药");
                interval=type.equals("LONG_TERM")?line.administrationIntervalMinutes():null;
                require(type.equals("TEMPORARY") || (interval!=null && interval>0),"长期药品必须填写有效给药间隔");
                maxPerDay="PRN".equals(administrationMode)?line.maxAdministrationsPerDay():null;
                require(!"PRN".equals(administrationMode) || (maxPerDay!=null && maxPerDay>0),"按需给药必须填写每日最大次数");
                requestedDispense=line.requestedDispenseQuantity();
                if(requestedDispense==null)
                    requestedDispense=line.dosePerAdministration().divide(factor,yes(mi.get("allow_fractional_quantity"))?3:0,RoundingMode.CEILING);
                require(requestedDispense.signum()>0,"建议发药数量必须大于零");
                if(!yes(mi.get("allow_fractional_quantity"))) require(requestedDispense.stripTrailingZeros().scale()<=0,"该药品发药数量必须为整数，单位："+mi.get("unit"));
            }
            Instant start=line.startTime()==null ? now : line.startTime();
            require(line.endTime()==null || line.endTime().isAfter(start),"结束时间必须晚于开始时间");
            insert("""
                INSERT INTO order_item(medical_order_id,medical_item_id,dose_per_administration,requested_dispense_quantity,execution_quantity_per_operation,route,instructions,
                item_name_snapshot,item_type_snapshot,unit_snapshot,dose_unit,dose_units_per_stock,price_snapshot,
                billable,inventory_managed,execution_role,order_type,administration_mode,administration_interval_minutes,max_administrations_per_day,effective_start_time,effective_end_time)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,order,line.medicalItemId(),line.dosePerAdministration(),requestedDispense,executionQuantity,drug?line.route():null,line.instructions(),
                mi.get("name"),category,mi.get("unit"),doseUnit,factor,mi.get("unit_price"),billable,yes(mi.get("inventory_managed")),
                drug?"NURSE":mi.get("execution_role"),type,administrationMode,interval,maxPerDay,Timestamp.from(start),line.endTime()==null?null:Timestamp.from(line.endTime()));
        }
        audit.success("ORDER_CREATE","medical_order",order,null,Map.of("itemCount",request.items().size()));
        return order;
    }

    @Transactional
    public void change(long order,String action,Long lineId) {
        g.lockInHospital(g.orderAdmission(order));
        require(current(order),"医嘱不存在或已归档");
        var lines=g.jdbc.queryForList("SELECT * FROM order_item WHERE medical_order_id=? ORDER BY id FOR UPDATE",order);
        if(lineId!=null) {
            var target=lines.stream().filter(l->id(l.get("id"))==lineId).findFirst().orElseThrow(()->new BusinessException("NOT_FOUND","医嘱明细不存在"));
            lines=List.of(target);
        }
        int changed=0;
        for(var line:lines) {
            String status=(String)line.get("item_status");
            if(Set.of("COMPLETED","STOPPED","CANCELLED").contains(status)) continue;
            String next;
            switch(action) {
                case "submit" -> { require(status.equals("DRAFT"),"只有草稿可以提交"); ensureInitialSchedule(line); next="SUBMITTED"; }
                case "stop" -> { require(line.get("order_type").equals("LONG_TERM") && !status.equals("DRAFT"),"只有已提交的长期医嘱可以停止"); next="STOPPED"; }
                case "cancel" -> { require(decimal(line.get("dispensed_quantity")).signum()==0 && decimal(line.get("executed_quantity")).signum()==0,"已发药或已执行项目不能直接撤销"); next="CANCELLED"; }
                case "complete" -> {
                    require(!status.equals("DRAFT") && decimal(line.get("executed_quantity")).signum()>0,"需至少完成一次实际执行才能完成医嘱");
                    next="COMPLETED";
                }
                default -> throw new BusinessException("INVALID_ACTION","操作不合法");
            }
            g.jdbc.update("UPDATE order_item SET item_status=? WHERE id=?",next,line.get("id"));
            if(Set.of("STOPPED","CANCELLED","COMPLETED").contains(next))
                g.jdbc.update("UPDATE medication_schedule SET status='CANCELLED',completed_at=UTC_TIMESTAMP(3),version=version+1 WHERE order_item_id=? AND status IN ('PENDING','DEFERRED')",line.get("id"));
            changed++;
        }
        require(changed>0,"没有符合操作条件的项目");
        if(action.equals("submit")) g.jdbc.update("UPDATE medical_order SET submitted_at=UTC_TIMESTAMP(3) WHERE id=?",order);
        refresh(order);
        audit.success("ORDER_"+action.toUpperCase(),"medical_order",order,null,Map.of("itemCount",changed));
    }

    void refresh(long order) {
        var states=g.jdbc.queryForList("SELECT item_status FROM order_item WHERE medical_order_id=?",String.class,order);
        var terminal=Set.of("COMPLETED","STOPPED","CANCELLED");
        boolean partiallyFinished=states.stream().anyMatch(terminal::contains) && states.stream().anyMatch(state->!terminal.contains(state));
        String status=states.stream().allMatch("DRAFT"::equals)?"DRAFT":
                states.stream().allMatch("CANCELLED"::equals)?"CANCELLED":
                states.stream().allMatch("STOPPED"::equals)?"STOPPED":
                states.contains("IN_PROGRESS") || partiallyFinished?"IN_PROGRESS":
                states.contains("SUBMITTED")?"SUBMITTED":states.contains("DRAFT")?"DRAFT":"COMPLETED";
        g.jdbc.update("UPDATE medical_order SET status=? WHERE id=?",status,order);
    }

    boolean unrestrictedTasks() {
        return SecurityUtils.currentUser().roles().stream().anyMatch(Set.of("ADMIN","PHARMACY","ADMISSION","CASHIER")::contains);
    }
    boolean overdue(Map<String,Object> row,Instant now) {
        Object scheduled=row.get("scheduled_at");
        return "SCHEDULED".equals(row.get("administration_mode")) && scheduled instanceof Timestamp t
                && t.toInstant().isBefore(now.minusSeconds(LATE_TOLERANCE_SECONDS));
    }
    String dispenseStatus(Map<String,Object> row) {
        BigDecimal dispensed=decimal(row.get("dispensed_quantity"));
        if(dispensed.signum()<=0) return "NOT_DISPENSED";
        BigDecimal available=dispensed.multiply(decimal(row.get("dose_units_per_stock"))).subtract(decimal(row.get("executed_quantity")));
        return available.compareTo(decimal(row.get("dose_per_administration")))>=0?"READY":"INSUFFICIENT";
    }
    boolean matchesKeyword(Map<String,Object> row,String keyword) {
        if(keyword==null || keyword.isBlank()) return true;
        String needle=keyword.trim().toLowerCase(Locale.ROOT);
        return List.of("patient_name","patient_no","admission_no","bed_no","order_no","item_name")
                .stream().map(row::get).filter(Objects::nonNull).map(Object::toString)
                .anyMatch(value->value.toLowerCase(Locale.ROOT).contains(needle));
    }
    void decorateTask(Map<String,Object> row,Instant now,boolean preview) {
        Object scheduled=row.get("scheduled_at");
        row.put("task_time",scheduled==null?row.get("effective_start_time"):scheduled);
        row.put("preview_only",preview);
        row.put("timeliness",overdue(row,now)?"OVERDUE":"NORMAL");
        if(overdue(row,now) && scheduled instanceof Timestamp t)
            row.put("overdue_minutes",Math.max(1,(now.getEpochSecond()-t.toInstant().getEpochSecond())/60));
        row.put("dispense_status", "DRUG".equals(row.get("item_type"))?dispenseStatus(row):null);
    }
    List<Map<String,Object>> filterTasks(List<Map<String,Object>> source,String phase,String keyword,String administrationMode,
                                          String timeliness,Instant scheduledFrom,Instant scheduledTo,String dispenseStatus,Instant now) {
        return source.stream().filter(row->matchesKeyword(row,keyword))
                .filter(row->administrationMode==null || administrationMode.isBlank() || administrationMode.equals(row.get("administration_mode")))
                .filter(row->dispenseStatus==null || dispenseStatus.isBlank() || dispenseStatus.equals(row.get("dispense_status")))
                .filter(row-> {
                    if(timeliness==null || timeliness.isBlank()) return true;
                    if("OVERDUE".equals(timeliness)) return "OVERDUE".equals(row.get("timeliness"));
                    if(!"DUE_30".equals(timeliness) || !(row.get("task_time") instanceof Timestamp t)) return false;
                    Instant time=t.toInstant();
                    return phase.equals("ADMINISTER") && !time.isBefore(now.minusSeconds(30*60L)) && !time.isAfter(now.plusSeconds(30*60L));
                })
                .filter(row-> !(row.get("task_time") instanceof Timestamp t) || (scheduledFrom==null || !t.toInstant().isBefore(scheduledFrom)) && (scheduledTo==null || !t.toInstant().isAfter(scheduledTo)))
                .toList();
    }
    public PageResult<Map<String,Object>> tasks(String phase,String itemType,String view,Integer windowHours,String keyword,
            String administrationMode,String timeliness,Instant scheduledFrom,Instant scheduledTo,String dispenseStatus,
            Long departmentId,String sort,int page,int size) {
        require(Set.of("DISPENSE","ADMINISTER","PERFORM").contains(phase),"请选择执行环节");
        boolean future="FUTURE".equals(view);
        require(view==null || future || "EXECUTABLE".equals(view),"任务视图不合法");
        require(!future || phase.equals("ADMINISTER"),"未来预览仅支持护士给药");
        if(phase.equals("DISPENSE") && !role("PHARMACY")) return new PageResult<>(List.of(),0,Math.max(page,1),Math.min(Math.max(size,1),100));
        if(phase.equals("ADMINISTER") && !role("NURSE")) return new PageResult<>(List.of(),0,Math.max(page,1),Math.min(Math.max(size,1),100));
        require(administrationMode==null || administrationMode.isBlank() || Set.of("SCHEDULED","PRN").contains(administrationMode),"给药方式不合法");
        require(timeliness==null || timeliness.isBlank() || Set.of("OVERDUE","DUE_30").contains(timeliness),"时效筛选不合法");
        require(dispenseStatus==null || dispenseStatus.isBlank() || Set.of("NOT_DISPENSED","INSUFFICIENT","READY").contains(dispenseStatus),"发药状态不合法");
        require(sort==null || sort.isBlank() || Set.of("PLANNED_AT_ASC","PLANNED_AT_DESC").contains(sort),"排序方式不合法");
        require(scheduledFrom==null || scheduledTo==null || !scheduledTo.isBefore(scheduledFrom),"计划时间范围不合法");
        require(departmentId==null || unrestrictedTasks(),"当前账号不能跨科筛选任务");
        int safePage=Math.max(page,1),safeSize=Math.min(Math.max(size,1),100);
        int hours=windowHours==null?2:windowHours;
        require(!future || Set.of(2,4,8).contains(hours),"未来预览仅支持2、4或8小时");
        Instant now=Instant.now(),horizon=now.plusSeconds(hours*3600L);
        String scheduleJoin=phase.equals("ADMINISTER")
                ? (future ? " LEFT JOIN medication_schedule ms ON ms.order_item_id=oi.id AND ms.status='PENDING' " : " JOIN medication_schedule ms ON ms.order_item_id=oi.id AND ms.status='PENDING' AND ms.scheduled_at<=UTC_TIMESTAMP(3) ")
                : " LEFT JOIN medication_schedule ms ON 1=0 ";
        StringBuilder sql=new StringBuilder("""
            SELECT oi.*,oi.id order_item_id,mo.id order_id,mo.order_no,mo.admission_id,p.name patient_name,p.patient_no,a.admission_no,
            d.name department_name,oi.item_name_snapshot item_name,oi.item_type_snapshot item_type,
            b.bed_no,oi.unit_snapshot unit,mi.stock_quantity,ms.id schedule_id,ms.scheduled_at,
            (SELECT COUNT(*) FROM execution_record er WHERE er.order_item_id=oi.id AND er.phase='ADMINISTER' AND er.status='SUCCESS'
                AND NOT EXISTS(SELECT 1 FROM execution_record rev WHERE rev.reversed_execution_id=er.id)) administration_count,
            CASE WHEN ?='ADMINISTER' THEN oi.dose_per_administration
                 WHEN ?='DISPENSE' THEN oi.requested_dispense_quantity
                 ELSE oi.execution_quantity_per_operation END task_quantity,
            CASE WHEN ?='ADMINISTER' THEN oi.dose_unit ELSE oi.unit_snapshot END task_unit
            FROM order_item oi JOIN medical_order mo ON mo.id=oi.medical_order_id
            JOIN admission a ON a.id=mo.admission_id JOIN patient p ON p.id=a.patient_id
            JOIN department d ON d.id=a.department_id JOIN medical_item mi ON mi.id=oi.medical_item_id
            LEFT JOIN bed_usage bu ON bu.active_admission_key=a.id LEFT JOIN bed b ON b.id=bu.bed_id
            """+scheduleJoin+"""
            WHERE a.status='IN_HOSPITAL' AND oi.item_status IN ('SUBMITTED','IN_PROGRESS')
            AND oi.effective_start_time<=?
            AND (oi.effective_end_time IS NULL OR oi.effective_end_time>?)
            """);
        List<Object> args=new ArrayList<>(List.of(phase,phase,phase,Timestamp.from(future?horizon:now),Timestamp.from(now)));
        if(phase.equals("DISPENSE")) sql.append(" AND oi.item_type_snapshot='DRUG' AND (oi.order_type='LONG_TERM' OR oi.dispensed_quantity=0)");
        else if(phase.equals("ADMINISTER")) sql.append(" AND oi.item_type_snapshot='DRUG'");
        else { sql.append(" AND oi.item_type_snapshot<>'DRUG'"); if(!role("ADMIN")) { sql.append(" AND oi.execution_role IN ("); var roles=SecurityUtils.currentUser().roles().stream().filter(r->Set.of("NURSE","DOCTOR").contains(r)).toList(); if(roles.isEmpty()) return new PageResult<>(List.of(),0,safePage,safeSize); sql.append(String.join(",",Collections.nCopies(roles.size(),"?"))).append(")"); args.addAll(roles); } }
        if(SecurityUtils.doctorScope()!=null) { sql.append(" AND a.attending_doctor_id=?"); args.add(SecurityUtils.doctorScope()); }
        if(SecurityUtils.departmentScope()!=null) { sql.append(" AND a.department_id=?"); args.add(SecurityUtils.departmentScope()); }
        if(departmentId!=null) { sql.append(" AND a.department_id=?"); args.add(departmentId); }
        if(itemType!=null && !itemType.isBlank()) { sql.append(" AND oi.item_type_snapshot=?"); args.add(itemType); }
        List<Map<String,Object>> base=g.jdbc.queryForList(sql.toString(),args.toArray());
        List<Map<String,Object>> rows=new ArrayList<>();
        if(future) {
            for(var raw:base) {
                if("SCHEDULED".equals(raw.get("administration_mode")) && "LONG_TERM".equals(raw.get("order_type"))) {
                    for(Instant at=nextRegularAt(raw,now.plusMillis(1));!at.isAfter(horizon);at=at.plusMillis(((Number)raw.get("administration_interval_minutes")).longValue()*60_000L)) {
                        var row=new LinkedHashMap<>(raw);
                        if(!(raw.get("scheduled_at") instanceof Timestamp known) || !known.toInstant().equals(at)) row.put("schedule_id",null);
                        row.put("scheduled_at",Timestamp.from(at)); decorateTask(row,now,true); rows.add(row);
                    }
                } else if(raw.get("scheduled_at") instanceof Timestamp scheduled && scheduled.toInstant().isAfter(now) && !scheduled.toInstant().isAfter(horizon)) {
                    var row=new LinkedHashMap<>(raw); decorateTask(row,now,true); rows.add(row);
                }
            }
        } else for(var raw:base) { var row=new LinkedHashMap<>(raw); decorateTask(row,now,false); rows.add(row); }
        List<Map<String,Object>> filtered=filterTasks(rows,phase,keyword,administrationMode,timeliness,scheduledFrom,scheduledTo,dispenseStatus,now);
        Comparator<Map<String,Object>> byTime=Comparator.comparing(row->(Timestamp)row.get("task_time"),Comparator.nullsLast(Comparator.naturalOrder()));
        if("PLANNED_AT_DESC".equals(sort)) byTime=byTime.reversed();
        filtered=filtered.stream().sorted(byTime.thenComparing(row->((Number)row.get("order_item_id")).longValue())).toList();
        int from=Math.min((safePage-1)*safeSize,filtered.size()),to=Math.min(from+safeSize,filtered.size());
        return new PageResult<>(filtered.subList(from,to),filtered.size(),safePage,safeSize);
    }
    /** Compatibility helper retained for existing service-level callers. */
    public List<Map<String,Object>> pending(String phase,String itemType) {
        return tasks(phase,itemType,"EXECUTABLE",null,null,null,null,null,null,null,null,"PLANNED_AT_ASC",1,100).items();
    }

    @Transactional
    public Map<String,Object> execute(Requests.OrderExecution request) {
        long order=id(g.row("SELECT medical_order_id FROM order_item WHERE id=?",request.orderItemId()).get("medical_order_id"));
        long admission=g.orderAdmission(order); g.lockInHospital(admission);
        require(current(order),"医嘱不存在或已归档");
        var target=g.row("SELECT * FROM order_item WHERE id=? FOR UPDATE",request.orderItemId());
        require(!request.notDone() || (request.note()!=null && !request.note().isBlank()),"未执行时必须填写原因");
        require(!request.notDone() || !request.phase().equals("DISPENSE"),"发药失败不产生发药流水");
        List<Long> executions=new ArrayList<>();
        executions.add(executeLine(admission,request,target));
        refresh(order); g.refreshAlerts(admission);
        audit.success("ORDER_"+request.phase(),"medical_order",order,null,Map.of("executionIds",executions));
        return Map.of("executionIds",executions);
    }

    long executeLine(long admission,Requests.OrderExecution request,Map<String,Object> line) {
        require(Set.of("SUBMITTED","IN_PROGRESS").contains(line.get("item_status")),"医嘱项目当前不可执行");
        Instant now=Instant.now();
        Timestamp start=(Timestamp)line.get("effective_start_time"),end=(Timestamp)line.get("effective_end_time");
        require(!start.toInstant().isAfter(now) && (end==null || end.toInstant().isAfter(now)),"医嘱尚未生效或已超过结束时间");
        boolean drug="DRUG".equals(line.get("item_type_snapshot"));
        String phase=request.phase();
        boolean allowed=phase.equals("DISPENSE") ? drug && role("PHARMACY") : phase.equals("ADMINISTER") ? drug && role("NURSE") : !drug && role((String)line.get("execution_role"));
        if(!allowed) throw new BusinessException("FORBIDDEN","当前岗位不能处理该项目的这一环节",HttpStatus.FORBIDDEN);
        String key=UUID.nameUUIDFromBytes((request.requestNo()+":"+line.get("id")).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        require(g.count("SELECT COUNT(*) FROM execution_record WHERE request_no=?",key)==0,"该请求已处理，请刷新查看记录");

        Map<String,Object> schedule=null;
        if(phase.equals("ADMINISTER")) {
            schedule=request.scheduleId()==null
                    ? g.row("SELECT * FROM medication_schedule WHERE order_item_id=? AND status='PENDING' AND scheduled_at<=UTC_TIMESTAMP(3) ORDER BY scheduled_at LIMIT 1 FOR UPDATE",line.get("id"))
                    : g.row("SELECT * FROM medication_schedule WHERE id=? AND order_item_id=? FOR UPDATE",request.scheduleId(),line.get("id"));
            require("PENDING".equals(schedule.get("status")) && !((Timestamp)schedule.get("scheduled_at")).toInstant().isAfter(now),"给药任务不存在、尚未到时或已处理");
        }
        boolean late=phase.equals("ADMINISTER") && "SCHEDULED".equals(line.get("administration_mode"))
                && ((Timestamp)schedule.get("scheduled_at")).toInstant().isBefore(now.minusSeconds(LATE_TOLERANCE_SECONDS));
        require(!late || (request.note()!=null && !request.note().isBlank()),"迟发给药必须填写原因");

        BigDecimal prescribed=decimal(line.get(phase.equals("ADMINISTER")?"dose_per_administration":phase.equals("DISPENSE")?"requested_dispense_quantity":"execution_quantity_per_operation"));
        BigDecimal quantity=request.actualQuantity()==null?prescribed:request.actualQuantity();
        require(request.notDone() || quantity.signum()>0,"实际数量必须大于零");
        boolean variance=!request.notDone() && prescribed.signum()>0 && quantity.compareTo(prescribed)!=0;
        require(!variance || (request.quantityVarianceReason()!=null && !request.quantityVarianceReason().isBlank()),"实际数量与医嘱或建议数量不一致时必须填写原因");
        boolean temp="TEMPORARY".equals(line.get("order_type"));
        if(phase.equals("DISPENSE")) require(!temp || decimal(line.get("dispensed_quantity")).signum()==0,"临时项目已发药");
        if(phase.equals("ADMINISTER") && !request.notDone()) require(decimal(line.get("dispensed_quantity")).multiply(decimal(line.get("dose_units_per_stock"))).subtract(decimal(line.get("executed_quantity"))).compareTo(quantity)>=0,"已发药余量不足本次用量，请先核对药房发药");
        if(phase.equals("ADMINISTER") && "PRN".equals(line.get("administration_mode")) && !request.notDone()) {
            long today=g.count("SELECT COUNT(*) FROM execution_record er WHERE er.order_item_id=? AND er.phase='ADMINISTER' AND er.status='SUCCESS' AND DATE(er.execution_time)=UTC_DATE() AND NOT EXISTS(SELECT 1 FROM execution_record rev WHERE rev.reversed_execution_id=er.id)",line.get("id"));
            require(today<((Number)line.get("max_administrations_per_day")).intValue(),"今日按需给药次数已达上限");
        }
        var mi=g.row("SELECT * FROM medical_item WHERE id=? AND active=TRUE FOR UPDATE",line.get("medical_item_id"));
        BigDecimal before=decimal(mi.get("stock_quantity"));
        boolean inventory=phase.equals("DISPENSE") && yes(line.get("inventory_managed"));
        if(inventory) require(before.compareTo(quantity)>=0,"药品库存不足，本次操作已回滚");
        if(inventory && !yes(mi.get("allow_fractional_quantity"))) require(quantity.stripTrailingZeros().scale()<=0,"该药品实际发药数量必须为整数");
        String unit=(String)line.get(phase.equals("ADMINISTER")?"dose_unit":"unit_snapshot");
        long execution=insert("INSERT INTO execution_record(request_no,order_item_id,schedule_id,quantity,status,result_note,quantity_variance_reason,executor_id,phase,unit_snapshot) VALUES(?,?,?,?,?,?,?,?,?,?)",
                key,line.get("id"),schedule==null?null:schedule.get("id"),request.notDone()?BigDecimal.ZERO:quantity,request.notDone()?"NOT_DONE":"SUCCESS",request.note(),variance?request.quantityVarianceReason():null,staff(),phase,unit);
        if(request.notDone()) {
            if(schedule!=null) {
                g.jdbc.update("UPDATE medication_schedule SET status='NOT_DONE',completed_at=UTC_TIMESTAMP(3),not_done_reason=?,version=version+1 WHERE id=?",request.note(),schedule.get("id"));
                if(temp) g.jdbc.update("UPDATE order_item SET item_status='COMPLETED' WHERE id=?",line.get("id"));
                else { g.jdbc.update("UPDATE order_item SET item_status='IN_PROGRESS' WHERE id=?",line.get("id")); createNextSchedule(line,(Timestamp)schedule.get("scheduled_at"),true,false,now); }
            }
            return execution;
        }
        if(inventory) {
            g.jdbc.update("UPDATE medical_item SET stock_quantity=stock_quantity-?,version=version+1 WHERE id=?",quantity,line.get("medical_item_id"));
            g.jdbc.update("INSERT INTO drug_inventory_transaction(medical_item_id,execution_id,transaction_type,quantity,before_quantity,after_quantity,note,operated_by) VALUES(?,?,'DISPENSE',?,?,?,'药房发药',?)",line.get("medical_item_id"),execution,quantity.negate(),before,before.subtract(quantity),SecurityUtils.currentUser().id());
        }
        if(!phase.equals("ADMINISTER") && yes(line.get("billable"))) {
            BigDecimal price=decimal(line.get("price_snapshot")),amount=price.multiply(quantity).setScale(2,RoundingMode.HALF_UP);
            require(amount.signum()>0,"费用金额无效");
            long department=id(g.row("SELECT department_id FROM admission WHERE id=?",admission).get("department_id"));
            g.jdbc.update("INSERT INTO charge_detail(admission_id,execution_id,medical_item_id,department_id,charge_no,charge_type,unit_price_snapshot,quantity,amount,note) VALUES(?,?,?,?,?,'NORMAL',?,?,?,?)",admission,execution,line.get("medical_item_id"),department,ChargeNumber.next(false),price,quantity,amount,phase.equals("DISPENSE")?"药房发药计费":"项目执行计费");
        }
        if(phase.equals("DISPENSE")) g.jdbc.update("UPDATE order_item SET dispensed_quantity=dispensed_quantity+?,item_status='IN_PROGRESS' WHERE id=?",quantity,line.get("id"));
        else if(phase.equals("ADMINISTER")) {
            g.jdbc.update("UPDATE medication_schedule SET status='ADMINISTERED',completed_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=?",schedule.get("id"));
            g.jdbc.update("UPDATE order_item SET executed_quantity=executed_quantity+?,item_status=? WHERE id=?",quantity,temp?"COMPLETED":"IN_PROGRESS",line.get("id"));
            if(!temp) createNextSchedule(line,(Timestamp)schedule.get("scheduled_at"),false,late,now);
        }
        else g.jdbc.update("UPDATE order_item SET executed_quantity=executed_quantity+?,item_status=? WHERE id=?",quantity,temp?"COMPLETED":"IN_PROGRESS",line.get("id"));
        return execution;
    }

    @Transactional
    public void reverse(long execution,String reason) {
        long admission=g.executionAdmission(execution); g.lockInHospital(admission);
        var original=g.row("SELECT er.*,oi.medical_order_id FROM execution_record er JOIN order_item oi ON oi.id=er.order_item_id WHERE er.id=?",execution);
        var records=List.of(original);
        for(var record:records) {
            require("SUCCESS".equals(record.get("status")) && g.count("SELECT COUNT(*) FROM execution_record WHERE reversed_execution_id=?",record.get("id"))==0,"执行不存在、未成功或已经冲正");
            var line=g.row("SELECT * FROM order_item WHERE id=? FOR UPDATE",record.get("order_item_id"));
            String phase=(String)record.get("phase");
            boolean allowed=phase.equals("DISPENSE")?role("PHARMACY"):phase.equals("ADMINISTER")?role("NURSE"):role((String)line.get("execution_role"));
            if(!allowed) throw new BusinessException("FORBIDDEN","当前岗位无权冲正该环节",HttpStatus.FORBIDDEN);
            if(phase.equals("DISPENSE")) require(decimal(line.get("executed_quantity")).signum()==0,"已有给药记录，请先核对给药，不能整笔退回已使用药品");
            BigDecimal quantity=decimal(record.get("quantity"));
            if(phase.equals("ADMINISTER"))
                require(g.count("SELECT COUNT(*) FROM execution_record later WHERE later.order_item_id=? AND later.phase='ADMINISTER' AND later.status='SUCCESS' AND later.execution_time>? AND NOT EXISTS(SELECT 1 FROM execution_record rev WHERE rev.reversed_execution_id=later.id)",line.get("id"),record.get("execution_time"))==0,"只能冲正该医嘱最后一次有效给药");
            long reversed=insert("INSERT INTO execution_record(request_no,order_item_id,schedule_id,quantity,status,result_note,executor_id,reversed_execution_id,phase,unit_snapshot) VALUES(?,?,?,?, 'REVERSED',?,?,?,?,?)",UUID.randomUUID().toString(),line.get("id"),record.get("schedule_id"),quantity,reason,staff(),record.get("id"),phase,record.get("unit_snapshot"));
            if(phase.equals("DISPENSE") && yes(line.get("inventory_managed"))) {
                var mi=g.row("SELECT stock_quantity FROM medical_item WHERE id=? FOR UPDATE",line.get("medical_item_id"));
                BigDecimal before=decimal(mi.get("stock_quantity"));
                g.jdbc.update("UPDATE medical_item SET stock_quantity=stock_quantity+?,version=version+1 WHERE id=?",quantity,line.get("medical_item_id"));
                g.jdbc.update("INSERT INTO drug_inventory_transaction(medical_item_id,execution_id,transaction_type,quantity,before_quantity,after_quantity,note,operated_by) VALUES(?,?,'RETURN',?,?,?,?,?)",line.get("medical_item_id"),reversed,quantity,before,before.add(quantity),reason,SecurityUtils.currentUser().id());
            }
            for(var charge:g.jdbc.queryForList("SELECT * FROM charge_detail WHERE execution_id=? AND charge_type='NORMAL' FOR UPDATE",record.get("id")))
                g.jdbc.update("INSERT INTO charge_detail(admission_id,execution_id,medical_item_id,department_id,charge_no,charge_type,unit_price_snapshot,quantity,amount,original_charge_id,note) VALUES(?,?,?,?,?,'REVERSAL',?,?,?,?,?)",admission,reversed,line.get("medical_item_id"),charge.get("department_id"),ChargeNumber.next(true),charge.get("unit_price_snapshot"),decimal(charge.get("quantity")).negate(),decimal(charge.get("amount")).negate(),charge.get("id"),reason);
            if(phase.equals("DISPENSE")) g.jdbc.update("UPDATE order_item SET dispensed_quantity=dispensed_quantity-? WHERE id=?",quantity,line.get("id"));
            else if(phase.equals("ADMINISTER")) {
                g.jdbc.update("UPDATE order_item SET executed_quantity=executed_quantity-? WHERE id=?",quantity,line.get("id"));
                var schedule=g.row("SELECT * FROM medication_schedule WHERE id=? FOR UPDATE",record.get("schedule_id"));
                g.jdbc.update("UPDATE medication_schedule SET status='PENDING',completed_at=NULL,not_done_reason=NULL,version=version+1 WHERE id=?",schedule.get("id"));
                g.jdbc.update("UPDATE medication_schedule SET status='CANCELLED',completed_at=UTC_TIMESTAMP(3),version=version+1 WHERE order_item_id=? AND status='PENDING' AND scheduled_at>?",line.get("id"),schedule.get("scheduled_at"));
            }
            else g.jdbc.update("UPDATE order_item SET executed_quantity=executed_quantity-? WHERE id=?",quantity,line.get("id"));
            String restored=phase.equals("ADMINISTER") && successfulAdministrations(line.get("id"))>0 ? "IN_PROGRESS" : "SUBMITTED";
            g.jdbc.update("UPDATE order_item SET item_status=? WHERE id=? AND item_status NOT IN ('STOPPED','CANCELLED')",restored,line.get("id"));
        }
        refresh(id(original.get("medical_order_id"))); g.refreshAlerts(admission);
        audit.success("ORDER_EXECUTION_REVERSE","execution_record",execution,original,Map.of("reason",reason));
    }

    public List<Map<String,Object>> history(long admissionId) {
        g.requireRead(admissionId);
        return g.jdbc.queryForList("""
            SELECT er.id,er.request_no,er.execution_time,er.quantity,er.status,er.result_note,er.quantity_variance_reason,er.phase,er.schedule_id,
                   er.unit_snapshot unit,s.name executor_name,oi.item_name_snapshot item_name,
                   oi.item_type_snapshot item_type,mo.order_no,
                   EXISTS(SELECT 1 FROM execution_record rev WHERE rev.reversed_execution_id=er.id) is_reversed
            FROM execution_record er
            JOIN order_item oi ON oi.id=er.order_item_id
            JOIN medical_order mo ON mo.id=oi.medical_order_id
            JOIN staff s ON s.id=er.executor_id
            WHERE mo.admission_id=?
            ORDER BY er.execution_time DESC
            """,admissionId);
    }
}
