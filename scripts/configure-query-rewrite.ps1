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
    $inputValue = Read-Host ($Prompt + $suffix)
    if ([string]::IsNullOrWhiteSpace($inputValue)) { return $DefaultValue }
    return $inputValue.Trim()
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

Write-Host "Configure AgentForge V0.6.3 Knowledge Query Rewrite."
Write-Host "This defaults to the existing DashScope qwen3.8-flash credentials."
Write-Host "The rewriter is retrieval-only; the original user question is still used by Reranker and Answerability Judge."
Write-Host ""

$model = Read-TextValue "Query rewrite model" "qwen3.8-flash"
$maxQueryChars = Read-TextValue "Maximum query characters" "512"

if ([string]::IsNullOrWhiteSpace($model)) { throw "Query rewrite model must not be blank." }
if ($maxQueryChars -notmatch "^[0-9]+$") { throw "Maximum query characters must be numeric." }
$limit = [int]$maxQueryChars
if ($limit -lt 64 -or $limit -gt 2048) { throw "Maximum query characters must be between 64 and 2048." }

Set-EnvFileValue $localEnvPath "AGENTFORGE_KNOWLEDGE_QUERY_REWRITE_ENABLED" "true"
Set-EnvFileValue $localEnvPath "AGENTFORGE_KNOWLEDGE_QUERY_REWRITE_MODEL" $model
Set-EnvFileValue $localEnvPath "AGENTFORGE_KNOWLEDGE_QUERY_REWRITE_MAX_QUERY_CHARS" $maxQueryChars

Write-Host ""
Write-Host "Query Rewrite configuration saved."
Write-Host ("Model: {0}" -f $model)
Write-Host ("Max query chars: {0}" -f $maxQueryChars)
Write-Host "Credentials: reuse existing DASHSCOPE_* unless query-rewrite-specific overrides are configured."
Write-Host "Next: restart .\scripts\run-dashscope.ps1, then run .\scripts\evaluate-rag-v063-slice3.ps1"
