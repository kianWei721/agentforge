$ErrorActionPreference = "Stop"

if (-not (Test-Path ".git")) {
    git init -b main
}

$name = git config user.name
$email = git config user.email

if ([string]::IsNullOrWhiteSpace($name) -or [string]::IsNullOrWhiteSpace($email)) {
    Write-Host "Git identity is not configured for this repository."
    Write-Host 'Run: git config user.name "YOUR_NAME"'
    Write-Host 'Run: git config user.email "YOUR_GITHUB_EMAIL"'
    exit 1
}

git add .
git status
Write-Host "`nReady to commit with your own Git identity:"
Write-Host 'git commit -m "chore: initialize AgentForge V0"'
