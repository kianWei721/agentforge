$ErrorActionPreference = "Stop"

Write-Host "== Java =="
java -version

Write-Host "`n== Maven =="
mvn -v

Write-Host "`n== Git =="
git --version

$javaText = (& java -version 2>&1 | Out-String)
if ($javaText -notmatch 'version "(17|18|19|20|21|22|23|24|25|26)') {
    Write-Warning "AgentForge requires JDK 17+. Please switch JAVA_HOME before building."
}
