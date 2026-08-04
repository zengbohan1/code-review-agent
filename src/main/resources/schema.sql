-- 短剧订阅智能客服 Agent —— mock 订单/支付系统表结构
-- 字段语义对齐真实客诉接口文档（order_customer_service_api.md / sys_order_flow.md）

-- 订单表：pay_platform 1-5 = Google / Apple / Antom / Stripe / PayPal
CREATE TABLE IF NOT EXISTS mock_order (
    id            BIGSERIAL PRIMARY KEY,
    order_no      VARCHAR(64)  NOT NULL UNIQUE,           -- 商户订单号（用户常报）
    payment_id    VARCHAR(128),                           -- 第三方支付流水号
    pay_platform  SMALLINT     NOT NULL CHECK (pay_platform BETWEEN 1 AND 5),
    amount        NUMERIC(12,2) NOT NULL,                 -- 支付金额（订单原价）
    currency      VARCHAR(8)   NOT NULL DEFAULT 'USD',
    status        VARCHAR(20)  NOT NULL DEFAULT 'PAID',   -- PAID/REFUNDING/REFUNDED/CANCELLED
    created_at    TIMESTAMP    NOT NULL DEFAULT now()
);

-- 订阅表：计划 + 状态机（ACTIVE -> CANCELLING -> CANCELLED，状态由"平台回调"决定）
CREATE TABLE IF NOT EXISTS mock_subscription (
    id              BIGSERIAL PRIMARY KEY,
    subscription_no VARCHAR(64)  NOT NULL UNIQUE,
    user_id         VARCHAR(64)  NOT NULL,
    plan_name       VARCHAR(64)  NOT NULL,                -- 如 "月度会员"
    amount          NUMERIC(12,2) NOT NULL,
    currency        VARCHAR(8)   NOT NULL DEFAULT 'USD',
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE', -- ACTIVE/CANCELLING/CANCELLED
    next_billing_at TIMESTAMP,                            -- 下次扣费时间
    cancelled_at    TIMESTAMP
);

-- 退款表：分笔退款，服务端校验双确认 + 累计不超支付额
CREATE TABLE IF NOT EXISTS mock_refund (
    id                  BIGSERIAL PRIMARY KEY,
    order_id            BIGINT      NOT NULL REFERENCES mock_order(id),
    refund_no           VARCHAR(64) NOT NULL UNIQUE,
    amount              NUMERIC(12,2) NOT NULL,           -- 本笔退款金额
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING/SUCCESS/FAILED
    confirmed           BOOLEAN     NOT NULL DEFAULT FALSE,      -- 第一确认
    secondary_confirmed BOOLEAN     NOT NULL DEFAULT FALSE,      -- 第二确认（防绕过页面直接调接口）
    allow_used_equity   BOOLEAN     NOT NULL DEFAULT FALSE,      -- 已用权益时是否允许退
    reason              VARCHAR(255),
    created_at          TIMESTAMP   NOT NULL DEFAULT now(),
    success_at          TIMESTAMP
);

-- 订单流水表：状态流转留痕（先落库再请求平台，状态由回调决定）
CREATE TABLE IF NOT EXISTS mock_order_flow (
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT      NOT NULL REFERENCES mock_order(id),
    flow_type   VARCHAR(32) NOT NULL,                     -- SEARCH/PREVIEW/REFUND/CANCEL/PAYMENT
    from_status VARCHAR(20),
    to_status   VARCHAR(20),
    operator    VARCHAR(64) NOT NULL DEFAULT 'AGENT',     -- AGENT=智能客服 / USER
    remark      VARCHAR(255),
    created_at  TIMESTAMP   NOT NULL DEFAULT now()
);

-- 转人工工单：置信度过低 / LLM 故障 / 用户要求人工
CREATE TABLE IF NOT EXISTS support_ticket (
    id          BIGSERIAL PRIMARY KEY,
    ticket_no   VARCHAR(64) NOT NULL UNIQUE,
    session_id  VARCHAR(64) NOT NULL,
    user_text   TEXT,
    reason      VARCHAR(64) NOT NULL,                     -- LOW_CONFIDENCE/LLM_FAILURE/USER_REQUEST
    status      VARCHAR(20) NOT NULL DEFAULT 'OPEN',      -- OPEN/CLOSED
    created_at  TIMESTAMP   NOT NULL DEFAULT now()
);
