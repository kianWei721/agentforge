$ErrorActionPreference = "Stop"

& "$PSScriptRoot\dev-env.ps1"

$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    docker compose down
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose down failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}
