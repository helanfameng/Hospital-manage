# 小型医院住院信息管理与费用结算系统

数据库课程设计项目。系统以一次住院记录 `admission` 为业务主轴，围绕床位历史、医嘱执行和费用流水实现住院管理与费用结算。

## 当前版本：住院临床扩展版（V1–V14）

本目录保留住院临床扩展版本。完整迁移链为 V1–V14：V1–V5 提供重建基线，V6 加固完整性，V7–V9 增加护理观察、检查检验结果、住院诊断和签署临床记录，V10–V12 调整给药计划、给药途径及 PRN 范围，V13–V14 补齐费用历史快照。

当前结构为 **39 张应用表、7 个视图、5 个存储过程、49 个触发器**；Flyway 安装时另建历史表。V5 提供 30 位患者、100 张床位及关联演示数据。

医嘱项目通过频次、首次给药时间和有效时间窗决定给药资格，支持配液组共同执行，当前频次模型不包含 PRN。药房发药 `DISPENSE` 扣库存并计费，护士给药 `ADMINISTER` 不重复收费，非药品使用 `PERFORM`。执行、费用、押金、库存和结算保留原始事实，错误通过追加冲正更正。

### 历史验证记录

以下为原文档中 2026-08-29 留存的记录，不代表本次重新执行；当时的构建产物和过程目录已清理。

| 检查项 | 留存记录 |
| --- | --- |
| 后端 | Maven 记录为 52 项、0 失败，其中 40 项数据库集成测试跳过；V14 的真实 MySQL 集成和端到端回归仍待执行 |
| 前端 | 12 个测试文件、69 项测试及类型检查、生产构建曾通过 |
| 端到端 SQL | V14 脚本已同步；历史样本为押金 1000、费用 28.50、退款 971.50、库存 500→499，尚未完成 V14 真实库复核 |

当前验证应重新运行本文及 [测试计划](docs/测试计划.md) 中的命令。项目定位为课程设计与开发演示，尚未完成生产级负载、灾备、渗透和全浏览器验收。

### 迁移边界

V1–V5 是重建基线，不能直接接续旧开发链数据库；V6–V14 在该基线之上依次执行。旧环境需要先备份，再按 [部署说明](docs/部署说明.md) 新建目标库；保留历史数据时应使用经过对账的迁移方案。

## 技术栈

- Java 17、Spring Boot 3.5、Spring Security、JWT、MyBatis、Flyway
- MySQL 8.0 / InnoDB / utf8mb4
- Vue 3、TypeScript、Vite 8、Element Plus、Pinia、ECharts

## 项目结构

```text
backend/       Spring Boot 后端、Flyway 迁移与自动化测试
frontend/      Vue 管理端
database/      可独立执行的数据库脚本
docs/          需求、ER、数据字典、API、测试与运维文档
```

## 1. 初始化数据库

使用具有建库和授权权限的 MySQL 管理账号执行一次：

```sql
CREATE DATABASE hospital_inpatient
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE hospital_inpatient_test
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE hospital_inpatient_sql_test
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER 'hospital_app'@'localhost' IDENTIFIED BY '<自行设置强密码>';
GRANT ALL PRIVILEGES ON hospital_inpatient.* TO 'hospital_app'@'localhost';
GRANT ALL PRIVILEGES ON hospital_inpatient_test.* TO 'hospital_app'@'localhost';
GRANT ALL PRIVILEGES ON hospital_inpatient_sql_test.* TO 'hospital_app'@'localhost';

-- 开启二进制日志的 MySQL 创建触发器时需要此开关。
-- SET GLOBAL 仅持续到 MySQL 下次重启，首次迁移完成后运行期不再依赖它。
SET GLOBAL log_bin_trust_function_creators = 1;
FLUSH PRIVILEGES;
```

不要把实际密码或 JWT 密钥写入仓库。PowerShell 会话中设置：

