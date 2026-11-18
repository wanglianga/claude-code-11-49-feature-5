# 就诊人主索引（MPI）合并拆分与快照稳定服务

面向连锁诊所的就诊人主索引服务。不同院区的挂号、检验、结算系统各自生成就诊人编号，
本服务负责：**来源身份接入 →候选匹配 → 人工决议（合并/拆分）→ 时态关系图 → 业务快照 → 影响清单**，
保证已确认的结算、检验报告和经营报表"当时引用了谁"永远可复现。

## 核心设计

| 决策 | 实现 |
| --- | --- |
| 身份历史模型 | **时态关系图**：`mpi_link`（身份→主体）带生效图谱版本区间 `[validFrom, validTo)`，按业务时点解析身份 |
| 快照稳定 | 预约/结算/检验/分析启动时**发布快照固定图谱版本**，并把该版本的归属映射物化到 `snapshot_mapping`，读取快、可复核、永不回写 |
| 审核隔离 | 候选对、候选快照都是"尚在审核的身份推断"，下游只能读 `PUBLISHED` 快照（候选快照读取返回 409） |
| 并发决议 | 决议携带 `expectedGraphVersion`，事务内对全局图谱行加写锁：**先提交者锁定该对及相邻关系，后提交者收 409 必须重新检查**，不会形成一人并入两个互斥主体 |
| 身份更正 | 来源证件更正只更新证据字段并重跑匹配；**已发布快照不回写**，影响经新决议→影响分析→**候选快照**供业务确认 |
| 关账保护 | 已关账结算凭证**绝不改写**，身份拆分只生成 `ADJUSTMENT_LIST` 调整清单供财务线下处理；检验报告、预约提醒按各自业务规则领取新快照 |
| 渐进式来源接入 | 文件按**批次**接入：记录字段覆盖、格式规则、候选类型、自动决策资格与预计影响；保守门槛只让**强证据组合**（证件号一致，或 手机+姓名全称+出生年月 一致）进入自动链接，弱证据进**可逆暂存工作区**，结构上不写 `mpi_link`、不可能流入快照/结算/提醒/分析 |
| 批次质量门禁 | 文件指纹+记录键双重幂等；列名漂移、格式漂移、出生年月批量缺失、更正与首批矛盾一律把批次置为 **RESTRICTED/待修复**，不以空值填补、不沿用旧解析器放行 |
| 稳定性评估 | 补充字段到达时评估首批决策（STABLE/OVERTURNED/NEEDS_REVIEW/UPGRADED），只基于"首批当时可见证据"评价，**不把第二批资料当成首批本应拥有的信息**，首批报告永不回溯改写 |

技术栈：Java 17+（本地用 JDK 25 验证）、Spring Boot 3.5、Spring Data JPA、PostgreSQL、springdoc-openapi（Swagger UI）。

## 渐进式来源接入（收购诊所历史文件）

针对"首批只有旧病历号+简写姓名、第二批才补出生年月与更正日志"的场景，新增六个相互约束的模块：

| 模块 | 职责 | 关键约束 |
| --- | --- | --- |
| 文件接收 `BatchIngestService` | 按文件指纹（SHA-256）+记录键幂等接收 | 同指纹重传直接返回原批次并累计 `reuploadCount`，不重复建身份 |
| 字段契约检测 `FieldContractDetector` | 列名别名归一、契约版本（`legacy-v1`/`enriched-v2`）、格式规则、覆盖与批量缺失 | 未知列名/格式漂移/批量缺失（空值率 >50%）产出质量问题，整批受限 |
| 候选生成 `MatchService` | 评分、候选类型 `STRONG_AUTO`/`MANUAL_REVIEW`/`NO_CANDIDATE`、自动链接资格 | 仅强证据组合 `autoLinkEligible=true`；简写姓名只算"姓名相似"，不满足门槛 |
| 暂存/正式归属 `StagingService` | 弱证据身份写可逆暂存，强证据才经正式决议进图谱 | 暂存只存 `staged_attribution`，永不写 `mpi_link`；发布需强证据守卫否则 409 |
| 业务快照资格 `SnapshotService` | 物化只取正式图谱 | 暂存身份在快照中解析到其**正式单例主体**，临时猜测不可见；结算/提醒/分析因此天然隔离 |
| 批次质量报告 | 字段覆盖、格式规则、三类决策比例、预计影响、稳定性 | `GET /api/batches/{id}` 与 `/api/batches/compare` 量化"可确定链接/仅人工审阅/暂不链接" |

