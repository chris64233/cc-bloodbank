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

- **血液成分单位（BloodComponent）**：唯一条码、成分类型（红细胞/血浆/血小板/冷沉淀）、
  ABO 与 Rh 血型、采集时间、失效时间、库存状态（`AVAILABLE` / `RESERVED` / `ISSUED`）。
- **临床申请（ClinicalRequest）**：唯一申请号、患者 ABO/Rh 血型、所需成分类型、单位数、最晚使用时间。
- **预留（Reservation）**：对应一份申请，含预留时间、有效截止时间与状态
  （`RESERVED` / `ISSUED` / `RELEASED`）。
- **预留明细（ReservationItem）**：一笔预留分配到的各个血液成分单位，释放后保留历史。

## 主要业务规则

### 预留分配

- 只有同时满足以下条件的单位可被预留：成分类型一致、血型相容、当前未失效
  （`expiresAt > now`）、失效时间不早于最晚使用时间（`expiresAt >= latestUseTime`）、
  状态为 `AVAILABLE`。
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
  或 `POST /api/reservations/release-expired` 释放，全部单位回到 `AVAILABLE`，可再次预留。
- 截止后请求发放按到期释放处理，不会发放过期预留。
- **幂等**：重复发放、重复释放都返回当前状态，不重复改变库存。

### 幂等重放与并发

- 相同申请号 + 相同申请内容重放：返回原分配（无论预留当前状态），不重复占用单位。
- 相同申请号 + 不同内容：返回 409 冲突。
- 并发安全：
  - 候选单位按 `SELECT ... FOR UPDATE` 悲观锁选取，并以
    `UPDATE ... WHERE status = 'AVAILABLE'` 条件更新兜底——并发申请不可能预留同一单位。
  - 发放与到期释放通过预留行悲观锁 + `WHERE status = 'RESERVED'` 条件状态流转，
    并发时**只有一个状态变化生效**。
- 数据库约束兜底：申请号唯一、一笔申请至多一笔预留（`request_id` 唯一）。
- 时间判断统一使用可替换的 `Clock` Bean（生产为系统 UTC 时钟，测试使用可变时钟）。

## API 概览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/components` | 登记血液成分单位 |
| GET | `/api/components` | 库存状态查询（`?status=AVAILABLE/RESERVED/ISSUED` 可选） |
| GET | `/api/components/{barcode}` | 按条码查询单位 |
| POST | `/api/reservations` | 提交申请并原子预留（幂等重放） |
| POST | `/api/reservations/{requestNo}/issue` | 确认发放（幂等） |
| POST | `/api/reservations/release-expired` | 手动触发到期释放，返回释放笔数 |
| GET | `/api/requests/{requestNo}` | 申请及预留明细查询 |

错误码：400 参数非法，404 申请/单位不存在，409 申请号内容冲突或唯一性冲突，
422 相容可用单位不足。

## 测试

- `CompatibilityRulesTest`：ABO/Rh 相容规则单元测试。
- `ReservationServiceIntegrationTest`：分配顺序、相容/失效过滤、原子失败、
  幂等重放与冲突、发放、到期释放与再预留、查询。
- `ReservationConcurrencyTest`：并发申请不共享单位、并发发放幂等、
  发放与到期释放竞争下状态自洽、条件流转只有一个生效。
- `BloodBankApiTest`：REST 端到端流程与错误码。
