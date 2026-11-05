# 小型医院住院信息管理与费用结算系统

数据库课程设计项目。系统以一次住院记录 `admission` 为业务主轴，围绕床位历史、医嘱执行和费用流水实现住院管理与费用结算。

## 版本与分支

| 分支 | 版本与用途 |
| --- | --- |
| [`main`](https://github.com/helanfameng/Hospital-manage-final/tree/main) | 最终采用的住院管理与结算版，Flyway V1–V7 |
| [`clinical-extension`](https://github.com/helanfameng/Hospital-manage-final/tree/clinical-extension) | 保留的临床扩展版，Flyway V1–V14；从 [Hospital-manage-init](https://github.com/helanfameng/Hospital-manage-init) 导入并保留提交历史 |

默认展示 `main`；通过页面的分支菜单切换版本，下载前先选择所需分支。两个分支的数据库迁移链独立，安装与运行请使用所选分支的 README 和部署说明。

## 当前版本：住院管理与结算版（V1–V7）

当前源码提供可从空库安装的 V1–V7 完整迁移：V1–V6 创建结构、索引、视图、过程/触发器和两阶段演示种子数据，V7 增加库存流水前后数量平衡约束。医嘱头不再保存旧的类型、频次、工作流版本和归档兼容字段；长期/临时属性保留在独立医嘱项目层级。

当前医嘱工作流明确分为药房发药 `DISPENSE`、护士给药 `ADMINISTER` 和非药品执行 `PERFORM`。药品在发药时扣库存并计费，给药不重复收费；执行、费用、押金、库存、转床、结算和审计采用追加事实与冲正记录，不覆盖原流水。

完整迁移由 `V1__schema.sql` 至 `V7__inventory_balance_constraint.sql` 构成：V1 定义 28 张业务表，另有 7 个视图、4 个存储过程和 18 个保护触发器。V5 提供主体演示数据，V6 补充提醒场景；合计包含 30 位患者、100 张床位及关联演示数据。结算类别小计由收费明细实时聚合，收费号由应用代码生成，医嘱项目独立执行。

独立 SQL 当前提供六个安装镜像：V1–V4 分别对应结构、索引、视图和过程，V5、V6 合并为种子脚本，V7 对应库存平衡约束脚本。药品处方、计划任务和实际执行分别保存在 `order_item`、`medication_schedule`、`execution_record`；库存支持入库、盘点及流水查询。

### 历史验证记录

下表为原文档中 2026-09-03 留存的结果，不代表本次重新执行。当前验证应重新运行本文与测试计划中的命令。

| 检查项 | 2026-09-03 结果 |
| --- | --- |
| 后端 | 46 项测试通过，其中 36 项为真实 MySQL 集成测试；无失败、无跳过 |
| 前端 | 12 个测试文件、62 项测试通过；Vite 生产构建成功 |
| SQL 镜像 | 当时五个独立脚本覆盖 V1–V6，种子镜像由 V5 与 V6 合并；当前另有 V7 镜像 |
| 端到端 SQL | `database/verified-workflow.sql` 在独立 `*_test` 库通过：押金 1000、费用 28.50、退款 971.50、库存 500→499、余额归零并释放床位 |

历史生产构建曾提示部分前端分块大于 500 kB，属于性能优化提示；当前构建结果应重新验证。项目定位为课程设计与开发演示，尚未完成生产级负载、灾备、渗透和全浏览器验收。

### 迁移边界

V1–V6 是重建后的新基线，V7 在该基线上增加库存平衡约束；完整安装应运行 V1–V7，不是给曾延伸到 V6/V8/V13 的旧迁移链继续追加的原地升级包。旧环境必须先备份，再按 [部署说明](docs/部署说明.md) 新建或重建目标库；需要保留的历史数据应通过经过核对的导出/导入方案迁移，不能修改 Flyway 校验值或直接 `repair` 掩盖不一致。

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
CREATE DATABASE hospital_inpatient_main
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE hospital_inpatient_main_test
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE hospital_inpatient_main_sql_test
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER 'hospital_app'@'localhost' IDENTIFIED BY '<自行设置强密码>';
GRANT ALL PRIVILEGES ON hospital_inpatient_main.* TO 'hospital_app'@'localhost';
GRANT ALL PRIVILEGES ON hospital_inpatient_main_test.* TO 'hospital_app'@'localhost';
GRANT ALL PRIVILEGES ON hospital_inpatient_main_sql_test.* TO 'hospital_app'@'localhost';

-- 开启二进制日志的 MySQL 创建触发器时需要此开关。
-- SET GLOBAL 仅持续到 MySQL 下次重启，首次迁移完成后运行期不再依赖它。
SET GLOBAL log_bin_trust_function_creators = 1;
FLUSH PRIVILEGES;
```

不要把实际密码或 JWT 密钥写入仓库。PowerShell 会话中设置：

```powershell
$env:HOSPITAL_DB_URL='jdbc:mysql://localhost:3306/hospital_inpatient_main?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowMultiQueries=true'
$env:HOSPITAL_DB_USERNAME='hospital_app'
$env:HOSPITAL_DB_PASSWORD='<数据库密码>'
$env:HOSPITAL_JWT_SECRET='<至少32字节的随机字符串>'
$env:HOSPITAL_BOOTSTRAP_ADMIN_PASSWORD='<首次启动六个演示账号的强密码>'
```

与临床扩展版分别使用业务库和测试库；启动本版前应显式设置上述 URL，避免回退到与另一版本同名的默认库。

后端启动时运行 Flyway。仅当账号表为空且设置了 `HOSPITAL_BOOTSTRAP_ADMIN_PASSWORD` 时，才会创建六个演示账号：`admin`、`admission`、`doctor`、`nurse`、`pharmacy`、`cashier`。这些账号共用该初始密码；登录后通过右上角“修改密码”设置个人密码，并重新登录。演示账号不能直接用于正式部署。查看当前账号：管理员登录后进入“系统管理 → 用户”；数据库可查询 `SELECT id,username,display_name,enabled FROM user_account`，不要输出密码哈希。

**演示数据提示：**默认基线的 `V5__seed.sql` 会生成演示患者、住院、医嘱、费用等数据。首次启动应连接专用开发/演示库；接入真实业务数据前，应另行拆分或替换种子数据策略。

如需独立演示 SQL，请在另一个空演示库中按顺序执行，避免与 Flyway 自动安装混用：

```text
database/schema.sql
database/indexes.sql
database/views.sql
database/routines.sql
database/seed.sql
database/inventory-balance-constraint.sql
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
$env:HOSPITAL_TEST_DB_URL='jdbc:mysql://localhost:3306/hospital_inpatient_main_test?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowMultiQueries=true'
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
8. 展示当前住院运行、待出院/结算受阻/长住院提醒、每日入出院趋势、住院日分布、床位占用率和科室负荷。

## 5. 关键设计

- 业务以一次住院为锁与账目的边界；先锁住院主记录，再处理子流水，避免结算和补交/冲正读取不同版本的账目。
- 重复请求返回冲突，不重复生成事实；重试应复用原请求号并刷新核对。长期医嘱的新一次执行才生成新的请求号。
- 冲正追加反向事实，不修改原成功记录；查询通过原记录引用识别已冲正状态。
- 四个核心存储过程由调用方管理事务；独立 SQL 调用需显式事务与失败回滚，示例见 `database/verified-workflow.sql`。
- 浏览器按钮不是权限边界：后端校验当前权限、住院数据范围、业务状态与并发约束。
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