三类批次决策（对应需求的三种比例）：

- **可确定链接** `AUTO_MERGED`（强证据自动合并，留决议痕迹）/ `CONFIRMED_SINGLETON`（要素充分且无候选的独立主体）；
- **仅可人工审阅** `MANUAL_REVIEW`（评分过线但不满足强证据组合，候选对 `autoLinkEligible=false`）；
- **因证据不足暂不链接** `INSUFFICIENT_STAGED`（进入受限工作区，仅 `RESTRICTED_QUERY_ONLY` 查询可见，响应强制带临时性与范围警告）。

补充批次到达后，`enrichFields` 只补齐首批为空的字段（简写姓名补全按包含关系留更正痕跡），
稳定性评估给出结论：矛盾出生年月会 **OVERTURNED** 早期弱候选并撤回暂存；满足强证据则 **UPGRADED** 发布正式关联；
无矛盾无候选则 **STABLE**。受限工作区结果始终带临时性与范围提示，补充字段只改善后续正式快照。

## 一键启动（Docker Compose）

```bash
docker compose up --build
```

启动后：应用 http://localhost:8080 ，Swagger UI http://localhost:8080/swagger-ui.html 。
Compose 包含 PostgreSQL 18 容器，首次启动自动建表并播种样例数据。
重置数据：`docker compose down -v && docker compose up --build`。

## 本地开发

前置：JDK 17+（本仓库在 JDK 25 下 验过）、Maven 3.9+、PostgreSQL（默认连接 `127.0.0.1:5432`，库/用户/密码 `ccdev`，可用环境变量覆盖）。

```bash
# 启动（自动建表 + 播种样例数据）
mvn spring-boot:run

# 自定义数据库连接
DB_HOST=127.0.0.1 DB_PORT=5432 DB_NAME=ccdev DB_USER=ccdev DB_PASS=ccdev mvn spring-boot:run
```

## 测试账号（操作员）

接口通过请求头 `X-Operator-Id` 标识操作员（缺省 `system`）：

| 账号 | 用途 |
| --- | --- |
| `op_zhang` | 主索引操作员 1（合并/拆分/确认快照） |
| `op_li` | 主索引操作员 2（并发决议回放中的后提交者） |

## 验证方式

### 1. 集成测试（16 个用例，真实 PostgreSQL）

```bash
# 准备测试库（首次）
psql -h 127.0.0.1 -U ccdev -d ccdev -c "CREATE DATABASE ccdev_test;"
# 运行（使用独立库 ccdev_test，create-drop，不影响开发数据）
mvn test
```

原 9 个用例覆盖：接入与评分、合并版本递增与时点解析、过期版本 409 后重检成功、**双线程并发决议恰好一个成功**、
误并拆分恢复、证件更正不回写快照、关账只出调整清单、候选快照确认按业务规则生效、家庭共用号码拒绝合并。

新增 7 个渐进式接入用例（`BatchIngestFlowTest`）：简写姓名首批暂存→第二批出生年月升级且首批不回溯、
文件指纹重传幂等（含文件内重复行）、列名漂移受限与修复重传、enriched 契约出生年月批量缺失受限、
更正矛盾受限不覆盖首批事实、补充矛盾资料 OVERTURNED 早期弱候选、强证据自动链接与暂存不流入快照/预约。

