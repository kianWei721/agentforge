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
    if ([string]::IsNullOrWhiteSpace($inputValue)) {
        return $DefaultValue
    }
    return $inputValue.Trim()
}

function Read-SecretValue {
    param([Parameter(Mandatory = $true)][string]$Prompt)

    $secureValue = Read-Host $Prompt -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureValue)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
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

Write-Host "Configure AgentForge V0.6.2 Hybrid Knowledge Retrieval."
Write-Host "Secrets are written only to ignored .env.local and are never printed."
Write-Host ""

$esBaseUrl = Read-TextValue "Elasticsearch base URL, for example http://localhost:9200"
$indexName = Read-TextValue "Elasticsearch index name" "agentforge-knowledge-v1"
$authMode = (Read-TextValue "Elasticsearch auth mode: none, basic, api-key" "none").ToLowerInvariant()
$embeddingBaseUrl = Read-TextValue "DashScope embedding compatible-mode base URL" "https://dashscope.aliyuncs.com/compatible-mode/v1"
$embeddingModel = Read-TextValue "Embedding model" "text-embedding-v4"
$embeddingDimensions = Read-TextValue "Embedding dimensions" "1024"

if ([string]::IsNullOrWhiteSpace($esBaseUrl)) {
    throw "Elasticsearch base URL must not be blank."
}
if ($indexName -notmatch '^[a-z0-9][a-z0-9._-]{0,254}$') {
    throw "Elasticsearch index name is invalid."
}
if ($embeddingDimensions -notmatch '^[0-9]+$') {
    throw "Embedding dimensions must be numeric."
}

$esUsername = ""
$esPassword = ""
$esApiKey = ""

switch ($authMode) {
    "none" {}
    "basic" {
        $esUsername = Read-TextValue "Elasticsearch username"
        $esPassword = Read-SecretValue "Elasticsearch password"
        if ([string]::IsNullOrWhiteSpace($esUsername)) {
            throw "Elasticsearch username must not be blank for basic auth."
        }
    }
    "api-key" {
        $esApiKey = Read-SecretValue "Elasticsearch API key"
        if ([string]::IsNullOrWhiteSpace($esApiKey)) {
            throw "Elasticsearch API key must not be blank."
        }
    }
    default {
        throw "auth mode must be one of: none, basic, api-key"
    }
}

Set-EnvFileValue $localEnvPath "AGENTFORGE_KNOWLEDGE_HYBRID_ENABLED" "true"
Set-EnvFileValue $localEnvPath "AGENTFORGE_KNOWLEDGE_ES_BASE_URL" $esBaseUrl.TrimEnd("/")
Set-EnvFileValue $localEnvPath "AGENTFORGE_KNOWLEDGE_ES_INDEX" $indexName
Set-EnvFileValue $localEnvPath "AGENTFORGE_KNOWLEDGE_ES_USERNAME" $esUsername
Set-EnvFileValue $localEnvPath "AGENTFORGE_KNOWLEDGE_ES_PASSWORD" $esPassword
Set-EnvFileValue $localEnvPath "AGENTFORGE_KNOWLEDGE_ES_API_KEY" $esApiKey
Set-EnvFileValue $localEnvPath "AGENTFORGE_KNOWLEDGE_EMBEDDING_BASE_URL" $embeddingBaseUrl.TrimEnd("/")
Set-EnvFileValue $localEnvPath "AGENTFORGE_KNOWLEDGE_EMBEDDING_MODEL" $embeddingModel
Set-EnvFileValue $localEnvPath "AGENTFORGE_KNOWLEDGE_EMBEDDING_DIMENSIONS" $embeddingDimensions

Write-Host ""
Write-Host "Hybrid retrieval configuration saved."
Write-Host ("Elasticsearch: {0}" -f $esBaseUrl.TrimEnd("/"))
Write-Host ("Index: {0}" -f $indexName)
Write-Host ("Elasticsearch auth: {0}" -f $authMode)
Write-Host ("Embedding model: {0}, dimensions={1}" -f $embeddingModel, $embeddingDimensions)
Write-Host "Embedding API key: reuses DASHSCOPE_API_KEY unless AGENTFORGE_KNOWLEDGE_EMBEDDING_API_KEY is set."
Write-Host "Next: restart .\scripts\run-dashscope.ps1, then POST /api/v1/knowledge/index/rebuild"
