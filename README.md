# cc-bloodbank

临床用血成分库存与预留服务：管理血液成分单位，按血型相容性与有效期为临床申请**原子预留**血液成分，
支持确认发放、到期自动释放、重放幂等与并发安全。

## 开发环境

- JDK 21
- Spring Boot 4.1.1
- Spring Data JPA + H2（文件库，默认 `./data/cc-bloodbank`）
- Maven Wrapper 3.9.9

## 本地运行

启动服务：

    ./mvnw spring-boot:run

运行测试：

    ./mvnw clean test

## 领域模型

| 对象 | 关键字段 | 状态 |
| --- | --- | --- |
| 血液成分单位 `BloodUnit` | 唯一条码、成分类型、ABO/Rh 血型、采集时间、失效时间 | `AVAILABLE` 可用 → `RESERVED` 已预留 → `ISSUED` 已发放（终态） |
| 临床申请/预留 `Reservation` | 唯一申请号、患者血型、所需成分、单位数、最晚使用时间、预留截止时间 | `RESERVED` → `ISSUED`（终态）/ `RELEASED`（终态） |
| 预留明细 `ReservationItem` | 所属申请、分配序号、被分配单位、明细状态 | `ALLOCATED` → `ISSUED` / `RELEASED` |

## 主要业务规则

### 1. 可预留单位的筛选条件

只有**同时**满足以下条件的单位才能被预留：

1. 成分类型与申请**严格一致**（红细胞不与血浆、血小板混用）；
2. 血型**相容**（按红细胞输注规则，见下表）；
3. 库存状态为 `AVAILABLE`；
4. 当前时间未失效（`expiresAt >= now`）；
5. 失效时间**不早于**申请的最晚使用时间（`expiresAt >= latestUseAt`），保证发到临床仍在效期内。

### 2. 血型相容性

按红细胞抗原规则：供者携带的每种抗原（A、B、D/RhD）受者都必须具备（供者抗原 ⊆ 受者抗原）。

| 受者血型 | 可接受供者血型 |
| --- | --- |
| O- | O- |
| O+ | O-、O+ |
| A- | O-、A- |
| A+ | O-、O+、A-、A+ |
| B- | O-、B- |
| B+ | O-、O+、B-、B+ |
| AB- | O-、A-、B-、AB- |
| AB+ | 全部 8 种（O- 为万能供血者，AB+ 为万能受血者） |

### 3. 分配排序与整笔成败

- 候选单位按**失效时间最早优先（FEFO）**排序，失效时间相同时按**条码升序**稳定排序；
- 依次取前 N 个单位；
- 符合条件的单位不足 N 个时**整笔失败**：不创建申请/明细，不改变任何库存，不存在部分预留。

### 4. 预留有效期、发放与释放

- 每笔预留都有有效截止时间（申请显式传入，或使用默认 TTL 30 分钟）；
- 截止前可**确认发放**：单位 `RESERVED → ISSUED`，进入终态、**不可恢复**；
- **截止时间后不能再发放**（数据库条件更新兜底）；
- 到期未发放的预留由定时任务扫描，释放全部单位：单位 `RESERVED → AVAILABLE`，预留进入 `RELEASED` 终态；
  释放后的单位可被新申请再次预留；
- **重复发放、重复释放均幂等**：第二次调用原样返回/不产生变化，绝不重复改变库存。

### 5. 重放与冲突

- 相同申请号 + **相同内容**（患者血型、成分、单位数、最晚使用时间）重放：返回原分配，不重复占库存
  （首次 `201 Created`，重放 `200 OK`）；原预留即使已发放/已释放也原样返回；
- 相同申请号 + **内容不同**：返回 `409 Conflict`（`REQUEST_CONTENT_CONFLICT`），原分配不受影响。

### 6. 并发安全（数据库兜底）

所有状态与库存变更都在**同一事务**内完成，正确性不依赖应用层加锁，而由数据库保证：

