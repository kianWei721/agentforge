$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($env:DASHSCOPE_API_KEY)) {
    throw "DASHSCOPE_API_KEY is not set. Example: `$env:DASHSCOPE_API_KEY='sk-...'"
}

$env:AGENTFORGE_MODEL_PROVIDER = "dashscope"
$env:AGENTFORGE_AGENT_ENABLED = "true"
if ([string]::IsNullOrWhiteSpace($env:DASHSCOPE_MODEL)) {
    $env:DASHSCOPE_MODEL = "qwen-plus"
}

Write-Host "Starting AgentForge with DashScope model $env:DASHSCOPE_MODEL and AgentScope HarnessAgent..."
mvn -pl agentforge-app -am spring-boot:run
