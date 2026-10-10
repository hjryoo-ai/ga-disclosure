#!/usr/bin/env bash
# HTTP 데모(6A, 계획 §10): seed.sh로 준비한 DEMO1에서 같은 흐름을 HTTP로 — 데모 OIDC 토큰(역할은 identity_link), 설계사 초안 → 봉인,
# 원격 링크 발급 → 스케줄러의 NOTIFY 작업 → 콘솔 통지의 링크 프래그먼트(# 뒤)에서 토큰 → 고객 공개 경로(PDF·열람·본인확인·서명) → 설계사 서명 →
# 관리자 확인(완료) → 피드 2회 읽기·ack → 준법의 VERIFY_TENANT 작업과 보고서 → 거부 3종의 응답 바이트 비교.
# (6B) 설계사가 고객을 HTTP로 등록하고(본문은 허구 파일 그대로 — 응답은 가명·영수증뿐) 그 가명으로 초안을 만든다. 카탈로그 검색 1회.
# 같은 날 두 번째 실행은 같은 멱등 키라 같은 가명(재생)이다. 다른 날의 실행은 키가 달라 새 가명이다 — 같은 사람의 가명 병합은 미결정(설계서 §14 #22).
# 개인정보는 파일로만: 본인확인 생년월일·서명 획·이미지는 데모 파일(허구)을 jq로 읽어 요청 본문(표준 입력)으로 보낸다 — CLI 인자·환경변수에 없다.
# 토큰(JWT·서명 토큰)은 소유자 전용 헤더 파일(build/demo/http, gitignore)로 curl에 넘긴다(프로세스 목록에 남지 않게).
# 몇 번을 돌려도 같다: 쓰기마다 Idempotency-Key = "http-demo-<날짜>-<단계>"라 같은 날 두 번째 실행은 전부 재생(부작용 0)이고 재생 여부를 출력한다.
# 전제: seed.sh를 같은 DB에 한 번 돌렸다(DEMO1·고객·카탈로그·룰). jq·curl 필요. 값은 전부 예시다(설계서 부록 B).
# 사용: disclosure-demo/scripts/http-demo.sh   (HTTP_DEMO_PORT 기본 18080, HTTP_DEMO_MANAGEMENT_PORT 기본 18082, HTTP_DEMO_INTERNAL_PORT 기본 18081, HTTP_DEMO_RUN 기본 오늘 날짜)
#   (GA_DEMO_* 이름은 쓰지 않는다 — Spring이 ga.demo.* 키로 읽어 데모가 아닌 CLI 프로파일에서 DemoKeysGuard가 기동을 멈춘다)
set -euo pipefail
cd "$(dirname "$0")/../.."

for tool in curl jq; do
  command -v "$tool" >/dev/null 2>&1 || { echo "http-demo.sh needs $tool on PATH" >&2; exit 1; }
done

PORT="${HTTP_DEMO_PORT:-18080}"
BASE="http://localhost:$PORT"
# 관리 포트(Phase 8): 헬스는 여기서만 — 앱 포트의 /actuator/**는 404
MGMT_PORT="${HTTP_DEMO_MANAGEMENT_PORT:-18082}"
READY="http://localhost:$MGMT_PORT/actuator/health/readiness"
# 내부 포트(Phase 8 Q5): /internal/**은 여기서만 — 앱 포트의 /internal은 없는 경로와 같은 404
INTERNAL_PORT="${HTTP_DEMO_INTERNAL_PORT:-18081}"
INTERNAL="http://localhost:$INTERNAL_PORT"
RUN="http-demo-${HTTP_DEMO_RUN:-$(date +%F)}"
TENANT="DEMO1"
OPERATOR="http-demo"
DEMO="disclosure-demo/src/main/resources"
SIGN="$DEMO/demo/sign"
OUT="build/demo/http"
export GA_SECRETS_DIR="${GA_SECRETS_DIR:-$HOME/.ga-disclosure/secrets}"
ENGINE_STUB=(--ga.engine.mode=stub "--ga.engine.stub-table=$DEMO/demo/engine-table.json")
LOCAL_BUCKET=(--ga.storage.s3.create-bucket=true)
TSA_STUB=(--ga.tsa.mode=stub --ga.tsa.trust-pem=build/demo/tsa-trust.pem)

mkdir -p "$OUT"
chmod 700 "$OUT"
umask 077

./gradlew -q :disclosure-app:bootJar
JAVA="$(./gradlew -q :disclosure-app:demoJavaLauncher)"
JAR="$(ls disclosure-app/build/libs/disclosure-app-*.jar | grep -v -- '-plain.jar' | head -n 1)"

