#!/usr/bin/env bash
# =====================================================================
# Apicurio Registry smoke test — kurulumu uctan uca dogrular.
# Dummy bir .proto register eder, geri ceker, dogrular ve temizler.
# curl disinda bagimliligi yok (sunucuda calisir).
#
# Kullanim:
#   scripts/schema-smoke.sh [REGISTRY_URL] [GROUP]
#   scripts/schema-smoke.sh                          # http://localhost:8080, grup: default
#   scripts/schema-smoke.sh http://schema.lumix:8080 lumix-events
#
# NOT: Ayni isi Gradle'dan da yapabilirsiniz: ./gradlew schemaSmokeTest
# =====================================================================
set -uo pipefail

REGISTRY_URL="${1:-http://localhost:8080}"
GROUP="${2:-default}"
REGISTRY_URL="${REGISTRY_URL%/}"
API="${REGISTRY_URL}/apis/registry/v3"
ID="lumix.smoke.ping.v1"

# JSON string icine gomulecek proto (satirlar \n ile). contentType: protobuf sema metni.
PROTO='syntax = \"proto3\";\npackage lumix.smoke.v1;\nmessage Ping { string message = 1; }'
BODY="{\"artifactId\":\"${ID}\",\"artifactType\":\"PROTOBUF\",\"firstVersion\":{\"content\":{\"content\":\"${PROTO}\",\"contentType\":\"application/x-protobuf\"}}}"

fail() { echo "SMOKE HATA: $*" >&2; exit 1; }

echo ">> Registry: ${API}  (grup: ${GROUP})"

# 1) Register / yeni versiyon (ifExists=CREATE_VERSION)
code=$(curl -sS -o /tmp/smoke_reg.out -w '%{http_code}' \
  -X POST "${API}/groups/${GROUP}/artifacts?ifExists=CREATE_VERSION" \
  -H 'Content-Type: application/json' -d "${BODY}") \
  || fail "register istegi basarisiz (registry ayakta mi?)"
case "$code" in
  200|201|204) echo ">> register OK ($code)";;
  409) fail "UYUMSUZ (BACKWARD ihlali) — $(cat /tmp/smoke_reg.out)";;
  *) fail "register HTTP $code — $(cat /tmp/smoke_reg.out)";;
esac

# 2) Pull (branch=latest icerigi)
code=$(curl -sS -o /tmp/smoke_pull.out -w '%{http_code}' \
  "${API}/groups/${GROUP}/artifacts/${ID}/versions/branch=latest/content") \
  || fail "pull istegi basarisiz"
[ "$code" = "200" ] || fail "pull HTTP $code — $(cat /tmp/smoke_pull.out)"

# 3) Dogrula
if ! grep -q "message Ping" /tmp/smoke_pull.out; then
  fail "cekilen icerik bekleneni icermiyor:\n$(cat /tmp/smoke_pull.out)"
fi
echo ">> pull OK — icerik dogrulandi"

# 4) Temizlik (artifact sil; compose'da silme acik)
code=$(curl -sS -o /dev/null -w '%{http_code}' -X DELETE "${API}/groups/${GROUP}/artifacts/${ID}")
case "$code" in
  200|204|404) echo ">> temizlik OK";;
  *) echo ">> UYARI: smoke artifact silinemedi (HTTP $code). Elle silebilirsiniz." >&2;;
esac

rm -f /tmp/smoke_reg.out /tmp/smoke_pull.out
echo "SMOKE OK — register + pull basarili."
