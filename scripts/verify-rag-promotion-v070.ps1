param(
    [string]$BaseUri = "http://localhost:8080",
    [string]$ExpectedDocumentId = "e09e4a8f-0f56-4e17-82d5-576b8362de3f",
    [string]$PositiveQuery = "For a device that has been offline for a prolonged period, what troubleshooting order should I follow?",
    [string]$NoAnswerQuery = "What is the exact reconnect backoff ceiling in seconds?",
    [switch]$SkipKnowledgeSearch,
    [switch]$AllowEvaluationEnabled
)

$ErrorActionPreference = "Stop"
$base = $BaseUri.TrimEnd("/")

function Invoke-JsonPost {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter(Mandatory = $true)]$Body
    )
    $json = $Body | ConvertTo-Json -Depth 8
    return Invoke-RestMethod -Method Post -Uri $Uri -ContentType "application/json; charset=utf-8" -Body ([Text.Encoding]::UTF8.GetBytes($json))
}

$health = Invoke-RestMethod -Method Get -Uri ($base + "/api/v1/system/health")

if ($health.milestone -ne "V0.7-slice1b4-production-promotion") {
    throw "Unexpected milestone: $($health.milestone)"
}
if ($health.status -ne "UP") {
    throw "AgentForge is not UP: $($health.status)"
}
if ($health.knowledgeProductionPromotionState -ne "PROMOTED_ORIGINAL_K5") {
    throw ("Promotion state mismatch. Expected PROMOTED_ORIGINAL_K5, actual {0}" -f $health.knowledgeProductionPromotionState)
}
if ($health.knowledgeProductionQueryStrategy -ne "ORIGINAL") {
    throw "Production query strategy must be ORIGINAL."
}
if ([int]$health.knowledgeProductionRrfK -ne 5) {
    throw "Production RRF k must be 5."
}
if ($health.knowledgeQueryRewriteEnabled) {
    throw "Production Query Rewrite must be disabled."
}
if ($health.knowledgeRetrieverMode -ne "HYBRID_ES_RRF_RERANK_JUDGE_V1") {
    throw ("Unexpected production retriever mode: {0}" -f $health.knowledgeRetrieverMode)
}
if (-not $health.knowledgeRerankEnabled -or -not $health.knowledgeRerankConfigured) {
    throw "Reranker is not ready."
}
if (-not $health.knowledgeAnswerabilityEnabled -or -not $health.knowledgeAnswerabilityConfigured) {
    throw "Answerability Judge is not ready."
}
if (-not $AllowEvaluationEnabled -and $health.knowledgeEvaluationEnabled) {
    throw "Evaluation endpoint is still enabled. Production promotion defaults to disabling the calibration harness."
}

Write-Host "=== Promotion Health Gate ==="
[PSCustomObject]@{
    Status = $health.status
    Milestone = $health.milestone
    PromotionState = $health.knowledgeProductionPromotionState
    QueryStrategy = $health.knowledgeProductionQueryStrategy
    RrfK = $health.knowledgeProductionRrfK
    RetrieverMode = $health.knowledgeRetrieverMode
    RerankerReady = ($health.knowledgeRerankEnabled -and $health.knowledgeRerankConfigured)
    AnswerabilityReady = ($health.knowledgeAnswerabilityEnabled -and $health.knowledgeAnswerabilityConfigured)
    EvaluationEnabled = $health.knowledgeEvaluationEnabled
} | Format-List

if ($SkipKnowledgeSearch) {
    Write-Host "Knowledge smoke tests skipped by request."
    exit 0
}

$searchUri = $base + "/api/v1/knowledge/search"

$positive = Invoke-JsonPost -Uri $searchUri -Body @{
    query = $PositiveQuery
    topK = 5
}
if ($positive.noAnswer -or [int]$positive.count -lt 1) {
    throw "Positive knowledge smoke returned no-answer."
}
if ($positive.retrievalMode -ne "HYBRID_ES_RRF_RERANK_JUDGE_V1") {
    throw ("Positive smoke used unexpected mode: {0}" -f $positive.retrievalMode)
}
if ($positive.queryRewriteDiagnostics.rewriteMode -ne "DISABLED" -or $positive.queryRewriteDiagnostics.changed) {
    throw "Positive smoke indicates Query Rewrite was still active."
}
if (-not [string]::IsNullOrWhiteSpace($ExpectedDocumentId)) {
    $expectedCitationPrefix = "KB:" + $ExpectedDocumentId + "#"
    $matched = @($positive.hits | Where-Object { $_.citationId -like ($expectedCitationPrefix + "*") }).Count -gt 0
    if (-not $matched) {
        throw ("Positive smoke did not return expected citation prefix {0}" -f $expectedCitationPrefix)
    }
}

$negative = Invoke-JsonPost -Uri $searchUri -Body @{
    query = $NoAnswerQuery
    topK = 5
}
if (-not $negative.noAnswer -or [int]$negative.count -ne 0) {
    throw "No-answer smoke unexpectedly returned admitted knowledge."
}
if ($negative.queryRewriteDiagnostics.rewriteMode -ne "DISABLED" -or $negative.queryRewriteDiagnostics.changed) {
    throw "No-answer smoke indicates Query Rewrite was still active."
}

Write-Host ""
Write-Host "=== Production Knowledge Smoke ==="
[PSCustomObject]@{
    PositiveNoAnswer = $positive.noAnswer
    PositiveCount = $positive.count
    PositiveTopCitation = $positive.hits[0].citationId
    PositiveRewriteMode = $positive.queryRewriteDiagnostics.rewriteMode
    NoAnswer = $negative.noAnswer
    NoAnswerCount = $negative.count
    NoAnswerReason = $negative.noAnswerReason
    NoAnswerRewriteMode = $negative.queryRewriteDiagnostics.rewriteMode
} | Format-List

Write-Host "V0.7 Slice 1B-4 production retrieval smoke passed."
