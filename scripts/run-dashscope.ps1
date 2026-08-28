$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($env:DASHSCOPE_API_KEY)) {
    throw "DASHSCOPE_API_KEY is not set. Set it in this PowerShell session before running."
}

$env:AGENTFORGE_MODEL_PROVIDER = "dashscope"
$env:AGENTFORGE_AGENT_ENABLED = "true"
if ([string]::IsNullOrWhiteSpace($env:DASHSCOPE_MODEL)) {
    $env:DASHSCOPE_MODEL = "qwen-plus"
}

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
