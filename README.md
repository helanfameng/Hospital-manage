# 小型医院住院信息管理与费用结算系统

数据库课程设计项目。系统以一次住院记录 `admission` 为业务主轴，围绕床位历史、医嘱执行和费用流水实现住院管理与费用结算。

## 当前进展：V1–V5 重建基线

2026-08-29 已完成结构重建和兼容层清理。原 V1–V13 开发迁移已折叠为可从空库直接安装的五个基线迁移：结构、索引、视图、过程/触发器和种子数据。医嘱头不再保存旧的类型、频次、工作流版本和归档兼容字段；长期/临时属性保留在项目与配液组层级。

当前医嘱工作流明确分为药房发药 `DISPENSE`、护士给药 `ADMINISTER` 和非药品执行 `PERFORM`。药品在发药时扣库存并计费，给药不重复收费；执行、费用、押金、库存、转床、结算和审计采用追加事实与冲正记录，不覆盖原流水。

数据库基线由 `V1__schema.sql` 至 `V5__seed.sql` 构成：V1 定义 30 张应用表，另有 6 个视图、5 个存储过程和 20 个保护触发器。V5 提供 30 位患者、100 张床位以及约 390 条关联演示数据，满足课程设计的实体与关联规模要求。

### 当前验证证据

| 检查项 | 2026-08-29 结果 |
| --- | --- |
| 后端 | 45 项测试通过，其中 35 项为真实 MySQL 集成测试；无失败、无跳过 |
| 前端 | TypeScript 类型检查通过；12 个测试文件、71 项测试通过；Vite 生产构建成功 |
| SQL 镜像 | `database/` 的五个基线脚本与 Flyway 迁移逐字节一致 |
| 端到端 SQL | `database/verified-workflow.sql` 在独立 `*_test` 库通过：押金 1000、费用 28.50、退款 971.50、库存 500→499、余额归零并释放床位 |

生产构建仍提示部分前端分块大于 500 kB，这是性能优化提示，不影响功能正确性。项目定位为课程设计与开发演示，尚未完成生产级负载、灾备、渗透和全浏览器验收。

### 迁移边界

V1–V5 是重建后的新基线，不是给旧 V6/V8/V13 数据库继续追加的原地升级包。旧环境必须先备份，再按 [部署说明](docs/部署说明.md) 新建或重建目标库；需要保留的历史数据应通过经过核对的导出/导入方案迁移，不能修改 Flyway 校验值或直接 `repair` 掩盖不一致。

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
CREATE DATABASE hospital_inpatient_sqltest
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER 'hospital_app'@'localhost' IDENTIFIED BY '<自行设置强密码>';
GRANT ALL PRIVILEGES ON hospital_inpatient.* TO 'hospital_app'@'localhost';
GRANT ALL PRIVILEGES ON hospital_inpatient_test.* TO 'hospital_app'@'localhost';
GRANT ALL PRIVILEGES ON hospital_inpatient_sqltest.* TO 'hospital_app'@'localhost';

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

后端启动时运行 Flyway。仅当账号表为空且设置了 `HOSPITAL_BOOTSTRAP_ADMIN_PASSWORD` 时，才会创建六个演示账号：`admin`、`admission`、`doctor`、`nurse`、`pharmacy`、`cashier`。这些账号共用该初始密码；登录后通过右上角“修改密码”设置个人密码，并重新登录。演示账号不能直接用于正式部署。查看当前账号：管理员登录后进入“系统管理 → 用户”；数据库可查询 `SELECT id,username,display_name,enabled FROM user_account`，不要输出密码哈希。

**演示数据提示：**默认基线的 `V5__seed.sql` 会生成演示患者、住院、医嘱、费用等数据。首次启动应连接专用开发/演示库；接入真实业务数据前，应另行拆分或替换种子数据策略。

如需独立演示 SQL，可在目标数据库中按顺序执行：

```text
database/schema.sql
database/indexes.sql
database/views.sql
database/routines.sql
database/seed.sql
database/verify.sql
# 可选：仅在 *_test 测试库生成独立可对账样本
database/verified-workflow.sql
```

## 2. 启动后端

```powershell
cd backend
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

后端地址为 `http://localhost:8080`，健康检查为 `/actuator/health`。

真实 MySQL 集成测试默认关闭。准备好独立测试库后，务必显式设置 `HOSPITAL_TEST_DB_URL` 再开启测试：

```powershell
$env:HOSPITAL_TEST_DB_ENABLED='true'
$env:HOSPITAL_TEST_DB_URL='jdbc:mysql://localhost:3306/hospital_inpatient_test?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowMultiQueries=true'
.\mvnw.cmd test
```

测试配置不再回退到业务库。测试上下文在 Flyway 运行前拒绝缺失 URL、非本机地址、不以 `_test` 结尾或与业务 URL 相同的目标。仍须自行确认它确实是可写入的专用测试库。测试会保留带随机标识的夹具，不清空数据库。

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
3. 药房发药并扣库存、自动计费；护士随后记录给药，非药品项目由对应岗位执行。
4. 重放相同 `requestNo` 应被拒绝且无重复流水；即使换请求号，已执行的临时项目也不能再次执行。需退药时先完整冲正，必要时重新执行。
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
- Git 不属于程序运行依赖；仓库已用于保存本轮基线重建记录，但部署运行不要求执行 Git 命令。

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