### 2. 验收回放脚本（80 项断言，需服务已启动且为全新播种数据）

```bash
./scripts/verify.sh            # 默认 BASE_URL=http://127.0.0.1:8080
```

回放六大验收场景：多院重复挂号合并 → **误并拆分 + 关账后调整** → **来源证件更正** → **并发决议**（含家庭共用号码拒绝）
→ 按版本复现关系链 → **渐进式来源接入**（简写姓名首批/补充出生年月/重复上传/列名变更/批量缺失/更正矛盾/推翻早期候选/暂存隔离）。
全部通过时退出码为 0。

### 3. 手工回放（curl 示例）

```bash
# 查看待处理候选对（评分+证据）
curl -s 'http://127.0.0.1:8080/api/candidates?status=PENDING' | jq

# 取当前图谱版本 → 提交合并决议（先提交者锁定）
V=$(curl -s http://127.0.0.1:8080/api/graph/version | jq -r .graphVersion)
curl -s -X POST http://127.0.0.1:8080/api/resolutions/merge \
  -H 'Content-Type: application/json' -H 'X-Operator-Id: op_zhang' \
  -d "{\"candidateId\":\"<候选对ID>\",\"expectedGraphVersion\":$V,\"reason\":\"两院重复挂号\"}" | jq

# 后提交者持同一旧版本提交 → 409，响应携带 currentGraphVersion
# 查看影响清单与候选快照，业务确认领取
curl -s 'http://127.0.0.1:8080/api/impacts' | jq
curl -s -X POST http://127.0.0.1:8080/api/snapshots/<候选快照ID>/confirm -H 'X-Operator-Id: op_zhang'

# 按业务时点解析身份归属（历史可复现）
curl -s 'http://127.0.0.1:8080/api/resolve?sourceSystem=LAB_A&sourcePatientId=L-A-001&atVersion=0' | jq
```

### 4. 渐进式接入手工示例（批次）

```bash
# 首批 legacy-v1：旧病历只有病历号+简写姓名（中文列名别名可识别），无出生年月
curl -s -X POST http://127.0.0.1:8080/api/batches -H 'Content-Type: application/json' -d '{
  "sourceSystem":"CLINIC_X","fileName":"legacy.csv","contractVersion":"legacy-v1",
  "columns":["病历号","姓名","性别"],
  "rows":[{"病历号":"MRN-101","姓名":"张","性别":"M"}]}' | jq
# → status=ACCEPTED，insufficientCount=1，stagedCount=1（证据不足，进受限工作区）

# 受限历史查询：每条结果带 scope=RESTRICTED_QUERY_ONLY 与临时性警告
curl -s 'http://127.0.0.1:8080/api/staging/restricted-query?sourceSystem=CLINIC_X' | jq

# 同文件重传 → 幂等返回同一批次，reuploadCount+1（再次执行上面的 POST 即可）

# 第二批 enriched-v2：补出生年月与手机，满足强证据组合后稳定性 UPGRADED、发布正式关联
curl -s -X POST http://127.0.0.1:8080/api/batches -H 'Content-Type: application/json' -d '{
  "sourceSystem":"CLINIC_X","fileName":"enriched.csv","contractVersion":"enriched-v2",
  "columns":["病历号","姓名","手机号","性别","出生年月"],
  "rows":[{"病历号":"MRN-101","姓名":"张三","手机号":"13955550001","性别":"M","出生年月":"1990-01-01"}]}' | jq

# 批次质量报告 / 逐行台账 / 稳定性 / 三类比例比较
curl -s http://127.0.0.1:8080/api/batches | jq
curl -s http://127.0.0.1:8080/api/batches/<批次ID>/records | jq
curl -s http://127.0.0.1:8080/api/batches/<补充批次ID>/stability | jq
curl -s 'http://127.0.0.1:8080/api/batches/compare?batchAId=<首批>&batchBId=<补充批>' | jq

# 列名漂移/批量缺失/更正矛盾 → 批次 RESTRICTED，只落台账不接入，修复后用 repairOfBatchCode 重传
# 弱证据暂存无强证据候选时发布会被 409 拒绝：
curl -s -X POST http://127.0.0.1:8080/api/staging/<暂存ID>/publish \
  -H 'Content-Type: application/json' -d '{"expectedGraphVersion":0}'
```

