[CmdletBinding()]
param(
  [string]$SourceBackup = "database/backups/zhigang_map-before-p0-6-20260805-122838.sql",
  [string]$OutputDirectory = "database/competition"
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$backupRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot "database/backups"))
$competitionRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot "database/competition"))
$backupPath = [System.IO.Path]::GetFullPath((Join-Path $projectRoot $SourceBackup))
$outputPath = [System.IO.Path]::GetFullPath((Join-Path $projectRoot $OutputDirectory))
$manifestPath = Join-Path $outputPath "manifest.json"
$sanitizePath = Join-Path $outputPath "11_competition_sanitize.sql"
$dataPath = Join-Path $outputPath "10_competition_data.sql"

$directorySeparator = [System.IO.Path]::DirectorySeparatorChar
$backupPrefix = $backupRoot.TrimEnd([char[]]@('/', '\')) + $directorySeparator
if (-not $backupPath.StartsWith($backupPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
  throw "The source backup must stay inside database/backups."
}
if (-not $outputPath.Equals($competitionRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
  throw "The output directory must resolve to database/competition."
}
if (-not (Test-Path -LiteralPath $backupPath -PathType Leaf)) {
  throw "Source backup was not found: $backupPath"
}
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
  throw "Competition manifest was not found: $manifestPath"
}
if (-not (Test-Path -LiteralPath $sanitizePath -PathType Leaf)) {
  throw "Competition sanitizer was not found: $sanitizePath"
}
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
  throw "Docker was not found. Install and start Docker Desktop first."
}

$manifest = Get-Content -Raw -Encoding UTF8 -LiteralPath $manifestPath | ConvertFrom-Json
$containerName = "zhigang-competition-export-$PID"
$temporaryPassword = "zhigang_competition_export"
$tables = @(
  "data_source", "dataset_snapshot", "domain_pack", "job_family", "job_level",
  "capability", "skill", "skill_alias", "skill_prerequisite", "job_role",
  "responsibility", "industry_scenario", "graph_release", "release_job_skill",
  "release_job_responsibility", "release_job_scenario", "candidate_skill_registry",
  "crawler_job", "crawler_run", "raw_document", "duplicate_cluster", "duplicate_member",
  "processing_run", "extracted_fact", "fact_evidence", "quality_issue", "dynamic_record",
  "dynamic_fact", "dynamic_quality_issue", "dynamic_review_record",
  "promoted_document_metadata", "promotion_batch", "promotion_item", "projection_status",
  "outbox_event", "change_proposal", "proposal_item", "review_record",
  "emerging_discovery_run", "emerging_cluster", "emerging_cluster_member",
  "emerging_candidate", "emerging_candidate_evidence", "emerging_candidate_review",
  "emerging_candidate_revision", "published_role_document", "emerging_query_discovery",
  "emerging_query_evidence", "emerging_query_field_evidence",
  "published_role_query_evidence", "role_evolution_run", "role_evolution_window",
  "role_evolution_skill_change", "role_evolution_evidence", "role_evolution_revision",
  "published_role_evolution"
)
$manifestTables = @($manifest.includedTables)
$tableDifference = Compare-Object -ReferenceObject ($tables | Sort-Object) `
  -DifferenceObject ($manifestTables | Sort-Object)
if ($tableDifference) {
  throw "The export whitelist does not match manifest.includedTables."
}

function Invoke-ContainerMysql {
  param([Parameter(Mandatory)][string]$Sql)

  $result = docker exec -e "MYSQL_PWD=$temporaryPassword" $containerName mysql "-uroot" `
    "--default-character-set=utf8mb4" "-N" "-B" "zhigang_map" "-e" $Sql
  if ($LASTEXITCODE -ne 0) {
    throw "Temporary database query failed."
  }
  return $result
}

function Assert-Count {
  param(
    [Parameter(Mandatory)][string]$Name,
    [Parameter(Mandatory)][int]$Actual,
    [Parameter(Mandatory)][int]$Expected
  )

  if ($Actual -ne $Expected) {
    throw "$Name does not match the manifest: actual $Actual, expected $Expected."
  }
}

try {
  Write-Host "Restoring the source into an isolated in-memory database..."
  docker run -d --rm --name $containerName `
    --tmpfs "/var/lib/mysql:rw,noexec,nosuid,size=2g" `
    --mount "type=bind,source=$backupPath,target=/docker-entrypoint-initdb.d/00_source.sql,readonly" `
    -e "MYSQL_ROOT_PASSWORD=$temporaryPassword" `
    -e "MYSQL_DATABASE=zhigang_map" `
    "mysql:8.4" `
    --character-set-server=utf8mb4 `
    --collation-server=utf8mb4_0900_ai_ci | Out-Null
  if ($LASTEXITCODE -ne 0) {
    throw "Temporary MySQL container failed to start."
  }

  $ready = $false
  for ($attempt = 0; $attempt -lt 60; $attempt++) {
    try {
      $probe = docker exec -e "MYSQL_PWD=$temporaryPassword" $containerName mysql `
        "-uroot" "-N" "-B" "-e" "SELECT COUNT(*) FROM zhigang_map.raw_document" 2>$null
      if ($LASTEXITCODE -eq 0 -and [int]$probe -gt 0) {
        $ready = $true
        break
      }
    }
    catch {
      # The initialization server can accept pings before the restored database is ready.
    }
    Start-Sleep -Seconds 2
  }
  if (-not $ready) {
    docker logs --tail 80 $containerName
    throw "Temporary MySQL did not become ready before timeout."
  }

  docker cp $sanitizePath "${containerName}:/tmp/11_competition_sanitize.sql" | Out-Null
  docker exec -e "MYSQL_PWD=$temporaryPassword" $containerName sh -c "mysql -uroot --default-character-set=utf8mb4 zhigang_map < /tmp/11_competition_sanitize.sql"
  if ($LASTEXITCODE -ne 0) {
    throw "Competition data sanitization failed."
  }

  $counts = @{}
  $expectedQuery = $manifest.publicBaseline.emergingQuery.query.Replace("'", "''")
  Invoke-ContainerMysql -Sql @"
