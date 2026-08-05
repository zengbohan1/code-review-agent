# short-drama-agent 设计方案

短剧订阅智能客服 Agent —— 基于大模型 + 函数调用接入订单/支付系统，意图识别、多轮澄清、转人工兜底。

> 项目根目录：`C:\Users\EDY\Desktop\曾波涵\project\short-drama-agent`
> 仓库名：`short-drama-agent`（GitHub: zengbohan1）
> 状态：设计已定稿，待开工（仅落盘方案，未动代码）

---

## 0. 环境盘点（已实测）

| 项 | 状态 |
|---|---|
| Java 17 / Maven / Git / Python 3.11 | ✅ 就绪 |
| DeepSeek API key | ✅ 已验证可用（deepseek-v4-flash） |
| PostgreSQL + pgvector | ❌ 未安装（无 Docker 可替代） |

**待办**：安装 PostgreSQL（winget），并启用 pgvector 扩展。

---

## 1. 技术栈

- Java 17 + Spring Boot 3.5 + Spring AI 1.1.x（ChatClient、@Tool 注解、Advisor、Streaming）
- **DeepSeek 接入**：Spring AI 无 DeepSeek 官方 starter → 走 OpenAI 兼容层，`base-url` 指向 `https://api.deepseek.com`（已验证兼容）
- **Embedding（关键坑）**：DeepSeek **没有 embedding API**。RAG 向量化改用本地 ONNX 模型（Spring AI `spring-ai-transformers`，all-MiniLM-L6-v2，约 90MB，从 hf-mirror 镜像下载）——零成本、不依赖外部服务、模型文件可进 git
- **PostgreSQL + pgvector**：向量存储（HNSW 索引）+ 订单 mock 数据（更真实，能讲 SQL）
- **前端演示**：`static/demo.html` 聊天页 + SSE

---

## 2. 架构分层（对齐 Spring AI 分层 + DDD 习惯）

```
short-drama-agent/
├── config/        # DeepSeek(OpenAI兼容) 配置、VectorStore、ChatMemory
├── controller/    # ChatController(SSE) / CostController / SessionController
├── agent/         # IntentService(结构化意图识别) / EscalationService(转人工) / ClarifyService(多轮澄清)
├── tools/         # 6 个 @Tool（映射真实客服接口）
├── rag/           # FaqLoader(加载+chunking) / 向量检索 / QA Advisor
├── mock/          # MockOrderService + 种子数据（模拟订单/订阅/退款系统）
├── cost/          # TokenUsage 采集 + DeepSeek 定价表 + 成本接口
└── common/        # Result、全局异常、降级兜底
```

---

## 3. 工具模型（映射真实接口语义）

来源：`order_customer_service_api.md`（manage-service 真实客诉接口设计文档）

| @Tool | 对应真实接口 | 模拟的业务规则 |
|---|---|---|
| `searchOrder` | `GET /orders/search` | payPlatform 1-5（Google/Apple/Antom/Stripe/PayPal）+ 订单号，返回多命中不选 |
| `getOrderPreview` | `GET /orders/{id}/preview` | 剩余可退金额、权益快照、操作前预检 |
| `refund` | `POST /refund` | 双确认（confirmed+secondaryConfirmed）、分笔退款不超支付额、已用权益需 allowUsedEquity |
| `cancelSubscription` | `POST /cancel-subscription` | 先落库再请求平台，状态由回调决定 |
| `getAfterSaleDetail` | `GET /after-sales/{orderId}` | 售后详情、操作记录、失败原因 |
| `getSeriesInfo` | content 域 Series 语义 | 剧集信息、总集数、付费卡点（对接"剧集问题"场景） |

规则逐条从真实文档抄语义、自己写实现（双确认、分笔防超额、先落库再回调），保证工具行为与真实接口语义一致。

---

## 4. 意图识别

LLM + Structured Output（`@JsonOutput` 强制 JSON）：输出 `{intent: REFUND|PAYMENT|SERIES|SUBSCRIPTION|ESCALATE|CHITCHAT, slots, confidence}`。

- 低置信度 → 多轮澄清
- 明确无法解决 → 转人工（生成工单）
- 刻意不写关键词硬路由——展示模型能力

---

## 5. RAG

4 类 FAQ 文档（退款政策/订阅说明/支付问题/剧集问题）→ 按标题 chunking → ONNX 本地 embedding → pgvector 存 + HNSW 索引 → 检索 Top-K → `QuestionAnswerAdvisor` 注入 prompt。

---

## 6. 生产化三件套

- **SSE**：流式输出（demo.html 实时渲染）
- **降级重试**：LLM 调用失败 → Spring Retry 重试 → 降级为规则兜底回答 + 转人工标记（模型挂了系统不瘫）
- **Token 成本**：Advisor 采集 TokenUsage，按 DeepSeek 单价记账，`GET /api/cost` 可查

---

## 7. 数据库（PostgreSQL，字段对齐真实语义）

`mock_order`（order_no/payment_id/pay_platform/金额/状态）、`mock_subscription`、`mock_refund`（累计退款校验）、`mock_order_flow`（流水）、`faq_chunk`（向量列）、`session_memory`（可选）

---

## 8. 交付节奏

| Phase | 内容 | 对应任务 |
|---|---|---|
| P1 | 脚手架 + DeepSeek 连通 | 任务 #2 |
| P2 | PG + pgvector + mock 数据 + 工具 + 意图识别 | 任务 #3 |
| P3 | RAG | 任务 #4 |
| P4 | SSE + 降级 + 成本 | 任务 #5 |
| P5 | 自测三场景 + README + push | 任务 #6 |

---

## 9. 诚实风险清单

1. **需安装 PostgreSQL + pgvector**（唯一需要装的软件）——Windows 上用 winget 装，装完我来配
2. ONNX 模型下载可能被墙 → 用 hf-mirror 镜像
3. DeepSeek 走 OpenAI 兼容层，个别参数（temperature 支持度）需实测
4. **key 轮换**：用户已发的 key 在对话记录里，开工前建议去 DeepSeek 后台换新 key（只存环境变量，不进 git）
