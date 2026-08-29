$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$localEnvPath = Join-Path $repoRoot ".env.local"

if (Test-Path $localEnvPath) {
    throw ".env.local already exists. Edit or remove it explicitly instead of overwriting local secrets."
}

$defaultJavaHome = if (Test-Path "E:\JDK\bin\java.exe") { "E:\JDK" } elseif ($env:JAVA_HOME) { $env:JAVA_HOME } else { "" }
$javaHomeInput = Read-Host "Java 17 home [$defaultJavaHome]"
$javaHome = if ([string]::IsNullOrWhiteSpace($javaHomeInput)) { $defaultJavaHome } else { $javaHomeInput.Trim() }

if ([string]::IsNullOrWhiteSpace($javaHome) -or -not (Test-Path (Join-Path $javaHome "bin\java.exe"))) {
    throw "Invalid Java home: $javaHome"
}

$modelInput = Read-Host "DashScope model [qwen3.8-flash]"
$model = if ([string]::IsNullOrWhiteSpace($modelInput)) { "qwen3.8-flash" } else { $modelInput.Trim() }

$secureKey = Read-Host "DashScope API key (stored only in ignored .env.local)" -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)
try {
    $apiKey = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
}
finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
}

if ([string]::IsNullOrWhiteSpace($apiKey)) {
    throw "DashScope API key must not be blank."
}

$content = @(
    "# AgentForge local development configuration - DO NOT COMMIT",
    "AGENTFORGE_JAVA_HOME=$javaHome",
    "DASHSCOPE_API_KEY=$apiKey",
    "DASHSCOPE_MODEL=$model"
) -join [Environment]::NewLine

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($localEnvPath, $content + [Environment]::NewLine, $utf8NoBom)

Write-Host "Created $localEnvPath"
Write-Host "Local secrets are ignored by Git."
Write-Host "Next: configure MySQL/Redis with .\scripts\configure-infra.ps1"
