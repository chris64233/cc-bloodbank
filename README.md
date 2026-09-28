# cc-bloodbank

管理血液成分、相容性、临床预留、批次召回与召回后安全换血。

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

- **血液成分单位（BloodComponent）**：唯一条码、所属批次（`batchNo`）、成分类型
  （红细胞/血浆/血小板/冷沉淀）、ABO 与 Rh 血型、采集时间、失效时间、库存状态
  （`AVAILABLE` / `RESERVED` / `ISSUED` / `RECALLED`）。
- **临床申请（ClinicalRequest）**：唯一申请号、患者 ABO/Rh 血型、所需成分类型、单位数、最晚使用时间。
- **预留（Reservation）**：对应一份申请，含预留时间、有效截止时间与状态
  （`RESERVED` / `ISSUED` / `RELEASED` / `TERMINATED`），以及不可发放标记 `issueBlocked`。
- **预留明细（ReservationItem）**：一笔预留分配到的各个血液成分单位；行状态
  （`ACTIVE` / `REPLACED` / `RELEASED`）保留换血与释放历史。
- **批次召回（Recall）**：外部事件号（唯一）、批次号、原因、生效时间、激活标记。
- **换血任务（ReplacementTask）**：一起召回 × 一笔受影响预留至多一条；状态
  （`PENDING` / `COMPLETED` / `TERMINATED` / `ABANDONED`），记录未解决原因。
- **换血明细（ReplacementItem）**：被替换单位与替代单位一一配对，记录处理结果。

## 主要业务规则

### 预留分配

- 只有同时满足以下条件的单位可被预留：成分类型一致、血型相容、当前未失效
  （`expiresAt > now`）、失效时间不早于最晚使用时间（`expiresAt >= latestUseTime`）、
  状态为 `AVAILABLE`、所属批次**未被生效召回**。
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

- 血液中心按**外部事件号**发布召回，内容含批次号、原因、生效时间。
- **生效即隔离**：召回生效时，批次内在库单位立即置 `RECALLED`，不能再参与任何新预留
  （候选查询与条件更新双重排除已激活召回批次）。
- 生效时间可在未来：发布时不激活，由后台任务（`bloodbank.recall-sweeper.fixed-delay`，
  默认 10 秒）或 `POST /api/recalls/activate-due` 到点激活。
- **幂等与冲突**：相同事件号 + 相同内容重复提交返回原结果；相同事件号 + 不同内容
  （批次/原因/生效时间任一不同）返回 409 冲突。
- 已发放（`ISSUED`）的单位不受召回影响，不生成换血任务。

### 召回后换血

- 召回生效时，每笔**尚未发放且占用了批次单位**的预留生成一条 `PENDING` 换血任务，
  记录全部受影响单位（换血前明细），并把预留标记为**不可发放**（`issueBlocked=true`）。
- 替代品必须同时满足与新预留相同的规则：成分一致、ABO/Rh 相容、未失效、
  失效时间不早于最晚使用时间；选择顺序同样是**到期最早优先、条码升序**，
  且排除任何已生效召回批次与本预留已占用单位。
- **全有或全无（原子换血）**：只有全部受影响单位都找到合格替代品时，才在同一事务内
  一次性完成替换（替代单位 `AVAILABLE→RESERVED`，被召回单位保持 `RECALLED` 隔离，
  旧明细置 `REPLACED`）。替代品不足时**不换任何一个**：原预留关系保留、继续不可发放，
  任务停留 `PENDING` 并记录未解决原因，补货后可重试。
- 全部替换成功后解除不可发放标记；若该预留还受其他未完成召回影响，则继续阻断，
  直到所有受影响单位都换血完成。
- 同一换血任务**重复执行返回已有结果**（终态幂等，库存不再变化）；并发执行经
  预留行锁 + 任务行锁串行化，只会成功替换一次。
- **人工确认无法换血**：终止预留（`TERMINATED`）——仍占用的合格单位释放回库
  （可被其他申请使用），被召回单位保持 `RECALLED` 隔离，相关任务记 `TERMINATED`。