SELECT 'rawDocumentCount', COUNT(*) FROM raw_document WHERE deleted_at IS NULL;
SELECT 'promotedDynamicRecordCount', COUNT(*) FROM dynamic_record WHERE status = 'PROMOTED';
SELECT 'companyCount', COUNT(DISTINCT company_name) FROM raw_document
WHERE deleted_at IS NULL AND company_name IS NOT NULL AND TRIM(company_name) <> '';
SELECT 'enabledSourceCount', COUNT(*) FROM data_source WHERE enabled = 1;
SELECT 'jobRoleCount', COUNT(*) FROM job_role;
SELECT 'emergingQueryCount', COUNT(*) FROM emerging_query_discovery;
SELECT 'emergingQueryFieldEvidenceCount', COUNT(*)
FROM emerging_query_field_evidence
WHERE discovery_id = (
  SELECT id FROM emerging_query_discovery WHERE query_text = '$expectedQuery' LIMIT 1
);
SELECT 'roleEvolutionChangeCount', COUNT(*) FROM role_evolution_skill_change;
"@ | ForEach-Object {
    $parts = $_ -split "`t", 2
    $counts[$parts[0]] = [int]$parts[1]
  }

  foreach ($property in $manifest.databaseBaseline.PSObject.Properties) {
    Assert-Count -Name $property.Name -Actual $counts[$property.Name] -Expected ([int]$property.Value)
  }

  $invalidQueryCount = [int](Invoke-ContainerMysql -Sql @"
SELECT COUNT(*) FROM emerging_query_discovery
WHERE query_text IS NULL OR TRIM(query_text) = ''
   OR normalized_query IS NULL OR TRIM(normalized_query) = ''
   OR query_text REGEXP '[?]{4,}'
   OR normalized_query REGEXP '[?]{4,}';
"@)
  Assert-Count -Name "invalidEmergingQueryCount" -Actual $invalidQueryCount -Expected 0

  docker exec -e "MYSQL_PWD=$temporaryPassword" $containerName mysqldump "-uroot" `
    "--default-character-set=utf8mb4" "--no-create-info" "--skip-triggers" `
    "--single-transaction" "--hex-blob" "--complete-insert" "--insert-ignore" `
    "--set-gtid-purged=OFF" "--result-file=/tmp/10_competition_data.sql" `
    "zhigang_map" $tables
  if ($LASTEXITCODE -ne 0) {
    throw "Competition whitelist export failed."
  }
  docker cp "${containerName}:/tmp/10_competition_data.sql" $dataPath | Out-Null

  $forbiddenTablePattern = 'INSERT IGNORE INTO `(resume|resume_fact|resume_fact_anchor|resume_parse_run|ability_evidence|verification_submission|verification_evidence_audit|verification_review_record|learning_plan|learning_plan_item|match_run|match_dimension|match_skill_detail)`'
  if (Select-String -Path $dataPath -Pattern $forbiddenTablePattern -Encoding UTF8 -Quiet) {
    throw "The whitelist package contains a forbidden personal-data table."
  }
  $sensitivePatterns = [ordered]@{
    email = '[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}'
    mobile = '(?<![0-9A-F])1[3-9][0-9]{9}(?![0-9A-F])'
    localOperator = '(professional_dev|reviewer_dev)'
  }
  foreach ($entry in $sensitivePatterns.GetEnumerator()) {
    if (Select-String -Path $dataPath -Pattern $entry.Value -Encoding UTF8 -CaseSensitive:$false -Quiet) {
      throw "The whitelist package still contains sensitive data: $($entry.Key)."
    }
  }
  $mojibakeCharacters = [string]::Concat(
    [char]0x00C3, [char]0x00C2, [char]0x00E5, [char]0x00E6,
    [char]0x00E8, [char]0x00E7, [char]0x00E4, [char]0x00E9,
    [char]0xFFFD
  )
  $encodingPattern = "[$([Regex]::Escape($mojibakeCharacters))]|\?{4,}"
  if (Select-String -Path $dataPath -Pattern $encodingPattern -Encoding UTF8 -Quiet) {
    throw "The whitelist package still contains mojibake or question-mark placeholders."
  }

  $sqlText = Get-Content -Raw -Encoding UTF8 -LiteralPath $dataPath
  $actualTables = [regex]::Matches($sqlText, 'INSERT\s+IGNORE INTO `([^`]+)`') |
    ForEach-Object { $_.Groups[1].Value } |
    Sort-Object -Unique
  $unexpectedTables = $actualTables | Where-Object { $_ -notin $manifestTables }
  if ($unexpectedTables) {
    throw "The whitelist package contains unexpected tables: $($unexpectedTables -join ', ')."
  }

  $dataFile = Get-Item -LiteralPath $dataPath
  $dataHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $dataPath).Hash.ToLowerInvariant()
  $sanitizeHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $sanitizePath).Hash.ToLowerInvariant()
  $manifest.generatedAt = (Get-Date).ToUniversalTime().ToString("o")
  $manifest.source.backupFile = Split-Path -Leaf $backupPath
  $manifest | Add-Member -NotePropertyName includedTables -NotePropertyValue $tables -Force
  $manifest.files[0].size = $dataFile.Length
  $manifest.files[0].sha256 = $dataHash
  $manifest.files[1].sha256 = $sanitizeHash

  $utf8WithoutBom = [System.Text.UTF8Encoding]::new($false)
  $manifestJson = $manifest | ConvertTo-Json -Depth 12
  [System.IO.File]::WriteAllText($manifestPath, "$manifestJson`n", $utf8WithoutBom)

  Write-Host "Competition whitelist package generated."
  Write-Host "File: $dataPath"
  Write-Host "SHA-256: $dataHash"
}
finally {
  $existing = docker ps -a --filter "name=^/${containerName}$" --format "{{.Names}}" 2>$null
  if ($existing -eq $containerName) {
    docker rm -f $containerName | Out-Null
  }
}
