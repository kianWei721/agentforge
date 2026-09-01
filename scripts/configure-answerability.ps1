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

Write-Host "Configure AgentForge V0.6.3 Knowledge Answerability Judge."
Write-Host "By default this reuses DASHSCOPE_API_KEY / DASHSCOPE_BASE_URL from the existing LLM configuration."
Write-Host "No API key is printed or copied by this script."
Write-Host ""

$model = Read-TextValue "Answerability model" "qwen3.8-flash"
$candidateLimit = Read-TextValue "Maximum candidates checked in one answerability request" "8"

if ([string]::IsNullOrWhiteSpace($model)) { throw "Answerability model must not be blank." }
if ($candidateLimit -notmatch "^[0-9]+$") { throw "Candidate limit must be numeric." }
$limit = [int]$candidateLimit
if ($limit -lt 1 -or $limit -gt 20) { throw "Candidate limit must be between 1 and 20." }

Set-EnvFileValue $localEnvPath "AGENTFORGE_KNOWLEDGE_ANSWERABILITY_ENABLED" "true"
Set-EnvFileValue $localEnvPath "AGENTFORGE_KNOWLEDGE_ANSWERABILITY_MODEL" $model
Set-EnvFileValue $localEnvPath "AGENTFORGE_KNOWLEDGE_ANSWERABILITY_CANDIDATE_LIMIT" $candidateLimit

Write-Host ""
Write-Host "Answerability judge configuration saved."
Write-Host ("Model: {0}" -f $model)
Write-Host ("Candidate limit: {0}" -f $candidateLimit)
Write-Host "Credentials: reuse existing DASHSCOPE_* unless AGENTFORGE_KNOWLEDGE_ANSWERABILITY_* overrides are configured."
Write-Host "Next: restart .\scripts\run-dashscope.ps1 and run .\scripts\evaluate-rag-v063.ps1"
