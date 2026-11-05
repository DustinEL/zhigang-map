param(
    [string]$OutputName = "blind-predictions.json",
    [switch]$Regression
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Net.Http

$root = (Resolve-Path "$PSScriptRoot/..").Path
$baseUri = "http://127.0.0.1:18090"
$manifestPath = "$root/test-data/matching/final-blind-13-intake-v1/intake-manifest.json"
$outputPath = "$root/test-data/matching/final-blind-13-intake-v1/$OutputName"
$manifest = Get-Content -Raw -Encoding UTF8 $manifestPath | ConvertFrom-Json

if (Test-Path $outputPath) {
    throw "Blind prediction report already exists; refusing to overwrite: $outputPath"
}

function Assert-Sha256([string]$path, [string]$expected) {
    $actual = (Get-FileHash $path -Algorithm SHA256).Hash
    if ($actual -ne $expected) {
        throw "SHA-256 mismatch: $path expected=$expected actual=$actual"
    }
}

if (-not $Regression) {
    Assert-Sha256 "$root/backend/target/zhigang-backend-1.0.0.jar" $manifest.artifacts.jarSha256
    Assert-Sha256 "$root/backend/src/main/resources/matching/role-capability-cards-v2.json" $manifest.artifacts.capabilityCardsSha256
    Assert-Sha256 "$root/backend/src/main/java/com/zhigang/platform/career/ResumeEvidenceExtractor.java" $manifest.artifacts.resumeEvidenceExtractorSha256
    Assert-Sha256 "$root/backend/src/main/java/com/zhigang/platform/career/MatchDecisionEngine.java" $manifest.artifacts.matchDecisionEngineSha256
}
foreach ($resume in $manifest.resumes) {
    $filePath = [IO.Path]::GetFullPath((Join-Path $root ([string]$resume.path)))
    if ((Get-Item $filePath).Length -ne [long]$resume.bytes) {
        throw "File length mismatch: $filePath"
    }
    Assert-Sha256 $filePath ([string]$resume.sha256)
}

$handler = New-Object System.Net.Http.HttpClientHandler
$handler.UseCookies = $true
$handler.CookieContainer = New-Object System.Net.CookieContainer
$client = New-Object System.Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromMinutes(2)

function Read-ApiResponse([System.Net.Http.HttpResponseMessage]$response) {
    $content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    if (-not $response.IsSuccessStatusCode) {
        throw "HTTP $([int]$response.StatusCode): $content"
    }
    return ($content | ConvertFrom-Json).data
}

function Invoke-JsonRequest([string]$method, [string]$path, [object]$body) {
    $request = New-Object System.Net.Http.HttpRequestMessage
    $request.Method = New-Object System.Net.Http.HttpMethod($method)
    $request.RequestUri = [Uri]::new($baseUri + $path)
    $json = $body | ConvertTo-Json -Depth 20 -Compress
    $request.Content = New-Object System.Net.Http.StringContent($json, [Text.Encoding]::UTF8, "application/json")
    try {
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        try { return Read-ApiResponse $response } finally { $response.Dispose() }
    } finally {
        $request.Dispose()
    }
}

function Upload-Resume([string]$path, [string]$resumeId) {
    $multipart = New-Object System.Net.Http.MultipartFormDataContent
    $stream = [IO.File]::OpenRead($path)
    $fileContent = New-Object System.Net.Http.StreamContent($stream)
    $fileContent.Headers.ContentType = [Net.Http.Headers.MediaTypeHeaderValue]::Parse(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    $multipart.Add($fileContent, "file", "$resumeId.docx")
    try {
        $response = $client.PostAsync(
            "$baseUri/api/resumes/files?weeklyHours=12&graduationYear=2027", $multipart
        ).GetAwaiter().GetResult()
        try { return Read-ApiResponse $response } finally { $response.Dispose() }
    } finally {
        $multipart.Dispose()
        $stream.Dispose()
    }
}

$roleNames = @{
    2 = "Java Engineer"
    3 = "AI Agent Engineer"
    4 = "AI Backend Engineer"
    5 = "LLMOps Engineer"
    7 = "LLM Application Engineer"
    9 = "AI Data Engineer"
    12 = "AI Security Engineer"
}
$predictions = New-Object System.Collections.Generic.List[object]

try {
    foreach ($resume in $manifest.resumes) {
        $filePath = [IO.Path]::GetFullPath((Join-Path $root ([string]$resume.path)))
        $profile = Upload-Resume $filePath ([string]$resume.id)
        $profile = Invoke-JsonRequest "PUT" "/api/resumes/$($profile.id)/facts" ([ordered]@{ confirmed=$true })
        foreach ($roleId in $manifest.roleIds) {
            $match = Invoke-JsonRequest "POST" "/api/match-runs" ([ordered]@{
                resumeId=$profile.id
                jobRoleId=[int]$roleId
                preferredCity=""
                workMode=""
            })
            $predictions.Add([ordered]@{
                caseId=("FB13-{0}-{1:D2}" -f $resume.id, [int]$roleId)
                resumeId=[string]$resume.id
                roleId=[int]$roleId
                roleName=$roleNames[[int]$roleId]
                applicationState=$match.applicationState
                readiness=$match.readiness
                education=$profile.education
                projectCount=@($profile.projects).Count
                resumeAbilities=$profile.abilities
                hardGates=$match.hardGates
                skillDetails=$match.skillDetails
                dimensions=$match.dimensions
                scoreLimitReason=$match.scoreLimitReason
                scoringPolicyVersion=$match.scoringPolicyVersion
            })
        }
    }
    $stateCounts = $predictions.ToArray() | Group-Object { $_["applicationState"] } | ForEach-Object {
        [ordered]@{ state=$_.Name; count=$_.Count }
    }
    $artifactHashes = if ($Regression) {
        [ordered]@{
            jarSha256=(Get-FileHash "$root/backend/target/zhigang-backend-1.0.0.jar" -Algorithm SHA256).Hash
            capabilityCardsSha256=(Get-FileHash "$root/backend/src/main/resources/matching/role-capability-cards-v2.json" -Algorithm SHA256).Hash
            resumeEvidenceExtractorSha256=(Get-FileHash "$root/backend/src/main/java/com/zhigang/platform/career/ResumeEvidenceExtractor.java" -Algorithm SHA256).Hash
            matchDecisionEngineSha256=(Get-FileHash "$root/backend/src/main/java/com/zhigang/platform/career/MatchDecisionEngine.java" -Algorithm SHA256).Hash
        }
    } else {
        $manifest.artifacts
    }
    $report = [ordered]@{
        generatedAt=[DateTimeOffset]::Now.ToString("o")
        datasetId=$manifest.datasetId
        artifactHashes=$artifactHashes
        resumeCount=$manifest.resumeCount
        caseCount=$predictions.Count
        stateCounts=$stateCounts
        predictions=$predictions.ToArray()
    }
    [IO.File]::WriteAllText(
        $outputPath,
        ($report | ConvertTo-Json -Depth 30),
        [Text.UTF8Encoding]::new($false)
    )
    [pscustomobject]@{
        ResumeCount=$report.resumeCount
        CaseCount=$report.caseCount
        StateCounts=($stateCounts | ForEach-Object { "$($_.state)=$($_.count)" }) -join ", "
        Report=$outputPath
    } | Format-List
} finally {
    $client.Dispose()
    $handler.Dispose()
}
