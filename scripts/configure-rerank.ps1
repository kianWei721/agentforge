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

function Read-SecretValue {
    param([Parameter(Mandatory = $true)][string]$Prompt)
    $secureValue = Read-Host $Prompt -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureValue)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
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

Write-Host "Configure AgentForge V0.6.3 Knowledge Reranker."
Write-Host "The API key is written only to ignored .env.local and is never printed."
Write-Host ""

$baseUrl = Read-TextValue "Rerank compatible API base URL, ending in /compatible-api/v1"
$model = Read-TextValue "Rerank model" "qwen3-rerank"
$candidateLimit = Read-TextValue "RRF candidates sent to reranker" "8"
$apiKey = Read-SecretValue "Rerank API Key"

if ([string]::IsNullOrWhiteSpace($baseUrl)) { throw "Rerank base URL must not be blank." }
if ([string]::IsNullOrWhiteSpace($apiKey)) { throw "Rerank API Key must not be blank." }
if ($candidateLimit -notmatch "^[0-9]+$") { throw "Candidate limit must be numeric." }
$limit = [int]$candidateLimit
if ($limit -lt 1 -or $limit -gt 100) { throw "Candidate limit must be between 1 and 100." }

Set-EnvFileValue $localEnvPath "AGENTFORGE_KNOWLEDGE_RERANK_ENABLED" "true"
Set-EnvFileValue $localEnvPath "AGENTFORGE_KNOWLEDGE_RERANK_BASE_URL" $baseUrl.TrimEnd("/")
Set-EnvFileValue $localEnvPath "AGENTFORGE_KNOWLEDGE_RERANK_API_KEY" $apiKey
Set-EnvFileValue $localEnvPath "AGENTFORGE_KNOWLEDGE_RERANK_MODEL" $model
Set-EnvFileValue $localEnvPath "AGENTFORGE_KNOWLEDGE_RERANK_CANDIDATE_LIMIT" $candidateLimit

Write-Host ""
Write-Host "Reranker configuration saved."
Write-Host ("Base URL: {0}" -f $baseUrl.TrimEnd("/"))
Write-Host ("Model: {0}" -f $model)
Write-Host ("Candidate limit: {0}" -f $candidateLimit)
Write-Host "API Key: stored in .env.local (not printed)"
Write-Host "Next: restart .\scripts\run-dashscope.ps1, then run .\scripts\evaluate-rag-v063.ps1"
