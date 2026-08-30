package com.hospital.inpatient.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Seeds the starter order templates.
 * order_template.created_by references user_account, which Flyway cannot populate:
 * the accounts are created by {@link AdminBootstrap} after the migrations run.
 * So the templates are seeded here instead, once an owner exists. */
@Component
@Order(2)
public class TemplateBootstrap implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    public TemplateBootstrap(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Template(String name, String description, List<String> itemCodes) {}

    private static final List<Template> TEMPLATES = List.of(
        new Template("肺部感染常用检查", "胸部影像、血常规、C反应蛋白及血培养；医师需逐项确认。",
            List.of("EXAM002", "LAB001", "LAB002", "LAB003")),
        new Template("肝肾功能检查", "肝功能与肾功能维护套餐；展开后形成独立项目。",
            List.of("LAB004", "LAB005")),
        new Template("肺炎住院基础医嘱", "常用用药、检查、护理和观察项目；不代表自动配伍或推荐剂量。",
            List.of("DRUG006", "EXAM002", "LAB001", "NURS002", "OTHER002")),
        new Template("糖尿病住院管理", "血糖检查、饮食、风险防护和胰岛素项目；剂量仍需医师确认。",
            List.of("LAB006", "DRUG008", "DIET005", "NURS006", "OTHER003")),
        new Template("术前基础检查", "血常规、肝肾功能、凝血功能、心电图等常用术前项目。",
            List.of("LAB001", "LAB004", "LAB005", "LAB007", "EXAM003"))
    );

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Long owner = jdbc.query("SELECT id FROM user_account ORDER BY id LIMIT 1",
                rs -> rs.next() ? rs.getLong("id") : null);
        if (owner == null) {
            return;
        }
        for (Template template : TEMPLATES) {
            // Idempotent by name so a restart never duplicates the starter set.
            if (jdbc.queryForObject("SELECT COUNT(*) FROM order_template WHERE name=?", Integer.class, template.name()) > 0) {
                continue;
            }
            jdbc.update("INSERT INTO order_template(name,description,created_by) VALUES(?,?,?)",
                    template.name(), template.description(), owner);
            Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            int sortOrder = 0;
            for (String code : template.itemCodes()) {
                jdbc.update("""
                        INSERT INTO order_template_item(template_id,medical_item_id,sort_order)
                        SELECT ?,id,? FROM medical_item WHERE item_code=?
                        """, id, sortOrder++, code);
            }
        }
    }
}
