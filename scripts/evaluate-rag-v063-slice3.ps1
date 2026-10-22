param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$ExpectedTitle = "V0.6 Offline Troubleshooting Acceptance",
    # Stands in for the trusted gateway; must be registered in AGENTFORGE_STAFF_USER_IDS.
    [string]$UserId = "dev-staff"
)

$ErrorActionPreference = "Stop"

# Windows PowerShell 5.1 does not always preload System.Net.Http.
# Load it explicitly before creating HttpClient/StringContent for UTF-8-safe response handling.
Add-Type -AssemblyName System.Net.Http -ErrorAction Stop

$root = $BaseUrl.TrimEnd("/")
$datasetPath = Join-Path $PSScriptRoot "rag-eval-v063-slice3.json"

function Invoke-Utf8JsonPost {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter(Mandatory = $true)][string]$JsonBody
    )

    $client = New-Object System.Net.Http.HttpClient
    $client.DefaultRequestHeaders.Add("X-AgentForge-User-Id", $UserId)
    try {
        $content = New-Object System.Net.Http.StringContent(
            $JsonBody,
            [System.Text.Encoding]::UTF8,
            "application/json"
        )
        $response = $client.PostAsync($Uri, $content).GetAwaiter().GetResult()
        $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        $text = [System.Text.Encoding]::UTF8.GetString($bytes)

        if (-not $response.IsSuccessStatusCode) {
            throw ("HTTP {0} from {1}" -f [int]$response.StatusCode, $Uri)
        }

        return $text | ConvertFrom-Json
    }
    finally {
        $client.Dispose()
    }
}

if (-not (Test-Path $datasetPath)) {
    throw "Evaluation dataset not found: $datasetPath"
}

$health = Invoke-RestMethod -Method GET -Uri ($root + "/api/v1/system/health")

if ($health.knowledgeRetrieverMode -ne "HYBRID_ES_RRF_REWRITE_RERANK_JUDGE_V1") {
    throw "Slice 3 evaluation requires knowledgeRetrieverMode=HYBRID_ES_RRF_REWRITE_RERANK_JUDGE_V1. Actual: $($health.knowledgeRetrieverMode)"
}
if ($health.knowledgeQueryRewriteEnabled -ne $true -or $health.knowledgeQueryRewriteConfigured -ne $true) {
    throw "Knowledge Query Rewrite is not enabled/configured. Run .\scripts\configure-query-rewrite.ps1 and restart AgentForge."
}
if ($health.knowledgeAnswerabilityEnabled -ne $true -or $health.knowledgeAnswerabilityConfigured -ne $true) {
    throw "Answerability Judge must remain enabled for Slice 3 evaluation."
}

$parsedCases = Get-Content -LiteralPath $datasetPath -Raw -Encoding UTF8 | ConvertFrom-Json
# Windows PowerShell 5.1 may return a JSON array as one array object. Re-enumerate it explicitly
# so Count reflects the number of evaluation cases instead of the outer pipeline object.
$cases = @($parsedCases | ForEach-Object { $_ })
if ($cases.Count -lt 10) {
    throw "Expanded evaluation dataset must contain at least 10 cases."
}

$rows = @()
$rewriteRows = @()
$failed = $false

foreach ($case in $cases) {
    $body = @{ query = $case.query; topK = 5 } | ConvertTo-Json
    $result = Invoke-Utf8JsonPost -Uri ($root + "/api/v1/knowledge/search") -JsonBody $body

    $top = $null
    if ($result.count -gt 0) { $top = $result.hits[0] }

    $rewrite = $result.queryRewriteDiagnostics
    $judge = $result.answerabilityDiagnostics
    $firstDecision = $null
    if ($null -ne $judge -and $judge.decisions.Count -gt 0) {
        $firstDecision = $judge.decisions[0]
    }

    $rewriteHealthy = $null -ne $rewrite -and $rewrite.rewriteMode -eq "QWEN3_8_FLASH_QUERY_REWRITE_V1" -and $rewrite.decision -ne "FALLBACK_ORIGINAL" -and -not [string]::IsNullOrWhiteSpace($rewrite.retrievalQuery)

    $tokensPreserved = $true
    if ($rewriteHealthy) {
        foreach ($token in @($case.requiredTokens)) {
            if (-not $rewrite.retrievalQuery.Contains([string]$token)) {
                $tokensPreserved = $false
                break
            }
        }
    }
    else {
        $tokensPreserved = $false
    }

    if ([bool]$case.expectHit) {
        $semanticPass = $result.count -ge 1 -and $top.title -eq $ExpectedTitle -and $result.noAnswer -eq $false -and $null -ne $judge -and $judge.decision -eq "KNOWLEDGE" -and $judge.supportedCount -ge 1
    }
    else {
        $semanticPass = $result.count -eq 0 -and $result.noAnswer -eq $true -and $null -ne $judge -and $judge.decision -eq "NO_ANSWER" -and $judge.supportedCount -eq 0
    }

    $pass = $semanticPass -and $rewriteHealthy -and $tokensPreserved
    if (-not $pass) { $failed = $true }

    $rows += [PSCustomObject]@{
        Case = $case.name
        Category = $case.category
        Pass = $pass
        ExpectHit = [bool]$case.expectHit
        Count = $result.count
        NoAnswer = $result.noAnswer
        RewriteDecision = if ($null -eq $rewrite) { "" } else { $rewrite.decision }
        RewriteReason = if ($null -eq $rewrite) { "" } else { $rewrite.reasonCode }
        TokensPreserved = $tokensPreserved
        JudgeDecision = if ($null -eq $judge) { "" } else { $judge.decision }
        JudgeSupported = if ($null -eq $judge) { $null } else { $judge.supportedCount }
        ReasonCode = if ($null -eq $firstDecision) { "" } else { $firstDecision.reasonCode }
    }

    $rewriteRows += [PSCustomObject]@{
        Case = $case.name
        Original = $case.query
        RetrievalQuery = if ($null -eq $rewrite) { "" } else { $rewrite.retrievalQuery }
    }
}

Write-Host ""
Write-Host "=== Slice 3 Expanded Evaluation ==="
$rows | Format-Table -AutoSize

Write-Host ""
Write-Host "=== Query Rewrite Audit ==="
$rewriteRows | Format-Table -Wrap -AutoSize

$passed = @($rows | Where-Object { $_.Pass }).Count
Write-Host ""
Write-Host ("Passed: {0}/{1}" -f $passed, $rows.Count)

if ($failed) {
    throw "V0.6.3 Slice 3 evaluation failed. Inspect semantic result, queryRewriteDiagnostics, and exact-token preservation. Do not compensate with cosine/rerank thresholds."
}

Write-Host "V0.6.3 Slice 3 query rewrite + expanded evaluation PASSED."
