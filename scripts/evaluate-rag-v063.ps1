param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$ExpectedTitle = "V0.6 Offline Troubleshooting Acceptance"
)

$ErrorActionPreference = "Stop"

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
    $result = Invoke-RestMethod -Method POST -Uri ($BaseUrl.TrimEnd("/") + "/api/v1/knowledge/search") -ContentType "application/json; charset=utf-8" -Body $body

    $top = $null
    if ($result.count -gt 0) { $top = $result.hits[0] }

    if ($case.ExpectHit) {
        $pass = $result.count -ge 1 -and $top.title -eq $ExpectedTitle
    } else {
        $pass = $result.count -eq 0 -and $result.noAnswer -eq $true
    }

    if (-not $pass) { $failed = $true }

    $rows += [PSCustomObject]@{
        Case = $case.Name
        Pass = $pass
        Count = $result.count
        NoAnswer = $result.noAnswer
        TopTitle = if ($null -eq $top) { "" } else { $top.title }
        MatchType = if ($null -eq $top) { "" } else { $top.matchType }
        VectorSimilarity = if ($null -eq $top) { $null } else { $top.vectorSimilarity }
        RrfScore = if ($null -eq $top) { $null } else { $top.score }
    }
}

$rows | Format-Table -AutoSize

if ($failed) {
    throw "V0.6.3 retrieval evaluation failed. Inspect vectorSimilarity and tune the relevance policy rather than treating nearest-neighbor results as relevant."
}

Write-Host ""
Write-Host "V0.6.3 relevance-gate evaluation PASSED."