### 重置样例数据（本地）

```bash
psql -h 127.0.0.1 -U ccdev -d ccdev -c "drop schema public cascade; create schema public;"
# 重启应用后会自动重新播种
```

## 样例数据（启动自动播种）

| 场景 | 数据 | 预期处理 |
| --- | --- | --- |
| 多院重复挂号 | 张三 `REG_A/P-A-001` × `REG_B/P-B-001`（证件号一致，评分 1.0） | 合并 |
| 误并素材 | 两个同名陈晨 `REG_A/P-A-002` × `REG_B/P-B-002`（仅姓名相同，低于候选阈值） | 人工误并 → 拆分回放 |
| 家庭共用号码 | 李四 × 李四中（共用手机号，评分 0.4） | 人工拒绝合并 |
| 证件更正 | 赵六 `LAB_A/L-A-001` 证件号末位录错（`...110` 应为 `...111`） | 更正 → 新候选 → 合并 |

下游记录：陈晨B 预约（BOOKED）、李四中预约（BOOKED）、陈晨A 结算单（**已关账**，2026-10，¥880）、
赵六结算单（未关账，2026-11，¥1200）、陈晨A 血常规（未签发）、赵六 尿常规（未签发）。

## API 概览（完整见 Swagger UI）

| 分组 | 接口 |
| --- | --- |
| 来源身份 | `POST/GET /api/source-identities`、`GET /api/source-identities/{id}`、`POST /api/source-identities/{id}/corrections` |
| 候选匹配 | `GET /api/candidates`、`GET /api/candidates/{id}`、`POST /api/candidates/{id}/reject` |
| 人工决议 | `POST /api/resolutions/merge`、`POST /api/resolutions/split`、`GET /api/resolutions[/{id}]` |
| 时态关系图 | `GET /api/graph/version`、`GET /api/graph/masters[/{id}]`、`GET /api/resolve?atVersion=` |
| 业务快照 | `POST /api/snapshots`、`GET /api/snapshots[/{id}]`、`GET /{id}/mappings`、`GET /{id}/resolve`、`POST /{id}/confirm`、`POST /{id}/reject` |
| 影响清单 | `GET /api/impacts`、`POST /api/impacts/{id}/ack` |
| 下游模拟 | `POST/GET /api/biz/appointments`、`POST /{id}/remind`、`POST/GET /api/biz/settlements`、`POST /{id}/close`、`POST/GET /api/biz/lab-reports`、`POST /{id}/finalize` |

## 目录结构

```
src/main/java/com/clinic/mpi/
├── domain/      # 13 个实体：来源身份、候选对、决议、图谱、时态链接、快照、影响项、下游记录
├── repo/        # Spring Data JPA 仓库（含悲观写锁查询）
├── service/     # Match/Resolution/Snapshot/Impact/Biz/Identity/Graph 服务
├── web/         # 7 个 REST 控制器 + 全局异常处理（409 版本冲突）
└── seed/        # 样例数据播种（幂等）
scripts/verify.sh  # 验收回放脚本（45 项断言）
```

## 验证结论（实际执行记录）

以下验证均在本仓库交付前实际执行通过：

- `mvn test`：9 个集成测试全部通过（真实 PostgreSQL 18，独立测试库 `ccdev_test`）。
- `./scripts/verify.sh`：45 项验收断言全部通过（本地启动 `mvn spring-boot:run` + 全新播种数据）。
- Swagger UI（`/swagger-ui.html`）与 OpenAPI 文档（`/v3/api-docs`，28 个路径）已实际访问确认可用。
