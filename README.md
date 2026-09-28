# cc-bloodbank

管理血液成分、相容性和临床预留。

## 开发环境

- JDK 21
- Spring Boot 4.1.1
- Maven Wrapper 3.9.9
- H2

## 本地运行

启动服务：

    ./mvnw spring-boot:run

运行测试：

    ./mvnw clean test

## 领域模型

- **血液成分单位（BloodComponent）**：唯一条码、**采供血批次号（batchNo）**、
  成分类型（红细胞/血浆/血小板/冷沉淀）、ABO 与 Rh 血型、采集时间、失效时间、库存状态
  （`AVAILABLE` / `RESERVED` / `ISSUED` / `RECALLED`）。
- **临床申请（ClinicalRequest）**：唯一申请号、患者 ABO/Rh 血型、所需成分类型、单位数、最晚使用时间。
- **预留（Reservation）**：对应一份申请，含预留时间、有效截止时间与状态
  （`RESERVED` / `ISSUED` / `RELEASED` / `TERMINATED`），以及：
  - `blocked`：召回导致换血库存不足时为 `true`，保留原预留关系但**禁止发放**；
  - `stateVersion`：预留状态版本号，每次成功换血递增，用于让持旧版本的发放/到期释放整体失效。
- **预留明细（ReservationItem）**：一笔预留分配到的各个血液成分单位。
  `active=true` 为当前生效单位；换血被替换或释放后 `active=false`，**保留历史**不删除。
- **批次召回（BatchRecall）**：外部事件号（唯一）、召回批次、原因、生效时间、是否已激活。
- **换血任务（ReplacementTask）**：一次召回影响到一笔未发放预留时生成
  （同一预留可因不同召回有多笔任务），状态为
  `PENDING`（待处理）/ `REPLACED`（已换血）/ `UNRESOLVABLE`（人工确认无法换血）/ `OBSOLETE`（预留已终态作废）。
- **换血明细（ReplacementItem）**：仅在一次性换血成功时写入，
  记录被替换单位、替代单位、触发召回事件号与处理时间。

## 主要业务规则

### 预留分配

- 只有同时满足以下条件的单位可被预留：成分类型一致、血型相容、当前未失效
  （`expiresAt > now`）、失效时间不早于最晚使用时间（`expiresAt >= latestUseTime`）、
  状态为 `AVAILABLE`（**已被生效召回冻结的 `RECALLED` 单位不能参与新预留**）。
- 分配顺序：**失效时间最早优先，失效时间相同按条码升序**，保证结果稳定可预期。
- **原子性**：可用单位不足时整笔失败并回滚，不做部分预留；申请、预留、明细与单位
  状态变更在同一事务中提交。

### 血型相容性

- ABO 按成分类别区分方向：
  - 红细胞、血小板（红细胞类规则）：受者血浆抗体不能攻击供者红细胞抗原——
    O 是万能供者，AB 患者可接受所有血型。
  - 血浆、冷沉淀（血浆类规则）：供者血浆抗体不能攻击受者红细胞抗原——
    AB 是万能供者，O 只能给 O。
- Rh：Rh 阴性患者只能接受 Rh 阴性单位；Rh 阳性患者两者皆可。

### 预留有效期与发放

- 每次成功预留有有效截止时间（默认 24 小时，`bloodbank.reservation-ttl` 可配置）。
- 截止前可确认发放：发放后单位置为 `ISSUED`，**不可恢复**。
- 到期未发放的预留由后台定时任务（`bloodbank.sweeper.fixed-delay`，默认 60 秒）
  或 `POST /api/reservations/release-expired` 释放，全部合格单位回到 `AVAILABLE`，可再次预留。
- 截止后请求发放按到期释放处理，不会发放过期预留。
- **幂等**：重复发放、重复释放都返回当前状态，不重复改变库存。

### 批次召回

- 血液中心按**外部事件号**发布召回，内容含召回批次、原因、生效时间。
  - 相同事件号 + 相同内容重复提交：**返回原结果**（含已生成的换血任务），不重复处理。
  - 相同事件号 + 批次/原因/生效时间任一不同：返回 **409 冲突**。
- **生效**：到达生效时间即激活（发布时已到点则立即激活；否则由后台任务
  `bloodbank.recall-sweeper.fixed-delay`，默认 5 秒，或 `POST /api/recalls/activate-due` 激活）。
- 激活后该批次单位立即冻结为 `RECALLED`：在库单位不能参与新预留；已被预留的单位
  不能发放、到期释放也**不会退回库存**；已发放（`ISSUED`）单位终态不受影响。
- 仅对**尚未发放**（`RESERVED`）且当前仍占用被召回单位的预留，按申请生成换血任务；
  已发放、已到期释放的预留不产生任务。

### 召回换血

- 换血仍遵守现有的**成分类型、ABO、Rh、最晚使用时间、失效时间**全部相容规则。
- 替代品按与预留相同的顺序选取：**失效时间最早优先、条码升序**，且排除该预留仍占用的合格单位。
- **原子替换**：以一笔预留为单位，其全部待处理任务涉及的所有被召回单位都找到替代品时，
  才一次性提交——旧明细分历史、被召回单位保持 `RECALLED`、替代单位挂入预留并写换血明细。
