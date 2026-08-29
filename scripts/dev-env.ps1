$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$localEnvPath = Join-Path $repoRoot ".env.local"

function Import-AgentForgeEnvFile {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path $Path)) {
        return
    }

    foreach ($rawLine in Get-Content -LiteralPath $Path) {
        $line = $rawLine.Trim()
        if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith("#")) {
            continue
        }

        $separator = $line.IndexOf("=")
        if ($separator -lt 1) {
            continue
        }

        $name = $line.Substring(0, $separator).Trim()
        $value = $line.Substring($separator + 1).Trim()

        if ($value.Length -ge 2) {
            $first = $value.Substring(0, 1)
            $last = $value.Substring($value.Length - 1, 1)
            if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) {
                $value = $value.Substring(1, $value.Length - 2)
            }
        }

        Set-Item -Path ("Env:" + $name) -Value $value
    }
}

Import-AgentForgeEnvFile -Path $localEnvPath

$javaHome = $env:AGENTFORGE_JAVA_HOME
if ([string]::IsNullOrWhiteSpace($javaHome)) {
    $javaHome = $env:JAVA_HOME
}

if ([string]::IsNullOrWhiteSpace($javaHome)) {
    throw "Java 17 is not configured. Run .\scripts\setup-dev.ps1 once or set AGENTFORGE_JAVA_HOME in .env.local."
}

$javaExe = Join-Path $javaHome "bin\java.exe"
$javacExe = Join-Path $javaHome "bin\javac.exe"

if (-not (Test-Path $javaExe)) {
    throw "Configured Java home does not contain bin\java.exe: $javaHome"
}
if (-not (Test-Path $javacExe)) {
    throw "Configured Java home is not a full JDK (bin\javac.exe is missing): $javaHome"
}

$env:JAVA_HOME = $javaHome
$javaBin = Join-Path $javaHome "bin"
$pathParts = @(
    $env:Path -split ";" |
        Where-Object {
            -not [string]::IsNullOrWhiteSpace($_) -and
            $_.TrimEnd("\") -ne $javaBin.TrimEnd("\")
        }
)
$env:Path = (@($javaBin) + $pathParts) -join ";"

$javacVersionOutput = (& $javacExe -version | Out-String).Trim()
if ($LASTEXITCODE -ne 0) {
    throw "Unable to execute javac from configured Java home: $javaHome"
}
if ($javacVersionOutput -notmatch '^javac\s+([0-9]+)') {
    throw "Unable to determine Java version from output: $javacVersionOutput"
}

$javaMajor = [int]$Matches[1]
if ($javaMajor -lt 17) {
    throw "AgentForge requires Java 17+. Current configured Java major version: $javaMajor ($javaHome)"
}

if ([string]::IsNullOrWhiteSpace($env:DASHSCOPE_MODEL)) {
    $env:DASHSCOPE_MODEL = "qwen3.8-flash"
}

$keyState = if ([string]::IsNullOrWhiteSpace($env:DASHSCOPE_API_KEY)) { "NOT_SET" } else { "SET" }
$mysqlState = if ([string]::IsNullOrWhiteSpace($env:MYSQL_HOST)) { "NOT_CONFIGURED" } else { "$env:MYSQL_HOST`:$env:MYSQL_PORT/$env:MYSQL_DATABASE" }
$redisState = if ([string]::IsNullOrWhiteSpace($env:REDIS_HOST)) { "NOT_CONFIGURED" } else { "$env:REDIS_HOST`:$env:REDIS_PORT db=$env:REDIS_DATABASE" }

Write-Host "[dev-env] JAVA_HOME=$env:JAVA_HOME"
Write-Host "[dev-env] JAVA_VERSION=$javacVersionOutput"
Write-Host "[dev-env] DASHSCOPE_MODEL=$env:DASHSCOPE_MODEL"
Write-Host "[dev-env] DASHSCOPE_API_KEY=$keyState"
Write-Host "[dev-env] MYSQL=$mysqlState"
Write-Host "[dev-env] REDIS=$redisState"
