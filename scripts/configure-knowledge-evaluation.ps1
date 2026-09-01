$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$localEnvPath = Join-Path $repoRoot ".env.local"

if (-not (Test-Path $localEnvPath)) {
    throw ".env.local does not exist. Run .\scripts\setup-dev.ps1 first."
}

function Read-TextValue {
    param(
        [Parameter(Mandatory = $true)][string]$Prompt,
        [string]$DefaultValue = ""
    )
    $suffix = if ([string]::IsNullOrWhiteSpace($DefaultValue)) { "" } else { " [$DefaultValue]" }
    $value = Read-Host ($Prompt + $suffix)
    if ([string]::IsNullOrWhiteSpace($value)) { return $DefaultValue }
    return $value.Trim()
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
    if (-not $found) { $lines += $prefix + $Value }
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllLines($Path, $lines, $utf8NoBom)
}

Write-Host "Configure AgentForge V0.7 Gold Dataset Evaluation Harness."
Write-Host "The evaluation corpus uses a dedicated Elasticsearch index and never writes to MySQL knowledge tables."
Write-Host ""

$indexName = Read-TextValue "Evaluation Elasticsearch index" "agentforge-knowledge-eval-v070"
$topK = Read-TextValue "Evaluation topK" "5"

if ($indexName -notmatch "^[a-z0-9][a-z0-9._-]{0,254}$") { throw "Invalid Elasticsearch index name." }
if ($topK -notmatch "^[0-9]+$") { throw "topK must be numeric." }
$topKValue = [int]$topK
if ($topKValue -lt 5 -or $topKValue -gt 20) { throw "topK must be between 5 and 20." }

Set-EnvFileValue $localEnvPath "AGENTFORGE_KNOWLEDGE_EVALUATION_ENABLED" "true"
Set-EnvFileValue $localEnvPath "AGENTFORGE_KNOWLEDGE_EVALUATION_INDEX" $indexName
Set-EnvFileValue $localEnvPath "AGENTFORGE_KNOWLEDGE_EVALUATION_TOP_K" $topK

Write-Host ""
Write-Host "Knowledge evaluation configuration saved."
Write-Host ("Index: {0}" -f $indexName)
Write-Host ("topK: {0}" -f $topK)
Write-Host "Dataset: classpath:knowledge/eval/v070-gold-dataset.json"
Write-Host "Next: restart .\scripts\run-dashscope.ps1, then run .\scripts\evaluate-rag-v070.ps1"
