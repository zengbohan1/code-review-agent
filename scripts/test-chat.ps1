# 客服 Agent 自测脚本：三场景 + RAG + 转人工（可重复运行）
# 用法: pwsh -ExecutionPolicy Bypass -File scripts/test-chat.ps1   （需先启动应用）
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$base = 'http://127.0.0.1:8080/api/chat'
$envFile = Join-Path $PSScriptRoot '..\.env'

# 重置 mock 数据：清空退款/流水，恢复订单与订阅初始状态（保证脚本可重复执行）
$psql = Join-Path $env:USERPROFILE 'pgsql\17\bin\psql.exe'
if ((Test-Path $envFile) -and (Test-Path $psql)) {
    $dbPwd = (Get-Content $envFile | Where-Object { $_ -match '^DB_PASSWORD=(.+)$' } | ForEach-Object { $matches[1] } | Select-Object -First 1)
    if ($dbPwd) {
        $env:PGPASSWORD = $dbPwd
        & $psql -w -h 127.0.0.1 -p 5432 -U postgres -d shortdrama -c "DELETE FROM mock_refund; DELETE FROM mock_order_flow; UPDATE mock_order SET status='PAID'; UPDATE mock_subscription SET status='ACTIVE', cancelled_at=NULL;" *> $null
        Write-Host '[init] mock 数据已重置'
    }
} else {
    Write-Host '[warn] 未找到 psql 或 .env，跳过数据重置'
}

function Send-Chat([string]$msg, [string]$session) {
    $body = @{ message = $msg; sessionId = $session } | ConvertTo-Json -Compress
    try {
        $r = Invoke-RestMethod -Uri $base -Method Post -ContentType 'application/json; charset=utf-8' `
            -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 90
        Write-Host ("  [intent=$($r.intent)] " + $r.reply)
        Write-Host ''
    } catch {
        $err = $_.ErrorDetails.Message
        Write-Host ("  ERR: " + $_.Exception.Message + " | " + $err)
        Write-Host ''
    }
}

Write-Host '=== 场景1: 退款完整流 ==='
Send-Chat '我想退款' 'sess-refund'
Send-Chat '订单 ORD-20260701-0001 重复扣费了，帮我退款' 'sess-refund'
Send-Chat '是的，确认退款' 'sess-refund'

Write-Host '=== 场景2: 取消订阅完整流 ==='
Send-Chat '帮我取消订阅 SUB-20260601-A1' 'sess-sub'
Send-Chat '确认取消' 'sess-sub'

Write-Host '=== 场景3: 剧集问题 ==='
Send-Chat '重生之都市修仙这部剧有多少集，从第几集开始收费？' 'sess-series'

Write-Host '=== 场景4: RAG 策略问答 ==='
Send-Chat '退款到账一般要多久？' 'sess-rag'
Send-Chat '自动续费失败怎么办？' 'sess-rag'

Write-Host '=== 附加: 闲聊 ==='
Send-Chat '你好呀' 'sess-chat'

Write-Host '=== 附加: 转人工 ==='
Send-Chat '我要投诉，转人工客服' 'sess-human'

Write-Host '=== 附加: 售后进度 ==='
Send-Chat '我的退款到哪了？订单号 ORD-20260702-0004' 'sess-after'
