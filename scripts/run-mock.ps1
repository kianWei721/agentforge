$ErrorActionPreference = "Stop"

& "$PSScriptRoot\dev-env.ps1"

$env:AGENTFORGE_MODEL_PROVIDER = "mock"
$env:AGENTFORGE_AGENT_ENABLED = "false"

Write-Host "[1/2] Installing AgentForge modules to the local Maven repository..."
mvn -pl agentforge-app -am install -DskipTests
if ($LASTEXITCODE -ne 0) {
    throw "Maven install failed with exit code $LASTEXITCODE"
}

Write-Host "[2/2] Starting agentforge-app with Mock provider..."
mvn -f agentforge-app/pom.xml spring-boot:run
if ($LASTEXITCODE -ne 0) {
    throw "Spring Boot startup failed with exit code $LASTEXITCODE"
}
