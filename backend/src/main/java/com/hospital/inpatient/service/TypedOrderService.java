package com.hospital.inpatient.service;

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
    Instant lastSuccessfulAdministration(Object line) {
        var row=g.jdbc.queryForList("SELECT MAX(er.execution_time) last_time FROM execution_record er WHERE er.order_item_id=? AND er.phase='ADMINISTER' AND er.status='SUCCESS' AND NOT EXISTS(SELECT 1 FROM execution_record rev WHERE rev.reversed_execution_id=er.id)",line).get(0);
        return row.get("last_time")==null ? null : ((Timestamp)row.get("last_time")).toInstant();
    }
    Map<String,Object> medicationPlan(Object line) {
        return g.row("SELECT mp.frequency_code,mf.name frequency_name,mf.interval_minutes,mf.once_only,mp.first_dose_at FROM medication_plan mp JOIN medication_frequency mf ON mf.code=mp.frequency_code WHERE mp.order_item_id=?",line);
    }

    public List<Map<String,Object>> catalog() {
        return g.jdbc.queryForList("""
            SELECT mi.*,dp.dose_unit,dp.dose_units_per_stock,
                   (SELECT GROUP_CONCAT(mr.name ORDER BY dpr.sort_order SEPARATOR ',')
                    FROM drug_profile_route dpr
                    JOIN medication_route mr ON mr.code=dpr.route_code
                    WHERE dpr.medical_item_id=mi.id) AS allowed_routes
            FROM medical_item mi LEFT JOIN drug_profile dp ON dp.medical_item_id=mi.id
            WHERE mi.active=TRUE AND mi.item_type<>'BED_FEE' ORDER BY mi.item_type,mi.id
            """);
    }

    public List<Map<String,Object>> medicationFrequencies() {
        return g.jdbc.queryForList("SELECT code,name,interval_minutes,once_only FROM medication_frequency WHERE active=TRUE ORDER BY once_only DESC,interval_minutes,code");
    }

    public List<Map<String,Object>> medicationRoutes() {
        return g.jdbc.queryForList("SELECT code,name FROM medication_route WHERE active=TRUE ORDER BY sort_order,code");
    }

    @Transactional
    public Map<String,Object> saveCatalog(com.hospital.inpatient.controller.TypedOrderController.Catalog body) {
        boolean drug=body.category().equals("DRUG");
        require(!body.billable() || body.price().signum()>0,"收费项目必须填写正数价格");
        require(body.billable() || body.price().signum()==0,"非收费项目价格应为零");
        require(!Set.of("NURSING","DIET","OTHER").contains(body.category()) || !body.billable(),"护理、饮食、其他指令不自动计费");
        List<String> routes=drug && body.allowedRoutes()!=null
            ? Arrays.stream(body.allowedRoutes().split(",")).map(String::trim).filter(route->!route.isBlank()).toList()
            : List.of();
        if(drug) {
            require(body.doseUnit()!=null && !body.doseUnit().isBlank() && body.doseUnitsPerStock()!=null && !routes.isEmpty(),"药品必须配置用量单位、换算关系和给药途径");
            require(routes.size()==routes.stream().distinct().count(),"给药途径不能重复");
            for(String route:routes)
                require(g.count("SELECT COUNT(*) FROM medication_route WHERE name=? AND active=TRUE",route)==1,"给药途径不合法或已停用");
        }
        long item;
        if(body.id()==null) {
            item=insert("INSERT INTO medical_item(item_code,name,item_type,unit,unit_price,specification,dosage_form,stock_quantity,billable,inventory_managed,execution_role,allow_fractional_quantity) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                body.code(),body.name(),body.category(),body.unit(),body.price(),body.specification(),body.dosageForm(),drug?BigDecimal.ZERO:null,body.billable(),drug,body.executionRole()==null?"NURSE":body.executionRole(),body.allowFractionalQuantity());
        } else {
            item=body.id(); var before=g.row("SELECT * FROM medical_item WHERE id=? FOR UPDATE",item);
            require(before.get("unit").equals(body.unit()) && before.get("item_type").equals(body.category()),"已有项目的分类和库存计价单位不能直接更换，请另建项目");
            g.jdbc.update("UPDATE medical_item SET item_code=?,name=?,unit_price=?,specification=?,dosage_form=?,billable=?,execution_role=?,allow_fractional_quantity=? WHERE id=?",body.code(),body.name(),body.price(),body.specification(),body.dosageForm(),body.billable(),body.executionRole()==null?"NURSE":body.executionRole(),body.allowFractionalQuantity(),item);
        }
        if(drug) {
            g.jdbc.update("INSERT INTO drug_profile(medical_item_id,dose_unit,dose_units_per_stock) VALUES(?,?,?) ON DUPLICATE KEY UPDATE dose_unit=VALUES(dose_unit),dose_units_per_stock=VALUES(dose_units_per_stock)",item,body.doseUnit(),body.doseUnitsPerStock());
            g.jdbc.update("DELETE FROM drug_profile_route WHERE medical_item_id=?",item);
            int sortOrder=1;
            for(String route:routes) {
                int inserted=g.jdbc.update("INSERT INTO drug_profile_route(medical_item_id,route_code,sort_order) SELECT ?,code,? FROM medication_route WHERE name=? AND active=TRUE",item,sortOrder++,route);
                require(inserted==1,"给药途径资料保存失败");
            }
        }
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
        Map<String,Long> groups=new HashMap<>();
        Map<String,String> groupFrequencies=new HashMap<>();
        Map<String,Instant> groupFirstDoses=new HashMap<>();
        Instant now=Instant.now();
        for(var line:request.items()) {
            var mi=g.row("SELECT * FROM medical_item WHERE id=? AND active=TRUE FOR UPDATE",line.medicalItemId());
            String category=(String)mi.get("item_type");
            require(!category.equals("BED_FEE"),"床位费不在医嘱中开立，请使用费用管理");
            boolean drug=category.equals("DRUG"), billable=yes(mi.get("billable"));
            String type=line.orderType();
            require(Set.of("TEMPORARY","LONG_TERM").contains(type),"医嘱类型不合法");
            require(!billable || decimal(mi.get("unit_price")).signum()>0,"收费项目价格无效");
            require(line.quantity()!=null && line.quantity().signum()>0,"项目数量必须大于零");
            if(Set.of("EXAMINATION","LAB","TREATMENT").contains(category) && "次".equals(mi.get("unit")))
                require(line.quantity().compareTo(BigDecimal.ONE)==0,"按次计价的检查、检验和治疗每次仅记录一次执行，数量必须为1");
            if(!yes(mi.get("allow_fractional_quantity"))) require(line.quantity().stripTrailingZeros().scale()<=0,"该项目数量必须为整数，单位："+mi.get("unit"));
            require(!Set.of("NURSING","DIET","OTHER").contains(category) || !billable,"护理、饮食及其他指令不得自动计费");
            require(drug || line.groupKey()==null || line.groupKey().isBlank(),"非药品项目不能加入配液组");
            require(drug || line.dosage()==null,"非药品项目不填写剂量");
            require(drug || (line.frequencyCode()==null && line.firstDoseAt()==null),"非药品项目不能填写给药计划");
            BigDecimal factor=null;
            String doseUnit=null;
            Map<String,Object> frequency=null;
            Instant firstDose=null;
            Instant start=line.startTime()==null ? now : line.startTime();
            if(drug) {
                require(line.frequencyCode()!=null && !line.frequencyCode().isBlank(),"药品必须填写给药频次");
                frequency=g.row("SELECT code,name,interval_minutes,once_only FROM medication_frequency WHERE code=? AND active=TRUE",line.frequencyCode());
                var profile=g.row("SELECT * FROM drug_profile WHERE medical_item_id=?",line.medicalItemId());
                doseUnit=(String)profile.get("dose_unit"); factor=decimal(profile.get("dose_units_per_stock"));
                require(factor.signum()>0,"请先维护有效的药品单位换算资料");
                require(line.dosage()!=null && line.dosage().signum()>0 && Objects.equals(doseUnit,line.doseUnit()),"请按药品资料填写单次用量与单位");
                require(line.route()!=null && g.count("""
                    SELECT COUNT(*)
                    FROM drug_profile_route dpr
                    JOIN medication_route mr ON mr.code=dpr.route_code
                    WHERE dpr.medical_item_id=? AND mr.name=? AND mr.active=TRUE
                    """,line.medicalItemId(),line.route())==1,"给药途径与药品资料不匹配");
                require(line.quantity().multiply(factor).compareTo(line.dosage())>=0,"本次发药数量不足一次给药用量");
                firstDose=line.firstDoseAt()==null ? start : line.firstDoseAt();
                boolean once=yes(frequency.get("once_only"));
                require(!type.equals("LONG_TERM") || !once,"长期医嘱不能使用一次性给药频次");
                require(!type.equals("TEMPORARY") || once || line.endTime()!=null,"临时规律用药必须设置疗程结束时间");
                require(!firstDose.isBefore(start) && (line.endTime()==null || firstDose.isBefore(line.endTime())),"首次给药时间必须位于医嘱有效时间窗内");
            }
            require(line.endTime()==null || line.endTime().isAfter(start),"结束时间必须晚于开始时间");
            Long group=null;
            if(drug && line.groupKey()!=null && !line.groupKey().isBlank()) {
                require(Set.of("静脉滴注","静脉注射").contains(line.route()),"配液组仅用于明确的注射给药途径");
                group=groups.get(line.groupKey());
                if(group==null) {
                    group=insert("INSERT INTO order_group(medical_order_id,name,route,order_type,frequency_code,start_time,end_time) VALUES(?,?,?,?,?,?,?)",order,line.groupKey(),line.route(),type,frequency.get("code"),Timestamp.from(firstDose),line.endTime()==null?null:Timestamp.from(line.endTime()));
                    groups.put(line.groupKey(),group);
                    groupFrequencies.put(line.groupKey(),(String)frequency.get("code"));
                    groupFirstDoses.put(line.groupKey(),firstDose);
                } else {
                    var previous=g.row("SELECT * FROM order_group WHERE id=?",group);
                    require(previous.get("route").equals(line.route()) && previous.get("order_type").equals(type)
                        && previous.get("frequency_code").equals(frequency.get("code"))
                        && ((Timestamp)previous.get("start_time")).toInstant().equals(firstDose)
                        && Objects.equals(previous.get("end_time"),line.endTime()==null?null:Timestamp.from(line.endTime())),"同一配液组的途径、类型、频次和起止时间必须一致");
                    require(Objects.equals(groupFrequencies.get(line.groupKey()),frequency.get("code"))
                        && Objects.equals(groupFirstDoses.get(line.groupKey()),firstDose),"同一配液组的给药频次和首次给药时间必须一致");
                }
            }
            long orderItem=insert("""
                INSERT INTO order_item(medical_order_id,medical_item_id,dosage,quantity_per_execution,route,instructions,
                group_id,item_name_snapshot,item_type_snapshot,unit_snapshot,dose_unit,dose_units_per_stock,price_snapshot,
                billable,inventory_managed,execution_role,order_type,start_time,end_time)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,order,line.medicalItemId(),line.dosage(),line.quantity(),drug?line.route():null,line.instructions(),group,
                mi.get("name"),category,mi.get("unit"),doseUnit,factor,mi.get("unit_price"),billable,yes(mi.get("inventory_managed")),
                drug?"NURSE":mi.get("execution_role"),type,Timestamp.from(start),line.endTime()==null?null:Timestamp.from(line.endTime()));
            if(drug) g.jdbc.update("INSERT INTO medication_plan(order_item_id,frequency_code,first_dose_at) VALUES(?,?,?)",orderItem,frequency.get("code"),Timestamp.from(firstDose));
        }
        for(long group:groups.values()) require(g.count("SELECT COUNT(*) FROM order_item WHERE group_id=?",group)>=2,"一个配液组至少包含两种药品；单药请取消分组");
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
            lines=lines.stream().filter(l->target.get("group_id")==null ? id(l.get("id"))==lineId : Objects.equals(l.get("group_id"),target.get("group_id"))).toList();
        }
        int changed=0;
        for(var line:lines) {
            String status=(String)line.get("item_status");
            if(Set.of("COMPLETED","STOPPED","CANCELLED").contains(status)) continue;
            String next;
            switch(action) {
                case "submit" -> { require(status.equals("DRAFT"),"只有草稿可以提交"); next="SUBMITTED"; }
                case "stop" -> { require(line.get("order_type").equals("LONG_TERM") && !status.equals("DRAFT"),"只有已提交的长期医嘱可以停止"); next="STOPPED"; }
                case "cancel" -> { require(decimal(line.get("dispensed_quantity")).signum()==0 && decimal(line.get("executed_quantity")).signum()==0 && decimal(line.get("administered_quantity")).signum()==0,"已发药或已执行项目不能直接撤销"); next="CANCELLED"; }
                case "complete" -> {
                    require(!status.equals("DRAFT") && decimal(line.get("executed_quantity")).signum()>0,"需至少完成一次实际执行才能完成医嘱");
                    if("DRUG".equals(line.get("item_type_snapshot"))) {
                        Map<String,Object> plan=medicationPlan(line.get("id"));
                        long administered=successfulAdministrations(line.get("id"));
                        if(yes(plan.get("once_only"))) {
                            require(administered>=1,"一次性药品必须先完成一次给药");
                        } else {
                            Object end=line.get("end_time");
                            require(end!=null && !((Timestamp)end).toInstant().isAfter(Instant.now()),"规律用药须到达疗程结束时间后才能完成");
                        }
                    }
                    next="COMPLETED";
                }
                default -> throw new BusinessException("INVALID_ACTION","操作不合法");
            }
            g.jdbc.update("UPDATE order_item SET item_status=? WHERE id=?",next,line.get("id")); changed++;
        }
        require(changed>0,"没有符合操作条件的项目");
        if(action.equals("submit")) g.jdbc.update("UPDATE medical_order SET submitted_at=UTC_TIMESTAMP(3) WHERE id=?",order);
        refresh(order);
        audit.success("ORDER_"+action.toUpperCase(),"medical_order",order,null,Map.of("itemCount",changed));
    }

    void refresh(long order) {
        var states=g.jdbc.queryForList("SELECT item_status FROM order_item WHERE medical_order_id=?",String.class,order);
        String status=states.stream().allMatch("DRAFT"::equals)?"DRAFT":states.contains("IN_PROGRESS")?"IN_PROGRESS":states.contains("SUBMITTED")?"SUBMITTED":states.contains("DRAFT")?"DRAFT":states.stream().allMatch("CANCELLED"::equals)?"CANCELLED":states.stream().allMatch("STOPPED"::equals)?"STOPPED":"COMPLETED";
        g.jdbc.update("UPDATE medical_order SET status=? WHERE id=?",status,order);
    }

    public List<Map<String,Object>> pending(String phase,String itemType) {
        require(Set.of("DISPENSE","ADMINISTER","PERFORM").contains(phase),"请选择执行环节");
        StringBuilder sql=new StringBuilder("""
            SELECT oi.*,oi.id order_item_id,mo.id order_id,mo.order_no,mo.admission_id,p.name patient_name,
            d.name department_name,oi.item_name_snapshot item_name,oi.item_type_snapshot item_type,
            oi.unit_snapshot unit,mi.stock_quantity,og.name group_name,
            mp.frequency_code,mp.frequency_name,mp.interval_minutes,mp.once_only,
            mp.first_dose_at,mp.administration_count,mp.last_administration_at,mp.next_administration_at,
            CASE WHEN ?='ADMINISTER' THEN oi.dosage ELSE oi.quantity_per_execution END task_quantity,
            CASE WHEN ?='ADMINISTER' THEN oi.dose_unit ELSE oi.unit_snapshot END task_unit
            FROM order_item oi JOIN medical_order mo ON mo.id=oi.medical_order_id
            JOIN admission a ON a.id=mo.admission_id JOIN patient p ON p.id=a.patient_id
            JOIN department d ON d.id=a.department_id JOIN medical_item mi ON mi.id=oi.medical_item_id
            LEFT JOIN order_group og ON og.id=oi.group_id
            LEFT JOIN v_medication_plan_progress mp ON mp.order_item_id=oi.id
            WHERE a.status='IN_HOSPITAL' AND oi.item_status IN ('SUBMITTED','IN_PROGRESS')
            AND oi.start_time<=UTC_TIMESTAMP(3) AND (oi.end_time IS NULL OR oi.end_time>UTC_TIMESTAMP(3))
            """);
        List<Object> args=new ArrayList<>(List.of(phase,phase));
        if(phase.equals("DISPENSE")) { if(!role("PHARMACY")) return List.of(); sql.append(" AND oi.item_type_snapshot='DRUG'"); }
        else if(phase.equals("ADMINISTER")) { if(!role("NURSE")) return List.of(); sql.append(" AND oi.item_type_snapshot='DRUG' AND mp.next_administration_at<=UTC_TIMESTAMP(3)"); }
        else { sql.append(" AND oi.item_type_snapshot<>'DRUG'"); if(!role("ADMIN")) { sql.append(" AND oi.execution_role IN ("); var roles=SecurityUtils.currentUser().roles().stream().filter(r->Set.of("NURSE","DOCTOR").contains(r)).toList(); if(roles.isEmpty()) return List.of(); sql.append(String.join(",",Collections.nCopies(roles.size(),"?"))).append(")"); args.addAll(roles); } }
        if(SecurityUtils.doctorScope()!=null) { sql.append(" AND a.attending_doctor_id=?"); args.add(SecurityUtils.doctorScope()); }
        if(SecurityUtils.departmentScope()!=null) { sql.append(" AND a.department_id=?"); args.add(SecurityUtils.departmentScope()); }
        if(itemType!=null && !itemType.isBlank()) { sql.append(" AND oi.item_type_snapshot=?"); args.add(itemType); }
        sql.append(" ORDER BY mo.submitted_at,oi.id");
        return g.jdbc.queryForList(sql.toString(),args.toArray());
    }

    @Transactional
    public Map<String,Object> execute(Requests.OrderExecution request) {
        long order=id(g.row("SELECT medical_order_id FROM order_item WHERE id=?",request.orderItemId()).get("medical_order_id"));
        long admission=g.orderAdmission(order); g.lockInHospital(admission);
        require(current(order),"医嘱不存在或已归档");
        var target=g.row("SELECT * FROM order_item WHERE id=? FOR UPDATE",request.orderItemId());
        var lines=target.get("group_id")==null ? List.of(target) : g.jdbc.queryForList("SELECT * FROM order_item WHERE group_id=? ORDER BY id FOR UPDATE",target.get("group_id"));
        require(!request.notDone() || (request.note()!=null && !request.note().isBlank()),"未执行时必须填写原因");
        require(!request.notDone() || !request.phase().equals("DISPENSE"),"发药失败不产生发药流水");
        List<Long> executions=new ArrayList<>();
        for(var line:lines) executions.add(executeLine(admission,request,line));
        refresh(order); g.refreshAlerts(admission);
        audit.success("ORDER_"+request.phase(),"medical_order",order,null,Map.of("executionIds",executions));
        return Map.of("executionIds",executions);
    }

    long executeLine(long admission,Requests.OrderExecution request,Map<String,Object> line) {
        require(Set.of("SUBMITTED","IN_PROGRESS").contains(line.get("item_status")),"医嘱项目当前不可执行");
        Instant now=Instant.now();
        require(!((Timestamp)line.get("start_time")).toInstant().isAfter(now) && (line.get("end_time")==null || ((Timestamp)line.get("end_time")).toInstant().isAfter(now)),"医嘱尚未生效或已超过结束时间");
        boolean drug="DRUG".equals(line.get("item_type_snapshot"));
        String phase=request.phase();
        boolean allowed=phase.equals("DISPENSE") ? drug && role("PHARMACY") : phase.equals("ADMINISTER") ? drug && role("NURSE") : !drug && role((String)line.get("execution_role"));
        if(!allowed) throw new BusinessException("FORBIDDEN","当前岗位不能处理该项目的这一环节",HttpStatus.FORBIDDEN);
        Map<String,Object> plan=drug ? medicationPlan(line.get("id")) : null;
        String key=UUID.nameUUIDFromBytes((request.requestNo()+":"+line.get("id")).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        require(g.count("SELECT COUNT(*) FROM execution_record WHERE request_no=?",key)==0,"该请求已处理，请刷新查看记录");
        BigDecimal quantity=decimal(line.get(phase.equals("ADMINISTER")?"dosage":"quantity_per_execution"));
        long done=phase.equals("ADMINISTER") ? successfulAdministrations(line.get("id")) : 0;
        if(phase.equals("ADMINISTER")) {
            Instant firstDose=((Timestamp)plan.get("first_dose_at")).toInstant();
            require(!firstDose.isAfter(now),"尚未到首次给药时间");
            if(yes(plan.get("once_only"))) {
                require(done==0,"一次性药品已经完成给药");
            } else if(done>0) {
                Instant last=lastSuccessfulAdministration(line.get("id"));
                int interval=((Number)plan.get("interval_minutes")).intValue();
                require(last!=null && !last.plusSeconds(interval*60L).isAfter(now),"尚未到下一次给药时间，请按医嘱频次执行");
            }
        }
        if(phase.equals("ADMINISTER") && !request.notDone()) require(decimal(line.get("dispensed_quantity")).multiply(decimal(line.get("dose_units_per_stock"))).subtract(decimal(line.get("administered_quantity"))).compareTo(quantity)>=0,"已发药余量不足本次用量，请先核对药房发药");
        var mi=g.row("SELECT * FROM medical_item WHERE id=? AND active=TRUE FOR UPDATE",line.get("medical_item_id"));
        BigDecimal before=decimal(mi.get("stock_quantity"));
        boolean inventory=phase.equals("DISPENSE") && yes(line.get("inventory_managed"));
        if(inventory) require(before.compareTo(quantity)>=0,"药品库存不足，本次操作已回滚");
        String unit=(String)line.get(phase.equals("ADMINISTER")?"dose_unit":"unit_snapshot");
        long execution=insert("INSERT INTO execution_record(request_no,order_item_id,quantity,status,result_note,executor_id,phase,unit_snapshot,batch_key) VALUES(?,?,?,?,?,?,?,?,?)",key,line.get("id"),request.notDone()?BigDecimal.ZERO:quantity,request.notDone()?"NOT_DONE":"SUCCESS",request.note(),staff(),phase,unit,request.requestNo());
        if(request.notDone()) return execution;
        if(inventory) {
            g.jdbc.update("UPDATE medical_item SET stock_quantity=stock_quantity-?,version=version+1 WHERE id=?",quantity,line.get("medical_item_id"));
            g.jdbc.update("INSERT INTO drug_inventory_transaction(medical_item_id,execution_id,transaction_type,quantity,before_quantity,after_quantity,note,operated_by) VALUES(?,?,'DISPENSE',?,?,?,'药房发药',?)",line.get("medical_item_id"),execution,quantity.negate(),before,before.subtract(quantity),SecurityUtils.currentUser().id());
        }
        if(!phase.equals("ADMINISTER") && yes(line.get("billable"))) {
            BigDecimal price=decimal(line.get("price_snapshot")),amount=price.multiply(quantity).setScale(2,RoundingMode.HALF_UP);
            require(amount.signum()>0,"费用金额无效");
            long department=id(g.row("SELECT department_id FROM admission WHERE id=?",admission).get("department_id"));
            g.jdbc.update("INSERT INTO charge_detail(admission_id,execution_id,medical_item_id,item_code_snapshot,item_name_snapshot,unit_snapshot,department_id,charge_no,charge_type,item_type_snapshot,unit_price_snapshot,quantity,amount,note) VALUES(?,?,?,?,?,?,?,?,'NORMAL',?,?,?,?,?)",
                admission,execution,line.get("medical_item_id"),mi.get("item_code"),line.get("item_name_snapshot"),line.get("unit_snapshot"),department,
                ChargeNumber.next(g.jdbc,false),line.get("item_type_snapshot"),price,quantity,amount,phase.equals("DISPENSE")?"药房发药计费":"项目执行计费");
        }
        if(phase.equals("DISPENSE")) g.jdbc.update("UPDATE order_item SET dispensed_quantity=dispensed_quantity+?,item_status='IN_PROGRESS' WHERE id=?",quantity,line.get("id"));
        else if(phase.equals("ADMINISTER")) g.jdbc.update("UPDATE order_item SET administered_quantity=administered_quantity+?,executed_quantity=executed_quantity+?,item_status=? WHERE id=?",quantity,quantity,yes(plan.get("once_only"))?"COMPLETED":"IN_PROGRESS",line.get("id"));
        else g.jdbc.update("UPDATE order_item SET executed_quantity=executed_quantity+?,item_status=? WHERE id=?",quantity,
            "TEMPORARY".equals(line.get("order_type"))?"COMPLETED":"IN_PROGRESS",line.get("id"));
        return execution;
    }

    @Transactional
    public void reverse(long execution,String reason) {
        long admission=g.executionAdmission(execution); g.lockInHospital(admission);
        var original=g.row("SELECT er.*,oi.medical_order_id,oi.group_id FROM execution_record er JOIN order_item oi ON oi.id=er.order_item_id WHERE er.id=?",execution);
        var records=original.get("group_id")==null ? List.of(original) : g.jdbc.queryForList("SELECT er.* FROM execution_record er JOIN order_item oi ON oi.id=er.order_item_id WHERE er.batch_key=? AND oi.group_id=? AND er.phase=? ORDER BY er.id FOR UPDATE",original.get("batch_key"),original.get("group_id"),original.get("phase"));
        for(var record:records) {
            require("SUCCESS".equals(record.get("status")) && g.count("SELECT COUNT(*) FROM execution_record WHERE reversed_execution_id=?",record.get("id"))==0,"执行不存在、未成功或已经冲正");
            var line=g.row("SELECT * FROM order_item WHERE id=? FOR UPDATE",record.get("order_item_id"));
            String phase=(String)record.get("phase");
            boolean allowed=phase.equals("DISPENSE")?role("PHARMACY"):phase.equals("ADMINISTER")?role("NURSE"):role((String)line.get("execution_role"));
            if(!allowed) throw new BusinessException("FORBIDDEN","当前岗位无权冲正该环节",HttpStatus.FORBIDDEN);
            if(phase.equals("DISPENSE")) require(decimal(line.get("administered_quantity")).signum()==0,"已有给药记录，请先核对给药，不能整笔退回已使用药品");
            BigDecimal quantity=decimal(record.get("quantity"));
            long reversed=insert("INSERT INTO execution_record(request_no,order_item_id,quantity,status,result_note,executor_id,reversed_execution_id,phase,unit_snapshot) VALUES(?,?,?,'REVERSED',?,?,?,?,?)",UUID.randomUUID().toString(),line.get("id"),quantity,reason,staff(),record.get("id"),phase,record.get("unit_snapshot"));
            if(phase.equals("DISPENSE") && yes(line.get("inventory_managed"))) {
                var mi=g.row("SELECT stock_quantity FROM medical_item WHERE id=? FOR UPDATE",line.get("medical_item_id"));
                BigDecimal before=decimal(mi.get("stock_quantity"));
                g.jdbc.update("UPDATE medical_item SET stock_quantity=stock_quantity+?,version=version+1 WHERE id=?",quantity,line.get("medical_item_id"));
                g.jdbc.update("INSERT INTO drug_inventory_transaction(medical_item_id,execution_id,transaction_type,quantity,before_quantity,after_quantity,note,operated_by) VALUES(?,?,'RETURN',?,?,?,?,?)",line.get("medical_item_id"),reversed,quantity,before,before.add(quantity),reason,SecurityUtils.currentUser().id());
            }
            for(var charge:g.jdbc.queryForList("SELECT * FROM charge_detail WHERE execution_id=? AND charge_type='NORMAL' FOR UPDATE",record.get("id")))
                g.jdbc.update("INSERT INTO charge_detail(admission_id,execution_id,medical_item_id,item_code_snapshot,item_name_snapshot,unit_snapshot,department_id,charge_no,charge_type,item_type_snapshot,unit_price_snapshot,quantity,amount,original_charge_id,note) VALUES(?,?,?,?,?,?,?,?,'REVERSAL',?,?,?,?,?,?)",
                    admission,reversed,line.get("medical_item_id"),charge.get("item_code_snapshot"),charge.get("item_name_snapshot"),charge.get("unit_snapshot"),charge.get("department_id"),
                    ChargeNumber.next(g.jdbc,true),charge.get("item_type_snapshot"),charge.get("unit_price_snapshot"),decimal(charge.get("quantity")).negate(),decimal(charge.get("amount")).negate(),charge.get("id"),reason);
            if(phase.equals("DISPENSE")) g.jdbc.update("UPDATE order_item SET dispensed_quantity=dispensed_quantity-? WHERE id=?",quantity,line.get("id"));
            else if(phase.equals("ADMINISTER")) g.jdbc.update("UPDATE order_item SET administered_quantity=administered_quantity-?,executed_quantity=executed_quantity-? WHERE id=?",quantity,quantity,line.get("id"));
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
            SELECT er.id,er.request_no,er.execution_time,er.quantity,er.status,er.result_note,er.phase,
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
