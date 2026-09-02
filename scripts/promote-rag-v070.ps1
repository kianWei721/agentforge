param(
    [string]$BaseUri = "http://localhost:8080",
    [switch]$Apply,
    [switch]$KeepEvaluationEnabled
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$localEnvPath = Join-Path $repoRoot ".env.local"
$stateDir = Join-Path $PSScriptRoot ".state"
$statePath = Join-Path $stateDir "v070-slice1b4-production-promotion.json"

if (-not (Test-Path -LiteralPath $localEnvPath)) {
    throw ".env.local does not exist. Run .\scripts\setup-dev.ps1 first."
}

function Set-EnvFileValue {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Name,
        [AllowEmptyString()][string]$Value
    )

    $lines = @(Get-Content -LiteralPath $Path)
    $prefix = $Name + "="
    $found = $false
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i].StartsWith($prefix)) {
            $lines[$i] = $prefix + $Value
            $found = $true
            break
        }
    }
    if (-not $found) {
        $lines += $prefix + $Value
    }

    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllLines($Path, $lines, $utf8NoBom)
}

function Write-JsonFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Value
    )
    $json = $Value | ConvertTo-Json -Depth 8
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($Path, $json, $utf8NoBom)
}

$healthUri = $BaseUri.TrimEnd("/") + "/api/v1/system/health"
try {
    $health = Invoke-RestMethod -Method Get -Uri $healthUri
}
catch {
    throw "Cannot read AgentForge health at $healthUri. Keep the current process running for promotion preflight, then retry."
}

if ($health.milestone -ne "V0.7-slice1b4-production-promotion") {
    throw "Expected V0.7 Slice 1B-4 code before promotion. Actual milestone: $($health.milestone)"
}
if ($health.status -ne "UP") {
    throw "AgentForge health must be UP before promotion. Actual: $($health.status)"
}
if (-not $health.knowledgeHybridEnabled -or -not $health.knowledgeHybridConfigured) {
    throw "Hybrid knowledge retrieval must be enabled and configured before promotion."
}
if (-not $health.knowledgeRerankEnabled -or -not $health.knowledgeRerankConfigured) {
    throw "Reranker must be enabled and configured before promotion."
}
if (-not $health.knowledgeAnswerabilityEnabled -or -not $health.knowledgeAnswerabilityConfigured) {
    throw "Answerability Judge must be enabled and configured before promotion."
}

if ($health.knowledgeProductionPromotionState -eq "PROMOTED_ORIGINAL_K5") {
    Write-Host "Production retrieval is already promoted: ORIGINAL + RRF k=5."
    Write-Host "Run .\scripts\verify-rag-promotion-v070.ps1 after restart/when needed."
    exit 0
}

if ($health.knowledgeProductionPromotionState -ne "LEGACY_CONTROL_REWRITE_K60") {
    throw ("Promotion requires the accepted legacy control REWRITE+k60 as the active pre-state. Actual: {0}" -f $health.knowledgeProductionBaseline)
}

$targetEvaluationEnabled =
    if ($KeepEvaluationEnabled) { [bool]$health.knowledgeEvaluationEnabled } else { $false }

Write-Host "V0.7 Slice 1B-4 Production Promotion preflight passed."
Write-Host ("Current: QueryStrategy={0}, RrfK={1}, EvaluationEnabled={2}" -f
    $health.knowledgeProductionQueryStrategy,
    $health.knowledgeProductionRrfK,
    $health.knowledgeEvaluationEnabled)
Write-Host "Target : QueryStrategy=ORIGINAL, RrfK=5"
Write-Host ("Evaluation after promotion: {0}" -f $targetEvaluationEnabled)
Write-Host "No Elasticsearch rebuild, MySQL migration, yudao change, or credential change is required."

if (-not $Apply) {
    Write-Host ""
    Write-Host "Dry run only. Re-run with -Apply to update ignored .env.local."
    Write-Host ".\scripts\promote-rag-v070.ps1 -Apply"
    exit 0
}

if (Test-Path -LiteralPath $statePath) {
    throw "Promotion state already exists at scripts/.state/v070-slice1b4-production-promotion.json. Roll back or remove it only after reviewing the saved state."
}

New-Item -ItemType Directory -Path $stateDir -Force | Out-Null
$state = [ordered]@{
    version = "V0.7-SLICE1B4"
    createdAt = (Get-Date).ToString("o")
    previous = [ordered]@{
        queryRewriteEnabled = [bool]$health.knowledgeQueryRewriteEnabled
        rrfK = [int]$health.knowledgeProductionRrfK
        evaluationEnabled = [bool]$health.knowledgeEvaluationEnabled
    }
    target = [ordered]@{
        queryRewriteEnabled = $false
        rrfK = 5
        evaluationEnabled = $targetEvaluationEnabled
    }
}
Write-JsonFile -Path $statePath -Value $state

Set-EnvFileValue $localEnvPath "AGENTFORGE_KNOWLEDGE_QUERY_REWRITE_ENABLED" "false"
Set-EnvFileValue $localEnvPath "AGENTFORGE_KNOWLEDGE_RRF_K" "5"
Set-EnvFileValue $localEnvPath "AGENTFORGE_KNOWLEDGE_EVALUATION_ENABLED" ($targetEvaluationEnabled.ToString().ToLowerInvariant())

Write-Host ""
Write-Host "Promotion configuration written to ignored .env.local."
Write-Host "Rollback state saved under ignored scripts/.state/."
Write-Host "The running process is unchanged until restart."
Write-Host ""
Write-Host "Next:"
Write-Host "1. Stop the current AgentForge process."
Write-Host "2. Start .\scripts\run-dashscope.ps1"
Write-Host "3. In another PowerShell window run .\scripts\verify-rag-promotion-v070.ps1"
