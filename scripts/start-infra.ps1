$ErrorActionPreference = "Stop"

& "$PSScriptRoot\dev-env.ps1"

$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    Write-Host "Starting AgentForge MySQL and Redis..."
    docker compose up -d --wait mysql redis
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose up failed with exit code $LASTEXITCODE"
    }
    docker compose ps
}
finally {
    Pop-Location
}
