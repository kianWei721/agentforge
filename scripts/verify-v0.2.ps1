$ErrorActionPreference = "Stop"

$baseUrl = if ($env:AGENTFORGE_BASE_URL) { $env:AGENTFORGE_BASE_URL.TrimEnd('/') } else { "http://localhost:8080" }

Write-Host "[1/5] Health"
$health = Invoke-RestMethod -Method Get -Uri "$baseUrl/api/v1/system/health"
$health | ConvertTo-Json -Depth 8
Write-Host ""

Write-Host "[2/5] Tool Registry"
$tools = Invoke-RestMethod -Method Get -Uri "$baseUrl/api/v1/tools"
$tools | ConvertTo-Json -Depth 8
if (-not ($tools.name -contains "query_device_status")) {
    throw "query_device_status is not registered"
}
Write-Host ""

Write-Host "[3/5] Agent CRUD"
$createBody = @{
    name = "verify-agent"
    description = "temporary V0.2 verification agent"
    systemPrompt = "你是测试 Agent。询问设备状态时必须调用 query_device_status。"
    modelProvider = "dashscope"
    modelName = "qwen-plus"
    toolNames = @("query_device_status")
    enabled = $true
} | ConvertTo-Json -Depth 8

$created = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/v1/agents" -ContentType "application/json" -Body $createBody
$created | ConvertTo-Json -Depth 8

$fetched = Invoke-RestMethod -Method Get -Uri "$baseUrl/api/v1/agents/$($created.id)"
if ($fetched.name -ne "verify-agent") {
    throw "Agent CRUD verification failed"
}
Write-Host ""

Write-Host "[4/5] Raw Model Gateway SSE"
curl.exe -N -sS -X POST "$baseUrl/api/v1/chat/stream" -H "Content-Type: application/json" -d '{"userId":"verify-user","sessionId":"verify-model","message":"只回答：MODEL_OK"}'
Write-Host ""

Write-Host "[5/5] Agent Tool Calling"
if ($health.agentRuntimeEnabled -eq $true) {
    curl.exe -N -sS -X POST "$baseUrl/api/v1/agents/$($created.id)/stream" -H "Content-Type: application/json" -d '{"userId":"verify-user","sessionId":"verify-agent","message":"设备367当前是什么状态？请先调用 query_device_status 工具，再根据工具结果回答。"}'
    Write-Host ""
} else {
    Write-Host "Skipped: agentRuntimeEnabled=false. Start with run-dashscope.ps1 to test real Function Calling."
}

Invoke-RestMethod -Method Delete -Uri "$baseUrl/api/v1/agents/$($created.id)"
Write-Host "Temporary verify-agent deleted."
