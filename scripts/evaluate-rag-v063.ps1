param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$ExpectedTitle = "V0.6 Offline Troubleshooting Acceptance",
    # Stands in for the trusted gateway; must be registered in AGENTFORGE_STAFF_USER_IDS.
    [string]$UserId = "dev-staff"
)

$ErrorActionPreference = "Stop"

$root = $BaseUrl.TrimEnd("/")

$health = Invoke-RestMethod -Method GET -Uri ($root + "/api/v1/system/health")

$supportedModes = @(
    "HYBRID_ES_RRF_RERANK_JUDGE_V1",
    "HYBRID_ES_RRF_REWRITE_RERANK_JUDGE_V1"
)
if ($supportedModes -notcontains $health.knowledgeRetrieverMode) {
    throw "V0.6.3 answerability evaluation requires a RERANK_JUDGE retrieval mode. Actual: $($health.knowledgeRetrieverMode)"
}
if ($health.knowledgeAnswerabilityEnabled -ne $true -or $health.knowledgeAnswerabilityConfigured -ne $true) {
    throw "V0.6.3 answerability judge is not enabled/configured. Run .\scripts\configure-answerability.ps1 and restart AgentForge."
}

$cases = @(
    [PSCustomObject]@{ Name = "positive-cross-language-offline"; Query = "设备长期离线应该按什么顺序排查？"; ExpectHit = $true },
    [PSCustomObject]@{ Name = "negative-shadow-conflict"; Query = "shadowStatus CONFLICT 的字段语义是什么？"; ExpectHit = $false },
    [PSCustomObject]@{ Name = "negative-signal-strength"; Query = "SignalStrength=0 在这个设备里具体代表什么？"; ExpectHit = $false },
    [PSCustomObject]@{ Name = "negative-peripheral-status"; Query = "PeripheralStatus 各字段的 0/1 具体表示什么？"; ExpectHit = $false }
)

$rows = @()
$failed = $false

foreach ($case in $cases) {
    $body = @{ query = $case.Query; topK = 5 } | ConvertTo-Json
    $result = Invoke-RestMethod -Method POST -Uri ($root + "/api/v1/knowledge/search") -Headers @{ "X-AgentForge-User-Id" = $UserId } -ContentType "application/json; charset=utf-8" -Body $body

    $top = $null
    if ($result.count -gt 0) { $top = $result.hits[0] }

    $rerank = $result.rerankDiagnostics
    $judge = $result.answerabilityDiagnostics
    $firstDecision = $null
    if ($null -ne $judge -and $judge.decisions.Count -gt 0) {
        $firstDecision = $judge.decisions[0]
    }

    if ($case.ExpectHit) {
        $pass = $result.count -ge 1 -and $top.title -eq $ExpectedTitle -and $null -ne $judge -and $judge.decision -eq "KNOWLEDGE" -and $judge.supportedCount -ge 1
    } else {
        $pass = $result.count -eq 0 -and $result.noAnswer -eq $true -and $null -ne $judge -and $judge.decision -eq "NO_ANSWER" -and $judge.supportedCount -eq 0
    }

    if (-not $pass) { $failed = $true }

    $rows += [PSCustomObject]@{
        Case = $case.Name
        Pass = $pass
        Count = $result.count
        NoAnswer = $result.noAnswer
        RerankScore = if ($null -eq $rerank) { $null } else { $rerank.topRealScore }
        JudgeDecision = if ($null -eq $judge) { "" } else { $judge.decision }
        JudgeCandidates = if ($null -eq $judge) { $null } else { $judge.candidateCount }
        JudgeSupported = if ($null -eq $judge) { $null } else { $judge.supportedCount }
        FirstSupported = if ($null -eq $firstDecision) { $null } else { $firstDecision.supported }
        ReasonCode = if ($null -eq $firstDecision) { "" } else { $firstDecision.reasonCode }
        TopTitle = if ($null -eq $top) { "" } else { $top.title }
        MatchType = if ($null -eq $top) { "" } else { $top.matchType }
        VectorSimilarity = if ($null -eq $top) { $null } else { $top.vectorSimilarity }
        RrfScore = if ($null -eq $top) { $null } else { $top.score }
    }
}

$rows | Format-Table -AutoSize

if ($failed) {
    throw "V0.6.3 answerability evaluation failed. Inspect answerabilityDiagnostics (decision/supported/reasonCode). Do not tune cosine or rerank thresholds to compensate for an answerability failure."
}

Write-Host ""
Write-Host "V0.6.3 rerank + answerability evaluation PASSED."
