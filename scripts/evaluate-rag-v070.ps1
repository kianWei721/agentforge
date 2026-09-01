param(
    [string]$BaseUrl = "http://localhost:8080",
    [ValidateRange(60, 3600)]
    [int]$RequestTimeoutSeconds = 900
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

if ($health.milestone -ne "V0.7-production-knowledge-platform") {
    throw "Expected V0.7 milestone. Actual: $($health.milestone)"
}
if ($health.knowledgeEvaluationEnabled -ne $true -or $health.knowledgeEvaluationConfigured -ne $true) {
    throw "Knowledge evaluation is not enabled/configured. Run .\scripts\configure-knowledge-evaluation.ps1 and restart AgentForge."
}
if ($health.knowledgeEvaluationIndex -eq $health.knowledgeElasticsearchIndex) {
    throw "Evaluation index must not equal the production knowledge index."
}

Write-Host ("Running isolated V0.7 gold evaluation with client timeout {0}s. This can make multiple embedding/rerank/judge requests..." -f $RequestTimeoutSeconds)
$report = Invoke-Utf8JsonPost -Uri ($root + "/api/v1/knowledge/evaluation/run")

if ($report.documentCount -lt 5 -or $report.caseCount -lt 10) {
    throw "Gold dataset is unexpectedly small."
}

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
Write-Host "V0.7 calibration report generated."
Write-Host "Do not set production quality thresholds until this first multi-document baseline has been reviewed."
