$ErrorActionPreference = "Stop"

$baseUrl = if ($env:AGENTFORGE_BASE_URL) {
    $env:AGENTFORGE_BASE_URL.TrimEnd('/')
} else {
    "http://localhost:8080"
}
# Stands in for the trusted gateway; the user must be registered in AGENTFORGE_STAFF_USER_IDS.
$userId = if ($env:AGENTFORGE_SCRIPT_USER_ID) { $env:AGENTFORGE_SCRIPT_USER_ID } else { "dev-staff" }
$identity = @{ "X-AgentForge-User-Id" = $userId }

function Invoke-AgentForgeSse {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter(Mandatory = $true)][object]$Payload
    )

    $json = $Payload | ConvertTo-Json -Depth 8 -Compress
    $response = Invoke-WebRequest -Method Post -Uri $Uri -Headers $identity -ContentType "application/json; charset=utf-8" -Body $json -UseBasicParsing
    Write-Host $response.Content
}

$created = $null

try {
    Write-Host "[1/5] Health"
    $health = Invoke-RestMethod -Method Get -Uri "$baseUrl/api/v1/system/health"
    $health | ConvertTo-Json -Depth 8
    Write-Host ""

    Write-Host "[2/5] Tool Registry"
    $tools = Invoke-RestMethod -Method Get -Uri "$baseUrl/api/v1/tools" -Headers $identity
    $tools | ConvertTo-Json -Depth 8
    if (-not ($tools.name -contains "query_device_status")) {
        throw "query_device_status is not registered"
    }
    Write-Host ""

    Write-Host "[3/5] Agent CRUD"
    $createBody = @{
        name = "verify-agent"
        description = "temporary V0.2 verification agent"
        systemPrompt = "You are a test agent. When asked about device status, call query_device_status before answering."
        modelProvider = "dashscope"
        modelName = "qwen-plus"
        toolNames = @("query_device_status")
        enabled = $true
    }

    $created = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/v1/agents" -Headers $identity -ContentType "application/json; charset=utf-8" -Body ($createBody | ConvertTo-Json -Depth 8)
    $created | ConvertTo-Json -Depth 8

    $fetched = Invoke-RestMethod -Method Get -Uri "$baseUrl/api/v1/agents/$($created.id)" -Headers $identity
    if ($fetched.name -ne "verify-agent") {
        throw "Agent CRUD verification failed"
    }
    Write-Host ""

    Write-Host "[4/5] Raw Model Gateway SSE"
    Invoke-AgentForgeSse -Uri "$baseUrl/api/v1/chat/stream" -Payload @{
        sessionId = "verify-model"
        message = "Reply exactly: MODEL_OK"
    }
    Write-Host ""

    Write-Host "[5/5] Agent Tool Calling"
    if ($health.agentRuntimeEnabled -eq $true) {
        Invoke-AgentForgeSse -Uri "$baseUrl/api/v1/agents/$($created.id)/stream" -Payload @{
            sessionId = "verify-agent"
            message = "What is the current status of device 1002? Call query_device_status first, then answer from the tool result."
        }
        Write-Host ""
    } else {
        Write-Host "Skipped: agentRuntimeEnabled=false. Start with run-dashscope.ps1 to test real Function Calling."
    }
}
finally {
    if ($null -ne $created -and -not [string]::IsNullOrWhiteSpace($created.id)) {
        try {
            Invoke-RestMethod -Method Delete -Uri "$baseUrl/api/v1/agents/$($created.id)" -Headers $identity
            Write-Host "Temporary verify-agent deleted."
        }
        catch {
            Write-Warning "Failed to delete temporary verify-agent: $($_.Exception.Message)"
        }
    }
}
