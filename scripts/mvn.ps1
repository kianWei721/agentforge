$ErrorActionPreference = "Stop"

& "$PSScriptRoot\dev-env.ps1"

if ($args.Count -eq 0) {
    mvn -v
} else {
    & mvn @args
}

if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
