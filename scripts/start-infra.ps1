$ErrorActionPreference = "Stop"

& "$PSScriptRoot\dev-env.ps1"

$docker = Get-Command docker -ErrorAction SilentlyContinue
if ($null -eq $docker) {
    throw @"
Docker CLI was not found in PATH.

AgentForge V0.3 local infrastructure requires Docker Desktop with Docker Compose v2.

Check first:
  where.exe docker
  Test-Path "C:\Program Files\Docker\Docker\resources\bin\docker.exe"

If Docker Desktop is not installed, install it and restart IDEA/PowerShell.
If it is already installed, start Docker Desktop and make sure its resources\bin directory is in PATH.
"@
}

$composeVersion = (& docker compose version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0) {
    throw "Docker is installed, but 'docker compose' is unavailable. AgentForge requires Docker Compose v2."
}

Write-Host "[infra] DOCKER=$($docker.Source)"
Write-Host "[infra] COMPOSE=$composeVersion"

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
