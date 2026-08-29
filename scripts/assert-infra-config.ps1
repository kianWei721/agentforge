$ErrorActionPreference = "Stop"

$required = @(
    "MYSQL_HOST",
    "MYSQL_PORT",
    "MYSQL_DATABASE",
    "MYSQL_USERNAME",
    "REDIS_HOST",
    "REDIS_PORT"
)

$missing = @()
foreach ($name in $required) {
    $value = [Environment]::GetEnvironmentVariable($name)
    if ([string]::IsNullOrWhiteSpace($value)) {
        $missing += $name
    }
}

if ($missing.Count -gt 0) {
    throw ("MySQL/Redis is not configured for AgentForge. Missing: {0}. Run .\scripts\configure-infra.ps1 first." -f ($missing -join ", "))
}
