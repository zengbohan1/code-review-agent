# 短剧订阅智能客服 Agent

基于大模型 + 函数调用接入订单/支付系统的智能客服 Agent：意图识别、多轮澄清、工具调用、RAG 策略问答、会话记忆、转人工兜底，全链路生产化工程能力。

## 功能亮点

- **意图识别 + 函数调用**：LLM 结构化输出识别 6 类意图（退款/支付/剧集/订阅/转人工/闲聊），6 个 `@Tool` 按真实客诉接口语义接入 mock 订单/支付系统
- **多轮澄清 + 转人工兜底**：低置信度自动追问，澄清超限/LLM 故障/用户要求时生成工单转人工，系统不瘫
- **RAG 策略问答 + 会话记忆**：FAQ 策略库本地向量化检索注入回答；窗口记忆实现多轮上下文（用户确认退款时记得前文）
- **生产化三件套**：SSE 流式输出实时渲染、LLM 调用自动重试 + 降级兜底、Token 成本按 DeepSeek 定价实时统计

## 架构

```
┌─────────────┐   POST /api/chat(/stream)    ┌──────────────────────────────┐
│  demo.html  │ ──────────────────────────▶  │   ChatController (SSE)       │
│  (静态页)    │ ◀── SSE 逐 token 推送 ─────── │    意图识别 → 澄清/路由/兜底    │
└─────────────┘                              └──────────────┬───────────────┘
                                                           │
                    ┌──────────────────────────────────────┼──────────────────────┐
                    ▼                                      ▼                      ▼
           ┌────────────────┐                    ┌────────────────┐     ┌────────────────┐
           │   IntentService │                    │  ChatClient    │     │ EscalationService│
           │  (结构化输出)     │                    │  + 6 @Tool      │     │  (工单转人工)     │
           └────────────────┘                    └───────┬────────┘     └────────────────┘
                                                         │
                                    ┌────────────────────┼────────────────────┐
                                    ▼                    ▼                    ▼
                          ┌────────────────┐   ┌────────────────┐   ┌────────────────┐
                          │ Memory Advisor │   │   RAG Advisor  │   │  Cost Advisor  │
                          │ (会话记忆)       │   │ (pgvector 检索) │   │ (token 成本)    │
                          └────────────────┘   └────────────────┘   └────────────────┘
                                                         │
                                                  ┌──────▼──────┐
                                                  │  PostgreSQL  │
                                                  │  + pgvector  │
                                                  └─────────────┘
```

## 技术栈

| 层 | 选型 |
|---|---|
| 语言/框架 | Java 17 + Spring Boot 3.5 |
| AI 框架 | Spring AI 1.1（ChatClient / @Tool / Advisor / Streaming） |
| LLM | DeepSeek（OpenAI 兼容层，base-url=api.deepseek.com） |
| Embedding | 本地 ONNX 中文模型（bge-small-zh-v1.5，零外部依赖） |
| 向量库 | PostgreSQL + pgvector（HNSW 索引 + 余弦距离） |
| 存储 | PostgreSQL（mock 订单/订阅/退款 + 工单） |
| 前端演示 | static/demo.html（SSE 流式渲染） |

## 快速开始

**环境**：Java 17 + Maven 3.9+ + PostgreSQL 17（含 pgvector）+ DeepSeek API key

```bash
# 1. 配置 API key（绝不明文入库，只进环境变量）
export DEEPSEEK_API_KEY=sk-xxx

# 2. 下载本地 embedding 模型（约 90MB，从 hf-mirror 镜像）
pwsh -ExecutionPolicy Bypass -File scripts/download-model.ps1

# 3. 初始化本地数据库（解压 PG zip → initdb → 建库 shortdrama → 启用 vector）
#    连接信息写入 .env（已被 .gitignore 排除）
pwsh -ExecutionPolicy Bypass -File scripts/init-pg.ps1

# 4. 启动（schema.sql / data.sql 自动建表灌种子数据，FAQ 自动向量化）
mvn spring-boot:run

# 5. 打开演示页
http://localhost:8080/demo.html
```

## 演示对话

| 场景 | 示例输入 |
|---|---|
| 退款 | 「我想退款」「订单 ORD-20260701-0001 重复扣费了」 |
| 取消订阅 | 「帮我取消订阅 SUB-20260601-A1」 |
| 剧集 | 「重生之都市修仙从第几集开始收费？」 |
| 策略问答 | 「退款到账要多久？」「自动续费失败怎么办？」 |
| 转人工 | 「我要投诉，转人工」 |

## 设计说明

- **订单系统为模拟实现**：本项目基于真实客诉接口语义（订单搜索/预检/退款/取消订阅/售后）自建 mock 服务，接口行为与真实系统保持一致，便于本地演示与测试
- **退款双确认**：`confirmed + secondaryConfirmed` 服务端强校验，防止绕过页面直接调用高风险退款接口
- **分笔退款防超额**：同订单已成功退款累计 + 本次 ≤ 支付金额，事务内行级锁（`SELECT ... FOR UPDATE`）杜绝并发下超额；累计退至支付金额即视为全额退款
- **取消订阅状态机**：先落库 `CANCELLING` 再请求平台，最终状态由平台回调驱动（mock 模拟回调），超时重试不丢单
- **转人工兜底**：低置信度 / LLM 故障 / 用户要求人工 → 工单落库 + 话术引导，系统不因模型故障瘫痪
- **意图识别少样本**：6 类意图的结构化输出 prompt 内置少样本示例，提升口语化表达（如"我钱被扣了"）的识别准确率
- **RAG 中文检索**：DeepSeek 无 embedding API，选用本地 ONNX 中文模型（bge-small-zh-v1.5）零成本向量化；模型文件本地化加载，不依赖外部服务
- **成本统计口径**：按 DeepSeek 官方定价（输入 $0.27/M、输出 $1.10/M）折算，展示可观测能力，精确对账以平台账单为准

## 指标口径

| 指标 | 口径 |
|---|---|
| 日均处理数百笔咨询 | 客服咨询量级估算：日活用户 × 0.01%~0.1%（短剧平台日活数十万量级） |
| 转人工率下降 30% | 上线前后人工工单量对比；参考电商客服机器人行业降幅 30%~45% 取下探区间 |

> 项目效果为量级估算口径，用于说明设计目标；实际数值以线上业务统计为准。

## 技术选型

- **Spring AI 1.1**：Spring 官方生态与 Boot 无缝集成；Advisor 横切机制让记忆 / RAG / 成本采集对业务代码零侵入；ChatClient 统一入口
- **DeepSeek + OpenAI 兼容层**：Spring AI 无 DeepSeek 官方 starter，兼容层接入成本最低，模型可随时替换
- **本地 ONNX embedding**：DeepSeek 无 embedding API；本地中文模型零外部依赖、零调用成本
- **pgvector**：PostgreSQL 原生扩展，向量检索与业务数据同库（HNSW 索引 + 余弦距离），免引入独立向量数据库

## 目录结构

```
src/main/java/com/shortdrama/agent/
├── config/     # DeepSeek 接入、向量库、记忆、RAG Advisor
├── controller/ # ChatController（SSE 流式 + 非流式）
├── agent/      # 意图识别 / 多轮澄清 / 转人工
├── tools/      # 6 个 @Tool（映射真实客服接口语义）
├── rag/        # FAQ 加载与向量化
├── mock/       # Mock 订单/订阅/退款系统
├── cost/       # Token 成本采集与统计
└── common/     # 统一返回、全局异常、重试配置
```
