param(
    [string]$BaseUri = "http://localhost:8080",
    [switch]$Apply
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$localEnvPath = Join-Path $repoRoot ".env.local"
$statePath = Join-Path $PSScriptRoot ".state\v070-slice1b4-production-promotion.json"

if (-not (Test-Path -LiteralPath $localEnvPath)) {
    throw ".env.local does not exist."
}
if (-not (Test-Path -LiteralPath $statePath)) {
    throw "No saved V0.7 Slice 1B-4 promotion state was found. Refusing to guess rollback values."
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

$state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
if ($state.version -ne "V0.7-SLICE1B4") {
    throw "Unexpected promotion state version: $($state.version)"
}

Write-Host "V0.7 Slice 1B-4 rollback plan:"
Write-Host ("Query Rewrite: {0}" -f $state.previous.queryRewriteEnabled)
Write-Host ("RRF k        : {0}" -f $state.previous.rrfK)
Write-Host ("Evaluation   : {0}" -f $state.previous.evaluationEnabled)

if (-not $Apply) {
    Write-Host ""
    Write-Host "Dry run only. Re-run with -Apply to restore the saved pre-promotion state."
    exit 0
}

Set-EnvFileValue $localEnvPath "AGENTFORGE_KNOWLEDGE_QUERY_REWRITE_ENABLED" ([bool]$state.previous.queryRewriteEnabled).ToString().ToLowerInvariant()
Set-EnvFileValue $localEnvPath "AGENTFORGE_KNOWLEDGE_RRF_K" ([int]$state.previous.rrfK).ToString()
Set-EnvFileValue $localEnvPath "AGENTFORGE_KNOWLEDGE_EVALUATION_ENABLED" ([bool]$state.previous.evaluationEnabled).ToString().ToLowerInvariant()

Write-Host ""
Write-Host "Saved pre-promotion configuration restored to ignored .env.local."
Write-Host "The running process is unchanged until restart."
Write-Host "Restart AgentForge, then inspect /api/v1/system/health."