```powershell
$env:HOSPITAL_DB_URL='jdbc:mysql://localhost:3306/hospital_inpatient?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowMultiQueries=true'
$env:HOSPITAL_DB_USERNAME='hospital_app'
$env:HOSPITAL_DB_PASSWORD='<数据库密码>'
$env:HOSPITAL_JWT_SECRET='<至少32字节的随机字符串>'
$env:HOSPITAL_BOOTSTRAP_ADMIN_PASSWORD='<首次启动六个演示账号的强密码>'
```

若同时保留另一套版本，应为两套版本分别建立业务库和测试库，并在启动前显式设置对应 URL。

后端启动时运行 Flyway。仅当账号表为空且设置了 `HOSPITAL_BOOTSTRAP_ADMIN_PASSWORD` 时，才会创建六个演示账号：`admin`、`admission`、`doctor`、`nurse`、`pharmacy`、`cashier`。这些账号共用该初始密码；登录后通过右上角“修改密码”设置个人密码，并重新登录。演示账号不能直接用于正式部署。查看当前账号：管理员登录后进入“系统管理 → 用户”；数据库可查询 `SELECT id,username,display_name,enabled FROM user_account`，不要输出密码哈希。

**演示数据提示：**默认基线的 `V5__seed.sql` 会生成演示患者、住院、医嘱、费用等数据。首次启动应连接专用开发/演示库；接入真实业务数据前，应另行拆分或替换种子数据策略。

如需独立演示 SQL，请在另一个空演示库中按顺序执行，避免与 Flyway 自动安装混用：

```text
database/schema.sql
database/indexes.sql
database/views.sql
database/routines.sql
database/seed.sql
database/integrity-hardening.sql
database/nursing-observation.sql
database/inpatient-result.sql
database/inpatient-clinical-record.sql
database/medication-schedule.sql
database/medication-route-normalization.sql
database/remove-prn-frequency.sql
database/charge-category-snapshot.sql
database/charge-catalog-snapshot.sql
database/verify.sql
# 可选：仅在以 _test 结尾的独立测试库运行，先通过后端初始化测试账号
database/verified-workflow.sql
```

## 2. 启动后端

```powershell
cd backend
.\mvnw.cmd -v
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

后端使用 Maven Wrapper 固定为 Maven 3.9.11；首次执行会下载 Wrapper JAR 和 Maven，需要 JDK 17、curl 与网络，已有缓存时可复用。后端地址为 `http://localhost:8080`，健康检查为 `/actuator/health`。

真实 MySQL 集成测试默认关闭。准备好独立测试库后，务必显式设置 `HOSPITAL_TEST_DB_URL` 再开启测试：

```powershell
$env:HOSPITAL_TEST_DB_ENABLED='true'
$env:HOSPITAL_TEST_DB_URL='jdbc:mysql://localhost:3306/hospital_inpatient_test?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowMultiQueries=true'
.\mvnw.cmd test
```

测试配置不再回退到业务库。测试上下文在 Flyway 运行前拒绝缺失 URL、非本机地址、不以 `_test` 结尾或与业务 URL 相同的目标，并在类级前置检查中读取 `log_bin_trust_function_creators` 与失败的 Flyway 记录；检查只读，不会自动修改全局配置或迁移元数据。仍须自行确认它确实是可写入的专用测试库。测试会保留带随机标识的夹具，不清空数据库。

## 3. 启动前端

```powershell
cd frontend
npm.cmd ci
npm.cmd run dev
```

浏览器访问 `http://localhost:5173`。生产构建：

```powershell
npm.cmd test
npm.cmd run build
```

## 4. 推荐演示流程

以下正常与异常事务已有回归测试；实际页面演示建议使用新建患者，便于独立核对账目。

