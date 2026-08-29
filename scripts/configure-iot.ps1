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

Write-Host "Configure AgentForge Real IoT HTTP Device Status Adapter."
Write-Host "Credentials are written only to ignored .env.local and are never printed."
Write-Host ""

$baseUrl = Read-TextValue "IoT backend base URL, for example https://host:48080"
$tenantId = Read-TextValue "IoT backend tenant-id"
$accessToken = Read-SecretValue "IoT backend access token (raw token or Bearer token)"

if ([string]::IsNullOrWhiteSpace($baseUrl)) {
    throw "IoT backend base URL must not be blank."
}
if ($tenantId -notmatch '^[0-9]{1,19}$') {
    throw "tenant-id must be numeric."
}
if ([string]::IsNullOrWhiteSpace($accessToken)) {
    throw "IoT backend access token must not be blank."
}

Set-EnvFileValue $localEnvPath "AGENTFORGE_IOT_DEVICE_STATUS_MODE" "http"
Set-EnvFileValue $localEnvPath "AGENTFORGE_IOT_BASE_URL" $baseUrl.TrimEnd("/")
Set-EnvFileValue $localEnvPath "AGENTFORGE_IOT_ACCESS_TOKEN" $accessToken
Set-EnvFileValue $localEnvPath "AGENTFORGE_IOT_TENANT_ID" $tenantId

Write-Host ""
Write-Host "Real IoT HTTP adapter configuration saved."
Write-Host ("Base URL: {0}" -f $baseUrl.TrimEnd("/"))
Write-Host ("tenant-id: {0}" -f $tenantId)
Write-Host "Access token: SET (not printed)"
Write-Host "Next: .\scripts\run-dashscope.ps1"