cli() { "$JAVA" -jar "$JAR" --spring.profiles.active=cli "$@"; }
cli_demo() { "$JAVA" -jar "$JAR" --spring.profiles.active=cli,demo "$@"; }

# ------------------------------------------------------------------------------------------------ 준비
# 스키마(Phase 8): 앱은 기동 때 마이그레이션하지 않는다 — db migrate가 먼저(멱등)
cli db migrate
# 비밀(Phase 8): 없는 것만 만든다 — 앱은 비밀을 만들지 않는다(seed.sh를 먼저 돌렸으면 전부 EXISTS)
cli secrets init --secrets-dir "$GA_SECRETS_DIR" --demo yes
cli demo seed --file "$DEMO/demo/phase6a-seed.json" --operator "$OPERATOR" | grep '^SEED '
SEEDED_REF="$(cli customer import --tenant "$TENANT" --file "$DEMO/customers.json" --operator "$OPERATOR" \
  | sed -n "s/^CUSTOMER_IMPORT $TENANT C01 .* ref=\(.*\)$/\1/p")"
[ -n "$SEEDED_REF" ] || { echo "customer C01 is not registered in $TENANT — run seed.sh first" >&2; exit 1; }

# 토큰은 웹 앱보다 먼저 만든다 — 웹 앱이 기동 때 데모 발급자의 공개키 PEM(build/demo/demo-oidc.pem)을 읽는다
for pair in agent:demo-agent manager:demo-manager compliance:demo-compliance scheduler:demo-scheduler feed:demo-feed; do
  role="${pair%%:*}"
  subject="${pair#*:}"
  jwt="$(cli_demo demo token --tenant "$TENANT" --subject "$subject" --ttl PT30M | grep -E '^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$' | tail -n 1)"
  [ -n "$jwt" ] || { echo "demo token for $subject failed" >&2; exit 1; }
  printf 'Authorization: Bearer %s\n' "$jwt" > "$OUT/auth-$role"
done
echo "TOKENS agent manager compliance scheduler feed (claims: sub, tenant_id, iss, aud, exp — no role claim)"

# ------------------------------------------------------------------------------------------------ 웹 앱
"$JAVA" -jar "$JAR" --spring.profiles.active=demo --server.port="$PORT" --management.server.port="$MGMT_PORT" --ga.internal.port="$INTERNAL_PORT" "${ENGINE_STUB[@]}" "${LOCAL_BUCKET[@]}" "${TSA_STUB[@]}" \
  > "$OUT/server.log" 2>&1 &
SERVER=$!
trap 'kill "$SERVER" 2>/dev/null || true; wait "$SERVER" 2>/dev/null || true' EXIT
for _ in $(seq 1 120); do
  if curl -sf "$READY" >/dev/null 2>&1; then break; fi
  kill -0 "$SERVER" 2>/dev/null || { echo "web app exited — see $OUT/server.log" >&2; exit 1; }
  sleep 1
done
curl -sf "$READY" >/dev/null || { echo "web app did not become ready — see $OUT/server.log" >&2; exit 1; }
echo "WEB UP $BASE (profile demo)"

