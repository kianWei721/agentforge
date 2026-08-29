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

Write-Host "Configure an existing MySQL and Redis for AgentForge V0.3."
Write-Host "Use a dedicated AgentForge database/schema. Do not point this at a production business schema."
Write-Host ""

$mysqlHost = Read-TextValue "MySQL host"
$mysqlPort = Read-TextValue "MySQL port" "3306"
$mysqlDatabase = Read-TextValue "MySQL database" "agentforge"
$mysqlUsername = Read-TextValue "MySQL username"
$mysqlPassword = Read-SecretValue "MySQL password (blank allowed)"

$redisHost = Read-TextValue "Redis host"
$redisPort = Read-TextValue "Redis port" "6379"
$redisUsername = Read-TextValue "Redis username (blank for default user)"
$redisDatabase = Read-TextValue "Redis database index" "0"
$redisPassword = Read-SecretValue "Redis password (blank allowed)"

if ([string]::IsNullOrWhiteSpace($mysqlHost)) { throw "MySQL host must not be blank." }
if ([string]::IsNullOrWhiteSpace($mysqlUsername)) { throw "MySQL username must not be blank." }
if ([string]::IsNullOrWhiteSpace($redisHost)) { throw "Redis host must not be blank." }

Set-EnvFileValue $localEnvPath "MYSQL_HOST" $mysqlHost
Set-EnvFileValue $localEnvPath "MYSQL_PORT" $mysqlPort
Set-EnvFileValue $localEnvPath "MYSQL_DATABASE" $mysqlDatabase
Set-EnvFileValue $localEnvPath "MYSQL_USERNAME" $mysqlUsername
Set-EnvFileValue $localEnvPath "MYSQL_PASSWORD" $mysqlPassword
Set-EnvFileValue $localEnvPath "REDIS_HOST" $redisHost
Set-EnvFileValue $localEnvPath "REDIS_PORT" $redisPort
Set-EnvFileValue $localEnvPath "REDIS_USERNAME" $redisUsername
Set-EnvFileValue $localEnvPath "REDIS_PASSWORD" $redisPassword
Set-EnvFileValue $localEnvPath "REDIS_DATABASE" $redisDatabase

Write-Host ""
Write-Host "Infrastructure configuration saved to ignored .env.local."
Write-Host ("MySQL: {0}:{1}/{2} user={3}" -f $mysqlHost, $mysqlPort, $mysqlDatabase, $mysqlUsername)
$redisUserDisplay = if ([string]::IsNullOrWhiteSpace($redisUsername)) { "default" } else { $redisUsername }
Write-Host ("Redis: {0}:{1} db={2} user={3}" -f $redisHost, $redisPort, $redisDatabase, $redisUserDisplay)
Write-Host "Passwords were not printed."
Write-Host "Next: .\scripts\run-dashscope.ps1"
