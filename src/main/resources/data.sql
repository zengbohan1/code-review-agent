-- 种子数据：覆盖各支付平台 / 各订单状态 / 退款与订阅场景，用于自测三场景
-- 幂等：仅当 mock_order 为空时插入

INSERT INTO mock_order (order_no, payment_id, pay_platform, amount, currency, status)
SELECT * FROM (VALUES
    ('ORD-20260701-0001', 'GP-8A2F1C', 1, 9.99,  'USD', 'PAID'),
    ('ORD-20260701-0002', 'AP-77B3D9', 2, 12.99, 'USD', 'PAID'),
    ('ORD-20260702-0003', 'AN-1E9A4B', 3, 6.99,  'USD', 'PAID'),
    ('ORD-20260702-0004', 'ST-5C6F8E', 4, 9.99,  'USD', 'REFUNDED'),
    ('ORD-20260703-0005', 'PP-9D2E7A', 5, 19.99, 'USD', 'PAID'),
    ('ORD-20260703-0006', NULL,         1, 4.99,  'USD', 'PAID')
) AS v(order_no, payment_id, pay_platform, amount, currency, status)
WHERE NOT EXISTS (SELECT 1 FROM mock_order);

INSERT INTO mock_subscription (subscription_no, user_id, plan_name, amount, currency, status, next_billing_at)
SELECT * FROM (VALUES
    ('SUB-20260601-A1', 'U-10001', '月度会员', 9.99,  'USD', 'ACTIVE',   now() + interval '20 days'),
    ('SUB-20260515-B2', 'U-10002', '季度会员', 26.99, 'USD', 'ACTIVE',   now() + interval '12 days'),
    ('SUB-20260420-C3', 'U-10003', '月度会员', 9.99,  'USD', 'CANCELLED', NULL),
    ('SUB-20260701-D4', 'U-10001', '年度会员', 89.99, 'USD', 'ACTIVE',   now() + interval '300 days')
) AS v(subscription_no, user_id, plan_name, amount, currency, status, next_billing_at)
WHERE NOT EXISTS (SELECT 1 FROM mock_subscription);

INSERT INTO mock_refund (order_id, refund_no, amount, status, confirmed, secondary_confirmed, allow_used_equity, reason, success_at)
SELECT o.id, 'RF-' || o.order_no, o.amount, 'SUCCESS', TRUE, TRUE, FALSE, '用户重复扣费', o.created_at
FROM mock_order o
WHERE o.status = 'REFUNDED'
  AND NOT EXISTS (SELECT 1 FROM mock_refund);

-- 流水：退款订单留痕（REFUNDED 状态由"平台回调"驱动）
INSERT INTO mock_order_flow (order_id, flow_type, from_status, to_status, operator, remark)
SELECT o.id, 'REFUND', 'PAID', 'REFUNDED', 'AGENT', 'mock 平台回调确认退款成功'
FROM mock_order o
WHERE o.status = 'REFUNDED'
  AND NOT EXISTS (SELECT 1 FROM mock_order_flow WHERE flow_type = 'REFUND' AND order_id = o.id);