- **库存不足不换一半**：只要有一个受影响单位找不到合格替代品，则**不做任何库存变更**，
  保留原预留关系、把预留标记为 `blocked=true`（不可发放），并在任务上记录未解决原因；
  之后库存补齐可再次执行，成功即一次性完成并解除阻止。
- **幂等**：同一换血任务重复执行返回已有结果，不重复占用替代单位、不重复写明细。
- **预留到期**：换血处理时若预留已过截止时间，则按到期释放处理（合格单位回库、
  被召回单位保持冻结），换血任务作废为 `OBSOLETE`。
- **人工确认无法换血**：`POST /api/recalls/replacements/{requestNo}/unresolvable`
  释放该预留仍占用的**合格单位**（被召回单位保持冻结），将预留置为 `TERMINATED` 终止，
  任务记为 `UNRESOLVABLE`；重复确认幂等。

### 召回与发放/释放的并发安全

- 换血、确认发放、到期释放都以 `SELECT ... FOR UPDATE` **锁定同一预留行**串行执行。
- **被召回单位不会发放**：发放除预留行锁外，再以
  `UPDATE ... WHERE status='RESERVED'` 条件批量发放兜底；若有单位已被冻结为 `RECALLED`，
  受影响行数不足则整笔回滚。
- **替代单位不会被重复占用**：换血对候选单位行锁并以 `WHERE status='AVAILABLE'`
  条件预留兜底，并发下被其他申请占用则整笔换血回滚。
- **换血完成后旧释放不生效**：每次成功换血递增预留 `stateVersion`；
  发放/到期释放的条件流转必须匹配持锁时读到的版本，版本不符（期间发生换血）则整体放弃，
  替代单位绝不会被旧的释放操作退回库存。

### 幂等重放与并发

- 相同申请号 + 相同申请内容重放：返回原分配（无论预留当前状态），不重复占用单位。
- 相同申请号 + 不同内容：返回 409 冲突。
- 并发安全：
  - 候选单位按 `SELECT ... FOR UPDATE` 悲观锁选取，并以
    `UPDATE ... WHERE status = 'AVAILABLE'` 条件更新兜底——并发申请不可能预留同一单位。
  - 发放与到期释放通过预留行悲观锁 + `WHERE status = 'RESERVED'` 条件状态流转，
    并发时**只有一个状态变化生效**。
- 数据库约束兜底：申请号唯一、一笔申请至多一笔预留（`request_id` 唯一）、
  换血任务 `(reservation_id, recall_id)` 唯一、召回事件号唯一。
- 时间判断统一使用可替换的 `Clock` Bean（生产为系统 UTC 时钟，测试使用可变时钟）。

## API 概览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/components` | 登记血液成分单位（含批次号） |
| GET | `/api/components` | 库存状态查询（`?status=AVAILABLE/RESERVED/ISSUED/RECALLED` 可选） |
| GET | `/api/components/{barcode}` | 按条码查询单位 |
| POST | `/api/reservations` | 提交申请并原子预留（幂等重放） |
| POST | `/api/reservations/{requestNo}/issue` | 确认发放（幂等；含召回单位返回 409） |
| POST | `/api/reservations/release-expired` | 手动触发到期释放，返回释放笔数 |
| GET | `/api/requests/{requestNo}` | 申请及预留明细查询（含 blocked 与历次换血前后明细） |
| POST | `/api/recalls` | 发布批次召回（事件号幂等、异内容 409；已到点立即激活） |
| GET | `/api/recalls` | 全部召回概览 |
| GET | `/api/recalls/{eventNo}` | 召回范围、受影响申请与各换血任务（含未解决原因） |
| POST | `/api/recalls/activate-due` | 手动激活已到生效时间的召回，返回激活数 |
| POST | `/api/recalls/replacements/{requestNo}` | 为一笔申请执行换血（幂等，返回已有结果） |
| POST | `/api/recalls/replacements/{requestNo}/unresolvable` | 人工确认无法换血，释放合格单位并终止预留 |

错误码：400 参数非法，404 申请/单位/召回不存在，409 申请号或召回事件号内容冲突、
召回阻止发放，422 相容可用单位不足。

## 测试

- `CompatibilityRulesTest`：ABO/Rh 相容规则单元测试。
- `ReservationServiceIntegrationTest`：分配顺序、相容/失效过滤、原子失败、
  幂等重放与冲突、发放、到期释放与再预留、查询。
- `ReservationConcurrencyTest`：并发申请不共享单位、并发发放幂等、
  发放与到期释放竞争下状态自洽、条件流转只有一个生效。
- `RecallReplacementIntegrationTest`：召回发布幂等/异内容冲突、生效冻结（在库/预留/已发放）、
  换血任务生成、早到期优先的一次性换血、库存不足阻止且不部分替换、重复执行幂等、
  人工确认终止并释放合格单位、到期作废、未来生效由定时激活、召回范围与未解决原因查询。
- `RecallConcurrencyTest`：换血与发放竞争绝不发放召回单位、换血与到期释放竞争状态自洽、
  持旧版本的释放在换血后被拒、同申请并发换血只执行一次。
- `BloodBankApiTest`：REST 端到端流程、错误码与召回换血/人工终止 HTTP 流程。
