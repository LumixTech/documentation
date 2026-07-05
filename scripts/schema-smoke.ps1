# =====================================================================
# Apicurio Registry smoke test (Windows / PowerShell 7+).
# Dummy bir .proto register eder, geri ceker, dogrular ve temizler.
#
# Kullanim:
#   pwsh scripts/schema-smoke.ps1
#   pwsh scripts/schema-smoke.ps1 -RegistryUrl http://schema.lumix:8080 -Group lumix-events
#
# NOT: Ayni isi Gradle'dan da yapabilirsiniz: ./gradlew schemaSmokeTest
# =====================================================================
param(
  [string]$RegistryUrl = "http://localhost:8080",
  [string]$Group = "default"
)
$ErrorActionPreference = "Stop"

$api = "$($RegistryUrl.TrimEnd('/'))/apis/registry/v3"
$id  = "lumix.smoke.ping.v1"
$proto = "syntax = `"proto3`";`npackage lumix.smoke.v1;`nmessage Ping { string message = 1; }"

$body = @{
  artifactId   = $id
  artifactType = "PROTOBUF"
  firstVersion = @{ content = @{ content = $proto; contentType = "application/x-protobuf" } }
} | ConvertTo-Json -Depth 6

Write-Host ">> Registry: $api  (grup: $Group)"

# 1) Register / yeni versiyon
try {
  Invoke-RestMethod -Method Post -Uri "$api/groups/$Group/artifacts?ifExists=CREATE_VERSION" `
    -ContentType "application/json" -Body $body | Out-Null
  Write-Host ">> register OK"
} catch {
  $resp = $_.Exception.Response
  if ($resp -and [int]$resp.StatusCode -eq 409) { throw "SMOKE HATA: UYUMSUZ (BACKWARD ihlali)" }
  throw "SMOKE HATA: register basarisiz — $($_.Exception.Message)"
}

# 2) Pull (branch=latest icerigi)
$pulled = Invoke-RestMethod -Method Get -Uri "$api/groups/$Group/artifacts/$id/versions/branch=latest/content"
$pulledText = if ($pulled -is [string]) { $pulled } else { $pulled | ConvertTo-Json -Depth 6 }

# 3) Dogrula
if ($pulledText -notmatch "message Ping") {
  throw "SMOKE HATA: cekilen icerik bekleneni icermiyor:`n$pulledText"
}
Write-Host ">> pull OK — icerik dogrulandi"

# 4) Temizlik
try {
  Invoke-RestMethod -Method Delete -Uri "$api/groups/$Group/artifacts/$id" | Out-Null
  Write-Host ">> temizlik OK"
} catch {
  Write-Warning "smoke artifact silinemedi — elle silebilirsiniz."
}

Write-Host "SMOKE OK — register + pull basarili."
