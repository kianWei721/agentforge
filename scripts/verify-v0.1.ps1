$ErrorActionPreference = "Stop"

$baseUrl = if ($env:AGENTFORGE_BASE_URL) { $env:AGENTFORGE_BASE_URL.TrimEnd('/') } else { "http://localhost:8080" }

Write-Host "[1/3] Health"
$health = Invoke-RestMethod -Method Get -Uri "$baseUrl/api/v1/system/health"
$health | ConvertTo-Json -Depth 5
Write-Host ""

Write-Host "[2/3] Model Gateway SSE"
curl.exe -N -sS -X POST "$baseUrl/api/v1/chat/stream" `
  -H "Content-Type: application/json" `
  -d '{"userId":"verify-user","sessionId":"verify-model","message":"只回答：MODEL_OK"}'
Write-Host "`n"

if ($health.agentRuntimeEnabled -eq $true) {
    Write-Host "[3/3] AgentScope two-turn session SSE"
    curl.exe -N -sS -X POST "$baseUrl/api/v1/agent/stream" `
      -H "Content-Type: application/json" `
      -d '{"userId":"verify-user","sessionId":"verify-agent","message":"请记住验证码 AF-2026，只回复收到。"}'
    Write-Host "`n"

    curl.exe -N -sS -X POST "$baseUrl/api/v1/agent/stream" `
      -H "Content-Type: application/json" `
      -d '{"userId":"verify-user","sessionId":"verify-agent","message":"刚才的验证码是什么？只回答验证码。"}'
    Write-Host "`n"
} else {
    Write-Host "[3/3] Agent runtime skipped (server reports agentRuntimeEnabled=false)"
}
