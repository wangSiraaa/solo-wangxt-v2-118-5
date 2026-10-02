# 集团内部债务清算试算台（Intercompany Clearing Trial）

围绕 **甲欠乙、乙欠丙、丙又欠甲** 这类集团内部债务，资金部可以按
**协议边界 × 币种** 试算多边净额，减少付款笔数，同时：

- **各法人净头寸不变**：清算腿在“分”层面严格还原每个法人的净额头寸；
- **协议边界不变**：互抵只发生在同一抵销协议成员之间，且不越币种；
- **排除质押与争议债权**：逐项给出排除原因；
- **跨币种可审计**：每一笔都列出汇率、汇率时点、精确换算(6 位小数)、
  入账换算(2 位小数)、尾差及其归属法人；
- **可追溯**：在 Angular 债务图上点任意一条边，都能追到被抵销的原始发票；
- **不接真实银行**：只有 `SIMULATED → CONFIRMED → PAID_SIMULATED`
  三个状态，付款只写模拟时间戳，不产生任何银行指令。

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 3.3 · Java 17 · Spring Data JPA · Flyway |
| 计算 | 纯 Java `NettingPlanner`，全程 `BigDecimal`（HALF_UP），无 double |
| 数据库 | PostgreSQL 16（建表与演示数据均由 Flyway 管理）；本地可用 H2(PG 模式) |
| 前端 | Angular 18（standalone 组件）· 原生 SVG 债务图 |

## 目录

```
backend/   Spring Boot 服务、净额引擎、Flyway 脚本、测试
frontend/  Angular 18 单页应用（债务图 / 追溯面板 / 批次生命周期）
docker-compose.yml  PostgreSQL + 后端
```

## 快速开始

### 方式一：Docker Compose（PostgreSQL）

```bash
docker compose up --build
# 后端: http://localhost:8080   （Flyway 自动建表并写入演示数据）
```

### 方式二：本地已有 PostgreSQL

```bash
createdb clearing
cd backend
DB_HOST=localhost DB_PORT=5432 DB_NAME=clearing DB_USER=.. DB_PASSWORD=.. \
  ./mvnw spring-boot:run
```

### 方式三：免数据库本地体验（H2，仅 dev）

```bash
cd backend
java -jar target/intercompany-clearing-1.0.0.jar --spring.profiles.active=dev
```

前端：

```bash
cd frontend
npm install
npm start            # http://localhost:4200 ，/api 代理到 8080
```

## 演示数据（估值日 2026-09-30）

| 协议 | 场景 | 结果 |
|---|---|---|
| `NA-CNY` | 三方环 1000/600/400 + 闭合环 200×3；另有 1 笔质押、1 笔争议 | 环 1：3 笔→2 笔实付（净 600）；环 2：0 笔实付（全部抵销）；质押/争议被排除 |
| `NA-NOFF` | 协议**禁止抵销**，500×3 闭合环 | 3 笔原始债务原样保留，金额/币种/发票不变 |
| `NA-XCCY` | 跨币种环（100 USD + CNY），结算币种 CNY | USD 按 08:30 快照 7.20535 换算：720.535 精确 → 720.54 入账，尾差 +0.005 归属 A；EUR 发票因超币种范围被排除 |

## 业务与计算规则

1. **分组**：一个批次按 (协议, 结算币种) 切分为多个互不相通的 group。
   - 协议禁止互抵（`allows_netting=false`）→ **pass-through**：每笔原债 1:1 保留；
   - 允许互抵但禁止跨币种 → 每个币种各自净额；
   - 允许跨币种 → 全部换算到协议结算币种后净额。
2. **排除**（留痕在 `batch_exclusion`）：非协议成员对、质押 `PLEDGED`、
   争议 `DISPUTED`、非开放状态、币种超范围、跨币种无汇率（`FX_RATE_MISSING`）。
3. **净额**：每笔债权换算后在债务方形成应付切片、债权方形成应收切片；
   每个法人 `净头寸 = Σ应付 − Σ应收`。净付方/净收方之间贪心匹配产生
   分层面严格平衡的实付腿。
4. **抵销追溯**：同一法人处被冲销的应付/应收切片两两匹配，形成
   **零金额“抵销备忘录腿”**，链路上保留两端原始发票。因此三方环
   缩减笔数后，图上仍能逐笔点回被抵销的发票；完全闭合的环实付为 0，
   但三张发票都在抵销腿中可见。
5. **尾差**：跨币种逐笔 `精确(6dp)` 与 `入账(2dp)` 都落库，
   `rounding_diff = 入账 − 精确`，承担人取协议 `rounding_bearer`。
   因为头寸是在入账（2 位小数）层面汇总，实付腿天然零和，
   **尾差不会改变任何法人的余额**；引擎内置不变量校验：
   实付腿在每个法人处的现金流必须等于其入账净头寸，否则直接抛错。
6. **生命周期**：
   - 试算 `SIMULATED`：只写批次结果，原始债权一行不改；
   - 确认 `CONFIRMED`：把被抵销的开放债权标记为 `SETTLED` 并回填批次号；
     pass-through 分组的债权保持开放；重复确认返回 409；
   - 模拟付款 `PAID_SIMULATED`：只给实付腿打付款时间戳，**不接银行**。

## 主要 API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/entities` `/api/agreements` `/api/claims` `/api/fx-rates` | 主数据 |
| POST | `/api/batches/simulate` | 生成试算批次（不动债权） |
| GET | `/api/batches` `/api/batches/{id}` | 批次列表 / 详情（含腿、逐笔追溯、排除） |
| POST | `/api/batches/{id}/confirm` | 确认方案 |
| POST | `/api/batches/{id}/pay-simulated` | 模拟付款 |

## 数据表（PostgreSQL，金额一律 `DECIMAL`，绝不用浮点）

`legal_entity · netting_agreement · agreement_member · agreement_currency ·
claim · fx_rate · netting_batch · batch_group · batch_leg · leg_item · batch_exclusion`

原始债权表 `claim` 永不被改写，只在确认时回填 `offset_batch_id`；
所有抵销结构都在 `batch_*` / `leg_item` 中独立保留。

## 测试与验收

```bash
cd backend && ./mvnw clean test
```

- `NettingPlannerTest`（10 个）：三方环缩减笔数、闭合环零实付但保留发票追溯、
  禁止互抵保留原债、质押/争议/非成员/币种排除、跨币种汇率-时点-尾差、
  逆汇率、缺汇率单独排除，以及 **200 组随机账册** 下
  “分层面零和 + 每张发票两侧恰好被追溯一次”的不变量；
- `ClearingApplicationTests`（Spring 全栈）：在演示数据上验证
  `NA-CNY` 缩减、`NA-NOFF` 保留、`NA-XCCY` 尾差、
  确认后原债权状态变化、pass-through 债权仍开放、
  模拟付款打标、重复确认冲突。
