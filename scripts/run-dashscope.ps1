$ErrorActionPreference = "Stop"

& "$PSScriptRoot\dev-env.ps1"
& "$PSScriptRoot\assert-infra-config.ps1"

if ([string]::IsNullOrWhiteSpace($env:DASHSCOPE_API_KEY)) {
    throw "DASHSCOPE_API_KEY is not configured. Run .\scripts\setup-dev.ps1 once."
}

$env:AGENTFORGE_MODEL_PROVIDER = "dashscope"
$env:AGENTFORGE_AGENT_ENABLED = "true"

Write-Host "[1/2] Installing AgentForge modules to the local Maven repository..."
mvn -pl agentforge-app -am install -DskipTests
if ($LASTEXITCODE -ne 0) {
    throw "Maven install failed with exit code $LASTEXITCODE"
}

Write-Host "[2/2] Starting agentforge-app with DashScope model $env:DASHSCOPE_MODEL and AgentScope HarnessAgent..."
mvn -f agentforge-app/pom.xml spring-boot:run
if ($LASTEXITCODE -ne 0) {
    throw "Spring Boot startup failed with exit code $LASTEXITCODE"
}
