# 客服 Agent 自测脚本 v2：三场景完整对话流（含确认轮次）
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$base = 'http://127.0.0.1:8080/api/chat'

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

Write-Host '=== 附加: 闲聊 ==='
Send-Chat '你好呀' 'sess-chat'

Write-Host '=== 附加: 转人工 ==='
Send-Chat '我要投诉，转人工客服' 'sess-human'

Write-Host '=== 附加: 售后进度 ==='
Send-Chat '我的退款到哪了？订单号 ORD-20260702-0004' 'sess-after'
