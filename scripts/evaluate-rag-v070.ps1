param(
    [string]$BaseUrl = "http://localhost:8080",
    [ValidateRange(60, 3600)]
    [int]$RequestTimeoutSeconds = 1200
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Net.Http -ErrorAction Stop

$root = $BaseUrl.TrimEnd("/")

function Invoke-Utf8JsonPost {
    param([Parameter(Mandatory = $true)][string]$Uri)
    $client = New-Object System.Net.Http.HttpClient
    $client.Timeout = [TimeSpan]::FromSeconds($RequestTimeoutSeconds)
    try {
        $content = New-Object System.Net.Http.StringContent("{}", [System.Text.Encoding]::UTF8, "application/json")
        try {
            $response = $client.PostAsync($Uri, $content).GetAwaiter().GetResult()
        }
        catch [System.Threading.Tasks.TaskCanceledException] {
            throw ("Evaluation request exceeded client timeout of {0}s. The V0.7 gold run is synchronous and may issue dozens of embedding/rerank/judge requests. Increase -RequestTimeoutSeconds if needed." -f $RequestTimeoutSeconds)
        }
        $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        $text = [System.Text.Encoding]::UTF8.GetString($bytes)
        if (-not $response.IsSuccessStatusCode) {
            throw ("HTTP {0} from {1}: {2}" -f [int]$response.StatusCode, $Uri, $text)
        }
        return $text | ConvertFrom-Json
    }
    finally {
        $client.Dispose()
    }
}

$health = Invoke-RestMethod -Method GET -Uri ($root + "/api/v1/system/health")

if ($health.milestone -ne "V0.7-slice1b3-e2e-confirmation") {
    throw "Expected V0.7 Slice 1B-3 End-to-End Confirmation milestone. Actual: $($health.milestone)"
}
if ($health.knowledgeEvaluationEnabled -ne $true -or $health.knowledgeEvaluationConfigured -ne $true) {
    throw "Knowledge evaluation is not enabled/configured. Run .\scripts\configure-knowledge-evaluation.ps1 and restart AgentForge."
}
if ($health.knowledgeEvaluationIndex -eq $health.knowledgeElasticsearchIndex) {
    throw "Evaluation index must not equal the production knowledge index."
}

Write-Host ("Running isolated V0.7 gold evaluation with client timeout {0}s. This can make multiple embedding/rerank/judge requests..." -f $RequestTimeoutSeconds)
$report = Invoke-Utf8JsonPost -Uri ($root + "/api/v1/knowledge/evaluation/run")

if ($report.documentCount -lt 10 -or $report.caseCount -lt 20) {
    throw "Gold dataset is unexpectedly small."
}
if ($null -eq $report.candidateBenchmark -or $report.candidateBenchmark.variants.Count -lt 20) {
    throw "Candidate-stage benchmark report is missing or incomplete."
}
if ($null -eq $report.channelStrategyConfirmation) {
    throw "Channel strategy confirmation report is missing."
}
if ($null -eq $report.endToEndConfirmation) {
    throw "End-to-end confirmation report is missing."
}
if ($report.documentCount -le $report.candidateBenchmark.candidateWindow) {
    throw "GOLD corpus must be larger than the candidate window."
}
if ($null -eq $report.stageDiagnostics) {
    throw "Retrieval stage diagnostics are missing."
}

Write-Host ""
Write-Host "=== Candidate-stage Benchmark ==="
Write-Host ("CandidateWindow: {0}" -f $report.candidateBenchmark.candidateWindow)
Write-Host ("RRF k sweep: {0}" -f (($report.candidateBenchmark.rrfKValues | ForEach-Object { [string]$_ }) -join ", "))

$candidateRows = @(
    $report.candidateBenchmark.variants | ForEach-Object {
        [PSCustomObject]@{
            Variant = $_.variant
            QueryMode = $_.queryMode
            Strategy = $_.retrievalStrategy
            RrfK = $_.rrfK
            RecallAt1 = [math]::Round([double]$_.recallAt1, 4)
            RecallAt3 = [math]::Round([double]$_.recallAt3, 4)
            RecallAt5 = [math]::Round([double]$_.recallAt5, 4)
            MRR = [math]::Round([double]$_.meanReciprocalRank, 4)
        }
    }
)
$candidateRows | Format-Table -AutoSize

Write-Host ""
Write-Host "=== Channel-specific Query Strategy Matrix ==="
$candidateRows |
Where-Object { $_.Strategy -eq "HYBRID_RRF" } |
Sort-Object QueryMode, RrfK |
Format-Table -AutoSize

Write-Host ""
Write-Host "=== RRF Parameter Confirmation ==="
$confirmation = $report.channelStrategyConfirmation
[PSCustomObject]@{
    SelectionPolicy = $confirmation.selectionPolicy
    DecisionBoundary = $confirmation.decisionBoundary
    ProductionVariant = $confirmation.productionVariant
    ProductionRrfK = $confirmation.productionRrfK
    ProductionOnLeadingFrontier = $confirmation.productionOnLeadingFrontier
    UniqueLeader = $confirmation.uniqueLeader
    LeadingVariant = $confirmation.leadingVariant
    LeadingVariants = ($confirmation.leadingVariants -join ", ")
    LeadingQueryModes = ($confirmation.leadingQueryModes -join ", ")
    LeadingRrfKValues = ($confirmation.leadingRrfKValues -join ", ")
    LeadingRecallAt1 = [math]::Round([double]$confirmation.leadingRecallAt1, 4)
    LeadingRecallAt3 = [math]::Round([double]$confirmation.leadingRecallAt3, 4)
    LeadingRecallAt5 = [math]::Round([double]$confirmation.leadingRecallAt5, 4)
    LeadingMRR = [math]::Round([double]$confirmation.leadingMeanReciprocalRank, 4)
    ProductionRecallAt1 = [math]::Round([double]$confirmation.productionRecallAt1, 4)
    ProductionRecallAt3 = [math]::Round([double]$confirmation.productionRecallAt3, 4)
    ProductionRecallAt5 = [math]::Round([double]$confirmation.productionRecallAt5, 4)
    ProductionMRR = [math]::Round([double]$confirmation.productionMeanReciprocalRank, 4)
    RecallAt1Delta = [math]::Round([double]$confirmation.recallAt1Delta, 4)
    RecallAt3Delta = [math]::Round([double]$confirmation.recallAt3Delta, 4)
    RecallAt5Delta = [math]::Round([double]$confirmation.recallAt5Delta, 4)
    MRRDelta = [math]::Round([double]$confirmation.meanReciprocalRankDelta, 4)
} | Format-List

Write-Host ""
Write-Host "=== End-to-End Production Promotion Confirmation ==="
$e2e = $report.endToEndConfirmation
[PSCustomObject]@{
    DecisionBoundary = $e2e.decisionBoundary
    Decision = $e2e.decision
    PromotionEligible = $e2e.promotionEligible
    CandidateLeaderConfirmed = $e2e.candidateLeaderConfirmed
    CandidateLeaderVariant = $e2e.candidateLeaderVariant
    ChallengerQueryMode = $e2e.challengerQueryMode
    ChallengerRrfK = $e2e.challengerRrfK
    ProductionRrfK = $e2e.productionRrfK
    NewFailureCases = ($e2e.newFailureCases -join ", ")
    ResolvedFailureCases = ($e2e.resolvedFailureCases -join ", ")
    RecallAt1Delta = [math]::Round([double]$e2e.recallAt1Delta, 4)
    RecallAt3Delta = [math]::Round([double]$e2e.recallAt3Delta, 4)
    RecallAt5Delta = [math]::Round([double]$e2e.recallAt5Delta, 4)
    MRRDelta = [math]::Round([double]$e2e.meanReciprocalRankDelta, 4)
    NoAnswerAccuracyDelta = [math]::Round([double]$e2e.noAnswerAccuracyDelta, 4)
    OverallAccuracyDelta = [math]::Round([double]$e2e.overallAccuracyDelta, 4)
} | Format-List

Write-Host ""
Write-Host "=== End-to-End Control vs Challenger ==="
@(
    [PSCustomObject]@{
        Role = "CONTROL"
        Variant = $e2e.control.variant
        Mode = $e2e.control.retrievalMode
        RecallAt1 = [math]::Round([double]$e2e.control.recallAt1, 4)
        RecallAt3 = [math]::Round([double]$e2e.control.recallAt3, 4)
        RecallAt5 = [math]::Round([double]$e2e.control.recallAt5, 4)
        MRR = [math]::Round([double]$e2e.control.meanReciprocalRank, 4)
        NoAnswerAccuracy = [math]::Round([double]$e2e.control.noAnswerAccuracy, 4)
        OverallAccuracy = [math]::Round([double]$e2e.control.overallAccuracy, 4)
    },
    [PSCustomObject]@{
        Role = "CHALLENGER"
        Variant = $e2e.challenger.variant
        Mode = $e2e.challenger.retrievalMode
        RecallAt1 = [math]::Round([double]$e2e.challenger.recallAt1, 4)
        RecallAt3 = [math]::Round([double]$e2e.challenger.recallAt3, 4)
        RecallAt5 = [math]::Round([double]$e2e.challenger.recallAt5, 4)
        MRR = [math]::Round([double]$e2e.challenger.meanReciprocalRank, 4)
        NoAnswerAccuracy = [math]::Round([double]$e2e.challenger.noAnswerAccuracy, 4)
        OverallAccuracy = [math]::Round([double]$e2e.challenger.overallAccuracy, 4)
    }
) | Format-Table -AutoSize

Write-Host ""
Write-Host "=== End-to-End Challenger Case Audit ==="
$e2e.challenger.cases |
Select-Object name, category, passed, noAnswerExpected, noAnswerActual, firstRelevantRank,
    @{Name="Expected";Expression={($_.expectedDocumentIds -join ",")}},
    @{Name="ActualTop";Expression={if ($_.actualDocumentIds.Count -gt 0) { $_.actualDocumentIds[0] } else { "" }}} |
Format-Table -Wrap -AutoSize

Write-Host ""
Write-Host "=== Candidate-stage Non-Rank1 / Miss Audit ==="
$hardRows = @(
    foreach ($variant in $report.candidateBenchmark.variants) {
        foreach ($case in $variant.cases) {
            if ($null -eq $case.firstRelevantRank -or $case.firstRelevantRank -gt 1) {
                [PSCustomObject]@{
                    Variant = $variant.variant
                    Case = $case.name
                    Rank = $case.firstRelevantRank
                    Expected = ($case.expectedDocumentIds -join ",")
                    ActualTop = if ($case.actualDocumentIds.Count -gt 0) { $case.actualDocumentIds[0] } else { "" }
                }
            }
        }
    }
)
if ($hardRows.Count -eq 0) {
    Write-Host "All candidate variants ranked every answerable case at position 1; dataset still has a ceiling effect."
}
else {
    $hardRows | Format-Table -Wrap -AutoSize
}

Write-Host ""
Write-Host "=== Selected Retrieval Stage Diagnostics ==="
Write-Host ("ProductionRrfK: {0}" -f $report.stageDiagnostics.productionRrfK)
Write-Host ("SelectedCases: {0}" -f (($report.stageDiagnostics.selectedCases | ForEach-Object { [string]$_ }) -join ", "))

$report.stageDiagnostics.diagnostics |
Select-Object caseName, queryMode, bm25Rank, vectorRank, rrfRank, rerankRank, finalRank,
    judgeDecision, judgeSupportedExpected,
    @{Name="JudgeReason";Expression={($_.judgeExpectedReasonCodes -join ",")}},
    @{Name="RrfTop";Expression={if ($_.rrfTop5.Count -gt 0) { $_.rrfTop5[0] } else { "" }}},
    @{Name="RerankTop";Expression={if ($_.rerankTop5.Count -gt 0) { $_.rerankTop5[0] } else { "" }}},
    @{Name="FinalTop";Expression={if ($_.finalDocumentIds.Count -gt 0) { $_.finalDocumentIds[0] } else { "" }}} |
Format-Table -Wrap -AutoSize

Write-Host ""
Write-Host "=== Stage Top-K Audit ==="
$report.stageDiagnostics.diagnostics |
Select-Object caseName, queryMode, retrievalQuery,
    @{Name="BM25Top3";Expression={($_.bm25Top3 -join " > ")}},
    @{Name="VectorTop3";Expression={($_.vectorTop3 -join " > ")}},
    @{Name="RRFTop5";Expression={($_.rrfTop5 -join " > ")}},
    @{Name="RerankTop5";Expression={($_.rerankTop5 -join " > ")}} |
Format-Table -Wrap -AutoSize

$variants = @(
    [PSCustomObject]@{
        Variant = $report.baseline.variant
        Mode = $report.baseline.retrievalMode
        RecallAt1 = [math]::Round([double]$report.baseline.recallAt1, 4)
        RecallAt3 = [math]::Round([double]$report.baseline.recallAt3, 4)
        RecallAt5 = [math]::Round([double]$report.baseline.recallAt5, 4)
        MRR = [math]::Round([double]$report.baseline.meanReciprocalRank, 4)
        NoAnswerAccuracy = [math]::Round([double]$report.baseline.noAnswerAccuracy, 4)
        OverallAccuracy = [math]::Round([double]$report.baseline.overallAccuracy, 4)
    },
    [PSCustomObject]@{
        Variant = $report.rewrite.variant
        Mode = $report.rewrite.retrievalMode
        RecallAt1 = [math]::Round([double]$report.rewrite.recallAt1, 4)
        RecallAt3 = [math]::Round([double]$report.rewrite.recallAt3, 4)
        RecallAt5 = [math]::Round([double]$report.rewrite.recallAt5, 4)
        MRR = [math]::Round([double]$report.rewrite.meanReciprocalRank, 4)
        NoAnswerAccuracy = [math]::Round([double]$report.rewrite.noAnswerAccuracy, 4)
        OverallAccuracy = [math]::Round([double]$report.rewrite.overallAccuracy, 4)
    }
)

Write-Host ""
Write-Host "=== V0.7 Gold Dataset Summary ==="
[PSCustomObject]@{
    DatasetVersion = $report.datasetVersion
    EvaluationIndex = $report.evaluationIndex
    Documents = $report.documentCount
    Cases = $report.caseCount
    DurationMs = $report.durationMs
} | Format-List

Write-Host ""
Write-Host "=== Rewrite OFF vs ON ==="
$variants | Format-Table -AutoSize

Write-Host ""
Write-Host "=== Delta (Rewrite - Baseline) ==="
[PSCustomObject]@{
    RecallAt1Delta = [math]::Round([double]$report.recallAt1Delta, 4)
    RecallAt3Delta = [math]::Round([double]$report.recallAt3Delta, 4)
    RecallAt5Delta = [math]::Round([double]$report.recallAt5Delta, 4)
    MRRDelta = [math]::Round([double]$report.meanReciprocalRankDelta, 4)
    NoAnswerAccuracyDelta = [math]::Round([double]$report.noAnswerAccuracyDelta, 4)
} | Format-List

Write-Host ""
Write-Host "=== Rewrite Variant Case Audit ==="
$report.rewrite.cases |
Select-Object name, category, passed, noAnswerExpected, noAnswerActual, firstRelevantRank,
    @{Name="Expected";Expression={($_.expectedDocumentIds -join ",")}},
    @{Name="ActualTop";Expression={if ($_.actualDocumentIds.Count -gt 0) { $_.actualDocumentIds[0] } else { "" }}} |
Format-Table -Wrap -AutoSize

Write-Host ""
Write-Host "V0.7 Slice 1B-3 End-to-End Confirmation report generated."
Write-Host "Candidate-stage selection and end-to-end promotion are separate gates."
Write-Host "PROMOTION_ELIGIBLE means the evaluation gate passed; it never changes production configuration automatically."
Write-Host "Any production change remains manual and must be followed by a runtime smoke/regression check."