# call METHOD PATH ROLE KEY BODYFILE — 상태 코드를 출력하고, 본문은 $OUT/last.body, 헤더는 $OUT/last.headers
call() {
  local method="$1" path="$2" role="$3" key="$4" body="$5"
  local args=(-sS -o "$OUT/last.body" -D "$OUT/last.headers" -w '%{http_code}' -X "$method" -H @"$OUT/auth-$role")
  if [ -n "$key" ]; then args+=(-H "Idempotency-Key: $RUN-$key"); fi
  if [ -n "$body" ]; then args+=(-H 'Content-Type: application/json' --data-binary @"$body"); fi
  case "$path" in /internal/*) curl "${args[@]}" "$INTERNAL$path" ;; *) curl "${args[@]}" "$BASE$path" ;; esac
}
replayed() { grep -qi '^idempotency-replayed: true' "$OUT/last.headers" && echo yes || echo no; }
# write METHOD PATH ROLE KEY BODYFILE EXPECTED — 쓰기 한 단계(상태 확인 + 재생 여부 출력)
write() {
  local status
  status="$(call "$1" "$2" "$3" "$4" "$5")"
  if [ "$status" != "$6" ]; then
    echo "STEP $4 expected $6 but got $status: $(cat "$OUT/last.body")" >&2
    exit 1
  fi
  echo "STEP $4 $status replayed=$(replayed)"
}
# body NAME JSON — 리터럴 본문을 파일로(따옴표 중첩 없이 — macOS bash 3.2)
body() { printf '%s' "$2" > "$OUT/$1.json"; }

# ------------------------------------------------------------------------------------------------ 설계사: 고객 등록(6B) → 카탈로그 검색
write POST /api/v1/customers agent register "$DEMO/demo/http-customer.json" 201
CUSTOMER_REF="$(jq -r .customerRef "$OUT/last.body")"
echo "REGISTERED customerRef=$CUSTOMER_REF receiptId=$(jq -r .receiptId "$OUT/last.body") (fields: $(jq -c 'keys' "$OUT/last.body"))"
[ "$(call GET '/api/v1/catalog/products?group=PG-HEALTH-SIMPLE-NR' agent "" "")" = 200 ] || { echo "catalog search failed" >&2; exit 1; }
echo "CATALOG PG-HEALTH-SIMPLE-NR asOf=$(jq -r .asOf "$OUT/last.body") products=$(jq '.items | length' "$OUT/last.body")"

# ------------------------------------------------------------------------------------------------ 설계사: 초안 → 봉인
jq -n --arg ref "$CUSTOMER_REF" --arg date "$(date +%F)" \
  '{customerRef: $ref, groupCode: "PG-HEALTH-SIMPLE-NR", consultDate: $date, templateType: "STANDARD"}' > "$OUT/create.json"
write POST /api/v1/disclosures agent create "$OUT/create.json" 201
ID="$(jq -r .disclosureId "$OUT/last.body")"
body items '{"items":[{"productKey":"INS-A:PRD-1001","recommended":true},{"productKey":"INS-B:PRD-2044"},{"productKey":"INS-C:PRD-3120","recommended":true}]}'
write POST "/api/v1/disclosures/$ID/items" agent items "$OUT/items.json" 200
write POST "/api/v1/disclosures/$ID/compare" agent compare "" 200
write POST "/api/v1/disclosures/$ID/grades" agent grades "" 200
body recommendations '{"reasons":[{"itemNo":1,"codes":["PREMIUM"]},{"itemNo":3,"codes":["COVERAGE"]}]}'
write POST "/api/v1/disclosures/$ID/recommendations" agent recommendations "$OUT/recommendations.json" 200
body validate '{"stage":"SEAL"}'
write POST "/api/v1/disclosures/$ID/validate" agent validate "$OUT/validate.json" 200
write POST "/api/v1/disclosures/$ID/seal" agent seal "" 200
echo "SEALED $ID $(jq -r .disclosureNo "$OUT/last.body")"

# ------------------------------------------------------------------------------------------------ 원격 링크 → NOTIFY 작업 → 토큰
body remote-link '{"channel":"REMOTE_LINK"}'
write POST "/api/v1/disclosures/$ID/sign-sessions" agent remote-link "$OUT/remote-link.json" 201
write POST /internal/v1/jobs/NOTIFY scheduler notify "" 202
NOTIFY_JOB="$(jq -r .jobId "$OUT/last.body")"
for _ in $(seq 1 60); do
  [ "$(call GET "/internal/v1/jobs/$NOTIFY_JOB" scheduler "" "")" = 200 ] || { echo "job read failed" >&2; exit 1; }
  state="$(jq -r .status "$OUT/last.body")"
  case "$state" in SUCCEEDED|FAILED) break ;; esac
  sleep 1
done
echo "JOB NOTIFY $NOTIFY_JOB $state"
SENT_AT="$(date +%s)"
# 콘솔 통지의 링크(`SIGN LINK https://…/s#<토큰>`)에서 프래그먼트만. 두 번째 실행은 새 링크가 없으므로 첫 실행이 남긴 파일을 쓴다(이미 쓰인 토큰).
LINK_TOKEN="$(sed -n 's|^SIGN LINK .*#||p' "$OUT/server.log" | tail -n 1)"
if [ -n "$LINK_TOKEN" ]; then
  printf '%s' "$LINK_TOKEN" > "$OUT/sign-token"
fi
[ -f "$OUT/sign-token" ] || { echo "no SIGN LINK was printed and no earlier token is kept" >&2; exit 1; }
printf 'X-Sign-Token: %s\n' "$(cat "$OUT/sign-token")" > "$OUT/sign-header"

# public PATH BODYFILE — 고객 공개 경로(토큰은 헤더 파일), 상태 코드 출력
public() {
  local args=(-sS -o "$OUT/last.body" -D "$OUT/last.headers" -w '%{http_code}' -X POST -H @"$OUT/sign-header")
  if [ -n "$2" ]; then args+=(-H 'Content-Type: application/json' --data-binary @"$2"); fi
  curl "${args[@]}" "$BASE$1"
}

base64 < "$SIGN/signature.png" | tr -d '\n' > "$OUT/signature.b64"   # 데모 서명 이미지(허구)
expect() { [ "$1" = "$2" ] || { echo "$3 expected $2 but got $1: $(cat "$OUT/last.body")" >&2; exit 1; }; }

expect "$(call GET "/api/v1/disclosures/$ID" agent "" "")" 200 "detail"
if [ "$(jq -r .status "$OUT/last.body")" = "COMPLETED" ]; then
  echo "CUSTOMER PATH skipped — $ID is already COMPLETED (second run)"
else
  # ---------------------------------------------------------------------------------------------- 고객 공개 경로
  expect "$(public /public/v1/sign/open "")" 200 "public open"
  cp "$OUT/last.body" "$OUT/sign.pdf"
  echo "PUBLIC open 200 → $OUT/sign.pdf"
  expect "$(public /public/v1/sign/view "$SIGN/view.json")" 200 "public view"
  echo "PUBLIC view 200"
  jq '{birthDate}' "$SIGN/identity-HTTP.json" > "$OUT/identity.json"   # 파일 → 본문(규칙 6) — HTTP로 등록한 고객
  expect "$(public /public/v1/sign/verify-identity "$OUT/identity.json")" 200 "public verify-identity"
  rm -f "$OUT/identity.json"
  echo "PUBLIC verify-identity 200 $(jq -c . "$OUT/last.body")"
  jq -n --slurpfile strokes "$SIGN/strokes.json" --rawfile image "$OUT/signature.b64" --slurpfile device "$SIGN/phone-device.json" \
    '{strokes: $strokes[0], imagePngBase64: $image, deviceFingerprint: $device[0].fingerprint}' > "$OUT/capture.json"
  # 고객은 문서를 읽고 서명한다 — 발송 뒤 룰 proxySignatureDetection.minSecondsFromSendToSign(60초)보다 빨리 서명하면 대리 서명 탐지가
  # SIGNATURE_DEVICE_REUSE(HIGH)를 올리고 관리자 확인이 그 플래그의 확인을 요구한다(6A HTTP에는 관리자가 플래그 ID를 찾는 경로가 아직 없다 — 보고서 질문)
  wait=$((SENT_AT + 61 - $(date +%s)))
  if [ "$wait" -gt 0 ]; then
    echo "WAIT ${wait}s — the customer reads before signing (rule minSecondsFromSendToSign)"
    sleep "$wait"
  fi
  expect "$(public /public/v1/sign/capture "$OUT/capture.json")" 200 "public capture"
  echo "PUBLIC capture 200 signatureId=$(jq -r .signatureId "$OUT/last.body")"
fi

# ------------------------------------------------------------------------------------------------ 설계사 서명 → 관리자 확인(완료)
jq -n --slurpfile strokes "$SIGN/strokes.json" --rawfile image "$OUT/signature.b64" '{strokes: $strokes[0], imagePngBase64: $image}' \
  > "$OUT/agent-signature.json"
write POST "/api/v1/disclosures/$ID/agent-signature" agent agent-signature "$OUT/agent-signature.json" 200
# (6B) 관리자는 그 확인서의 플래그를 읽고(FLAG_READ — 관리자 ORG) 확인한다. HTTP로 등록한 고객이 seed의 C01과 같은 데모 기기(phone-device.json)로
# 서명하므로 대리 서명 탐지가 SIGNATURE_DEVICE_REUSE를 올린다(의도된 탐지 — 다른 고객, 같은 기기). 6A에는 관리자가 플래그 ID를 찾는 경로가 없었다.
expect "$(call GET "/api/v1/disclosures/$ID/flags" manager "" "")" 200 "flags"
echo "FLAGS $ID $(jq -c '[.items[] | {type, status}]' "$OUT/last.body")"
jq '{acknowledgedFlags: [.items[] | select(.status == "OPEN") | .flagId]}' "$OUT/last.body" > "$OUT/manager-confirmation.json"
write POST "/api/v1/disclosures/$ID/manager-confirmation" manager manager-confirmation "$OUT/manager-confirmation.json" 200
expect "$(call GET "/api/v1/disclosures/$ID" agent "" "")" 200 "detail"
echo "STATUS $ID $(jq -r .status "$OUT/last.body")"

# ------------------------------------------------------------------------------------------------ 피드
[ "$(call GET '/internal/v1/events?limit=1000' feed "" "")" = 200 ]
cp "$OUT/last.body" "$OUT/feed-1.json"
[ "$(call GET '/internal/v1/events?limit=1000' feed "" "")" = 200 ]
cp "$OUT/last.body" "$OUT/feed-2.json"
FIRST="$(jq -c '[.events[].eventId]' "$OUT/feed-1.json")"
SECOND="$(jq -c '[.events[].eventId]' "$OUT/feed-2.json")"
echo "FEED read 1: $(jq '.events | length' "$OUT/feed-1.json") events, nextSeq=$(jq .nextSeq "$OUT/feed-1.json"), headSeq=$(jq .headSeq "$OUT/feed-1.json")"
[ "$FIRST" = "$SECOND" ] && echo "FEED read 2 before ack: same events again (at-least-once)" || { echo "FEED redelivery mismatch" >&2; exit 1; }
UP_TO="$(jq .nextSeq "$OUT/feed-1.json")"
jq -n --argjson upTo "$UP_TO" '{upToSeq: $upTo}' > "$OUT/ack.json"
write POST /internal/v1/events/ack feed "ack-$UP_TO" "$OUT/ack.json" 200
[ "$(call GET '/internal/v1/events?limit=1000' feed "" "")" = 200 ]
echo "FEED after ack: $(jq '.events | length' "$OUT/last.body") events from the ack point $(jq .nextSeq "$OUT/feed-1.json")"

# ------------------------------------------------------------------------------------------------ 준법: VERIFY_TENANT 작업
write POST /api/v1/jobs/VERIFY_TENANT compliance verify-tenant "" 202
VERIFY_JOB="$(jq -r .jobId "$OUT/last.body")"
for _ in $(seq 1 120); do
  [ "$(call GET "/api/v1/jobs/$VERIFY_JOB" compliance "" "")" = 200 ] || { echo "job read failed" >&2; exit 1; }
  state="$(jq -r .status "$OUT/last.body")"
  case "$state" in SUCCEEDED|FAILED) break ;; esac
  sleep 1
done
echo "JOB VERIFY_TENANT $VERIFY_JOB $state"
[ "$state" = SUCCEEDED ] || exit 1
[ "$(call GET "/api/v1/jobs/$VERIFY_JOB/report" compliance "" "")" = 200 ]
cp "$OUT/last.body" "$OUT/verify-report.json"
echo "REPORT $OUT/verify-report.json result=$(jq -r '.result // .conclusion // "see file"' "$OUT/verify-report.json")"

# ------------------------------------------------------------------------------------------------ 거부 3종: 같은 바이트
# reject_capture NAME CURL_ARGS… — 상태 줄·헤더(Date 제외, 소문자 이름 정렬)·본문을 한 파일로
reject_capture() {
  local name="$1"; shift
  curl -sS -o "$OUT/reject-$name.body" -D "$OUT/reject-$name.headers" -X POST "$@"
  { head -n 1 "$OUT/reject-$name.headers" | tr -d '\r'; tail -n +2 "$OUT/reject-$name.headers" | tr -d '\r' | grep -v '^$' \
      | grep -vi '^date:' | awk -F': ' '{print tolower($1) ": " $2}' | sort; cat "$OUT/reject-$name.body"; } > "$OUT/reject-$name.txt"
}
USED="$(cat "$OUT/sign-token")"
WRONG="${USED%?}$([ "${USED: -1}" = A ] && echo B || echo A)"
printf 'X-Sign-Token: %s\n' "$WRONG" > "$OUT/wrong-header"
# 질의 토큰은 일부러 잘못 보내는 요청이다(서버는 원 URI를 로그에 남기지 않는다 — 템플릿만). URL은 curl 설정 파일로(프로세스 목록에 남지 않게).
printf 'url = "%s/public/v1/sign/status?token=%s"\n' "$BASE" "$USED" > "$OUT/query.curlrc"
reject_capture wrong-token -H @"$OUT/wrong-header" "$BASE/public/v1/sign/status"
reject_capture query-token -K "$OUT/query.curlrc"
reject_capture used-token -H @"$OUT/sign-header" "$BASE/public/v1/sign/status"
diff -u "$OUT/reject-wrong-token.txt" "$OUT/reject-query-token.txt"
diff -u "$OUT/reject-wrong-token.txt" "$OUT/reject-used-token.txt"
echo "REJECTIONS wrong-token / query-token / used-token: identical ($(head -n 1 "$OUT/reject-used-token.txt"))"
echo "HTTP DEMO DONE run=$RUN disclosure=$ID"
