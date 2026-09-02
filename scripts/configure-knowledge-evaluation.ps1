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
$rrfKValues = Read-TextValue "RRF k sweep values (comma-separated)" "5,20,60,120"

if ($indexName -notmatch "^[a-z0-9][a-z0-9._-]{0,254}$") { throw "Invalid Elasticsearch index name." }
if ($topK -notmatch "^[0-9]+$") { throw "topK must be numeric." }
$topKValue = [int]$topK
if ($topKValue -lt 5 -or $topKValue -gt 20) { throw "topK must be between 5 and 20." }

$rrfParts = @($rrfKValues.Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" })
if ($rrfParts.Count -lt 3 -or $rrfParts.Count -gt 8) {
    throw "RRF k sweep must contain between 3 and 8 values."
}
$rrfSeen = @{}
foreach ($part in $rrfParts) {
    if ($part -notmatch "^[0-9]+$") { throw "Every RRF k value must be numeric." }
    $value = [int]$part
    if ($value -lt 1 -or $value -gt 1000) { throw "Every RRF k value must be between 1 and 1000." }
    if ($rrfSeen.ContainsKey($value)) { throw "RRF k sweep values must be unique." }
    $rrfSeen[$value] = $true
}
$rrfKValues = ($rrfParts -join ",")

Set-EnvFileValue $localEnvPath "AGENTFORGE_KNOWLEDGE_EVALUATION_ENABLED" "true"
Set-EnvFileValue $localEnvPath "AGENTFORGE_KNOWLEDGE_EVALUATION_INDEX" $indexName
Set-EnvFileValue $localEnvPath "AGENTFORGE_KNOWLEDGE_EVALUATION_TOP_K" $topK
Set-EnvFileValue $localEnvPath "AGENTFORGE_KNOWLEDGE_EVALUATION_DATASET" "classpath:knowledge/eval/v070-gold-dataset-v3.json"
Set-EnvFileValue $localEnvPath "AGENTFORGE_KNOWLEDGE_EVALUATION_RRF_K_VALUES" $rrfKValues

Write-Host ""
Write-Host "Knowledge evaluation configuration saved."
Write-Host ("Index: {0}" -f $indexName)
Write-Host ("topK: {0}" -f $topK)
Write-Host ("RRF k sweep: {0}" -f $rrfKValues)
Write-Host "Dataset: classpath:knowledge/eval/v070-gold-dataset-v3.json"
Write-Host "Next: restart .\scripts\run-dashscope.ps1, then run .\scripts\evaluate-rag-v070.ps1"
