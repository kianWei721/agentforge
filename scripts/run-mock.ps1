$ErrorActionPreference = "Stop"

$env:AGENTFORGE_MODEL_PROVIDER = "mock"
$env:AGENTFORGE_AGENT_ENABLED = "false"

Write-Host "Starting AgentForge with Mock provider..."
mvn -pl agentforge-app -am spring-boot:run