- 预留到期释放时，其未完成任务作废（`ABANDONED`）；被召回单位隔离、合格单位回库。

### 幂等重放与并发

- 相同申请号 + 相同申请内容重放：返回原分配（无论预留当前状态），不重复占用单位。
- 相同申请号 + 不同内容：返回 409 冲突。
- 并发安全：
  - 候选单位按 `SELECT ... FOR UPDATE` 悲观锁选取，并以
    `UPDATE ... WHERE status = 'AVAILABLE'` 条件更新兜底——并发申请（含换血）不可能占用同一单位。
  - 发放、到期释放、换血、人工终止统一**先锁预留行**（再锁任务行/单位行，固定加锁顺序避免死锁）。
  - 发放条件流转额外要求 `issueBlocked = false`——被召回单位在换血完成前**绝不会发放**；
    换血完成后旧的释放操作无法再退回替代品（释放与换血互斥，且释放会作废未完成任务）。
- 数据库约束兜底：申请号唯一、条码唯一、一笔申请至多一笔预留（`request_id` 唯一）、
  一起召回 × 一笔预留至多一条换血任务、一条换血明细对一个被替换单位。
- 时间判断统一使用可替换的 `Clock` Bean（生产为系统 UTC 时钟，测试使用可变时钟）。

## API 概览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/components` | 登记血液成分单位（含批次号） |
| GET | `/api/components` | 库存状态查询（`?status=AVAILABLE/RESERVED/ISSUED/RECALLED` 可选） |
| GET | `/api/components/{barcode}` | 按条码查询单位 |
| POST | `/api/reservations` | 提交申请并原子预留（幂等重放） |
| POST | `/api/reservations/{requestNo}/issue` | 确认发放（幂等；召回阻断时 409） |
| POST | `/api/reservations/release-expired` | 手动触发到期释放，返回释放笔数 |
| GET | `/api/requests/{requestNo}` | 申请、预留明细及换血任务查询 |
| POST | `/api/recalls` | 发布批次召回（幂等重放/同号异内容 409） |
| POST | `/api/recalls/activate-due` | 手动激活已到生效时间的召回，返回激活数 |
| GET | `/api/recalls/{eventNo}` | 召回详情：召回范围、受影响申请、换血前后明细、未解决原因 |
| POST | `/api/recalls/tasks/{taskId}/execute` | 按任务 id 执行/重试换血（幂等） |
| POST | `/api/recalls/{eventNo}/requests/{requestNo}/replace` | 按事件号+申请号执行/重试换血 |
| POST | `/api/recalls/{eventNo}/requests/{requestNo}/terminate` | 人工确认无法换血，释放合格单位并终止预留 |

错误码：400 参数非法，404 申请/单位/召回/任务不存在，409 申请号或召回事件号内容冲突、
含被召回单位时发放被拒，422 相容可用单位不足。

## 测试

- `CompatibilityRulesTest`：ABO/Rh 相容规则单元测试。
- `ReservationServiceIntegrationTest`：分配顺序、相容/失效过滤、原子失败、
  幂等重放与冲突、发放、到期释放与再预留、查询。
- `ReservationConcurrencyTest`：并发申请不共享单位、并发发放幂等、
  发放与到期释放竞争下状态自洽、条件流转只有一个生效。
- `RecallReplacementServiceTest`：召回幂等/冲突、在库隔离与新预留阻断、
  换血任务生成与发放拦截、相容/失效/FEFO 选单位、库存不足原子阻断与重试、
  重复执行幂等、人工终止、到期作废、连续多起召回、召回详情查询。
- `RecallConcurrencyTest`：并发换血只替换一次、发放与换血竞争绝不发放被召回单位、
  换血与到期释放竞争下替代品不会被占用后又退回库存。
- `BloodBankApiTest`：REST 端到端流程、召回换血/终止/定时激活与错误码。