1. 住院处登记患者、缴纳押金并分配床位。
2. 医生开立药品和检查医嘱并提交。
3. 药房发药并扣库存、自动计费；护士随后记录给药、在住院详情的“护理观察”页签记录生命体征，并为成功的住院检查/检验执行登记结果；医生可在“诊疗记录”页签登记诊断和签署病程记录。
4. 重放相同 `requestNo` 应被拒绝且无重复流水；药品按频次到点给药，新的发药请求可记录新的库存批量，但不会凭发药量增加实际给药次数。需退药时先完整冲正，必要时重新执行。
5. 转入占用床位应失败且原记录不变；跨科转床前处理原医嘱、选择目标科室医生，核对科室/医生/床位与历史同步。
6. 费用超过押金阈值后查看欠费提醒并补交押金。
7. 未完成医嘱时尝试结算，验证系统阻止；先处理完医嘱，再申请出院并完成结算，核对床位释放。
8. 展示床位使用率、收入构成、药品排行和平均住院天数。

## 5. 关键设计

- 业务以一次住院为锁与账目的边界；先锁住院主记录，再处理子流水，避免结算和补交/冲正读取不同版本的账目。
- 重复请求返回冲突，不重复生成事实；重试应复用原请求号并刷新核对。长期医嘱的新一次执行才生成新的请求号。
- 冲正追加反向事实，不修改原成功记录；查询通过原记录引用识别已冲正状态。
- 四个核心存储过程由调用方管理事务；独立 SQL 调用需显式事务与失败回滚，示例见 `database/verified-workflow.sql`。
- 浏览器按钮不是权限边界：后端校验当前权限、住院数据范围、业务状态与并发约束。
- 药品计划以 `medication_frequency` 作为参考主数据，以 `medication_plan(order_item_id)` 作为药品项目的一对一计划子类型；`first_dose_at`、`start_time/end_time` 与频次间隔共同决定下一次给药资格，实际次数从不可变执行事实派生，不把库存批量或人工总次数当作临床事实。药品途径以 `medication_route` 和 `drug_profile_route` 建模多值关系，医嘱仍保存发生时途径快照。
- 护理观察以一次住院为归属，不引入门诊就诊；观察原始事实只追加不修改/删除，更正必须作为新的观察记录保存。
- 检查/检验结果以住院医嘱执行为唯一来源，不允许脱离住院执行凭空登记；一条执行可拆分多个检验项目，结果更正通过 `amends_result_id` 追加并保留原始结果。
- 住院诊断引用启用的诊断目录并保存编码/名称快照；主要诊断在同一次住院中只能有一个有效项，诊断只能从现症转为已解决或已排除。
- 住院临床记录只保存受控的入院评估、病程、病情变化和出院小结；签署记录不可修改/删除，更正通过同一住院、同一记录类型的自引用新记录追加。
- 当前目录保留源码和技术文档，不附带原本机 Git 历史、构建产物或开发过程文件。

## 6. 文档导航与维护约定

| 文档 | 用途 |
| --- | --- |
| [总体方案](住院信息系统课程设计总体方案.md) | 需求与设计基线，不代表各项功能均已实现 |
| [需求说明](docs/需求说明.md) | 业务范围、角色权限与规则 |
| [实体关系设计](docs/实体关系设计.md) / [完整实体关系图源文件](docs/医院完整实体关系图.mmd) | 数据模型、关系和建模取舍 |
| [数据字典](docs/数据字典.md) / [建表脚本](database/schema.sql) | 数据表、字段及约束；完整字段以 SQL 为准 |
| [接口说明](docs/接口说明.md) | 接口概览；具体请求与行为以当前代码为准 |
| [测试计划](docs/测试计划.md) | 测试用例及历史验收记录 |
| [部署说明](docs/部署说明.md) / [备份恢复](docs/备份与恢复.md) | 环境配置与运维方案 |

项目当前进度统一维护在本 README，不再单独保留交接文档。新增或修复功能时，应同步更新进展、待办、测试证据和相关设计文档；“已有代码”“测试通过”“完成验收”应分别记录，不将计划或历史记录当作当前验证结果。