1. 预留事务用 `SELECT … FOR UPDATE` 锁定候选单位，并发预留在此串行化；
2. 库存流转是**条件更新** `UPDATE … WHERE status = :旧状态`，只有旧状态匹配才生效，行数不符即整笔回滚；
3. 发放与到期释放对预留行做**条件状态机更新**，二者并发时影响行数之和恰为 1——只有一个状态变化生效；
   发放更新额外带 `expireAt > now` 条件，到点后发放必然落空；
4. `reservation_item` 上有 H2 **生成列 + 唯一约束**
   （`active_unit_barcode = CASE WHEN status='ALLOCATED' THEN unit_barcode ELSE NULL END`）：
   同一单位全库至多存在于一条有效明细中；发放/释放后生成列变为 NULL，不影响单位再次被预留。

### 7. 时间来源

业务代码只依赖 `java.time.Clock`（`TimeConfig` 提供系统 UTC 时钟 Bean），测试中替换为可任意拨动的
`MutableClock`，用于确定性地验证到期释放、截止后拒绝发放等时间相关规则。

## HTTP 接口

### 登记血液成分单位

    POST /api/units

```json
{
  "barcode": "U001",
  "componentType": "RBC",
  "bloodType": "O-",
  "collectedAt": "2026-08-29T08:00:00Z",
  "expiresAt": "2026-09-29T08:00:00Z"
}
```

成分类型：`RBC`（红细胞）、`PLASMA`（冰冻血浆）、`PLATELET`（血小板）。

### 提交临床申请并预留

    POST /api/reservations

```json
{
  "requestNo": "REQ-20260927-001",
  "patientBloodType": "A+",
  "componentType": "RBC",
  "unitCount": 2,
  "latestUseAt": "2026-09-29T06:00:00Z",
  "expireAt": "2026-09-27T08:30:00Z"
}
```

`expireAt` 可省略（使用默认 TTL）。返回预留明细（按 FEFO+条码排序的单位列表）。
相同内容重放返回 `200` 与原分配；库存不足/内容冲突返回 `409`。

### 查询

| 接口 | 说明 |
| --- | --- |
| `GET /api/units` | 库存查询，可 `?status=AVAILABLE|RESERVED|ISSUED` 过滤 |
| `GET /api/units/{barcode}` | 单个单位状态 |
| `GET /api/reservations` | 全部申请及预留明细 |
| `GET /api/reservations/{requestNo}` | 单笔申请与预留明细 |

### 发放与到期处理

| 接口 | 说明 |
| --- | --- |
| `POST /api/reservations/{requestNo}/issue` | 确认发放（截止前）；重复调用幂等 |
| `POST /api/reservations/{requestNo}/expire` | 手动触发到期判定/释放（通常由定时任务完成） |

到期扫描默认每 30 秒一次（首次延迟 5 秒），可用以下配置调整：

    bloodbank.reservation.default-ttl=30m
    bloodbank.reservation.expiry-scan-delay-ms=30000
    bloodbank.reservation.expiry-scan-initial-delay-ms=5000
    bloodbank.scheduling.enabled=true   # 测试环境置 false 关闭定时扫描

## 自动化测试

- `BloodTypeCompatibilityTest`：8 种血型相容性规则；
- `ReservationServiceTest`：FEFO+条码排序、相容性/成分/效期过滤、整笔失败、重放与冲突、
  发放幂等与终态、截止后拒绝发放、到期释放与幂等、释放后再预留、默认 TTL 等；
- `ReservationConcurrencyTest`：真实多线程——5 笔并发申请永不抢占同一单位、
  多单位并发请求整笔原子性、发放/释放条件更新 20 轮竞争恰好一方生效；
- `ReservationExpiryJobTest`：到期扫描批量释放且不影响有效预留；
- `ReservationApiTest`：REST 端到端（预留、查询、发放、释放、409/404/400 状态码）。
