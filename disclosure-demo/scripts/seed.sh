#!/usr/bin/env bash
# 데모 시드(Phase 1~4): 데모 테넌트 2개(DEMO1 대형 GA, DEMO2), 규제 번들 배포, DEMO1 사규 승인, 활성화, 번들 대사,
# 카탈로그 수입(상품군 → 보험사 패널 → 상품, 가상 코드 PG-…), 테넌트 KEK 준비(Phase 8 — 저장소 밖 비밀 디렉터리의 kek/{T}/{T}-KEK-1 + 레지스트리 등록),
# (3A) 가상 고객 파일 등록(customers.json — 개인정보는 파일로만, CLI 인자·환경변수 금지), 데모 확인서 흐름(엔진 스텁 고정표),
# (3B) 데모 확인서 봉인·정정(봉인 산출물은 로컬 SeaweedFS — 버킷이 없으면 Object Lock 활성으로 만든다, 로컬 전용 설정).
# (4) 서명: 3자 터치 서명 완료(DEMO1 A-2), 원격 링크(DEMO1 A-3-REMOTE — 콘솔 통지의 토큰을 받아 고객 본인확인·서명을 이어 실행), 만료 1건
#     (DEMO1 A-5-EXPIRE — --as-of로 기한 뒤를 판정), 종이 스캔 → 관리자 확인 완료(DEMO2 A-4-SCAN — DEMO1은 사규가 PAPER_SCAN을 끈다).
#     본인확인 입력·스트로크·이미지는 파일(허구)로만 넘긴다.
# (5) 일일 앵커(어제 날짜로 첫 날 → A-4-SCAN 완료 → 오늘 둘째 날, 스텁 TSA — 키는 저장소 밖 ~/.ga-disclosure, 신뢰 앵커 인증서만 build/demo로),
#     영수증 내보내기 → verify package(영수증 없음·있음) → verify tenant, 짧은 보존 데모 테넌트 DEMO3(데모 전용 번들 DISC-DEMO-SHORT, 데모 프로파일의
#     시계 오프셋으로 과거에 봉인·무효 → 실제 시계 재적용 → 보류 1건 → 파기 1건·HOLD 1건 → verify tenant).
# 전제: docker compose up -d postgres seaweedfs (PostgreSQL + init-roles.sql, S3 호환 저장소). 값은 전부 예시다(설계서 부록 B·D). 몇 번을 돌려도 결과가 같다(멱등).
# 사용: disclosure-demo/scripts/seed.sh [활성화 기준일, 기본 오늘]
#   GA_SECRETS_DIR(기본 ~/.ga-disclosure/secrets): 저장소 밖 비밀 디렉터리(테넌트 KEK·API 키·데모 OIDC 서명 키·백업 키 — 없는 것만 만든다, 권한 600).
#   Phase 2~7 시절 로컬 볼륨(전역 KEK ~/.ga-disclosure/kek.json으로 감싼 데이터)은 이 판이 풀지 않는다 — 이행 판(1a, 커밋 270e18d)의 seed.sh로
#   한 번 재래핑하거나 볼륨을 새로 만든다(설계서 §9 "2단 업그레이드", docs/operations/keys.md).
set -euo pipefail
cd "$(dirname "$0")/../.."

AS_OF="${1:-$(date +%F)}"
OPERATOR="demo-seed"
export GA_SECRETS_DIR="${GA_SECRETS_DIR:-$HOME/.ga-disclosure/secrets}"
mkdir -p "$GA_SECRETS_DIR" && chmod 700 "$GA_SECRETS_DIR"
CATALOG="disclosure-demo/src/main/resources/demo/catalog"
DEMO="disclosure-demo/src/main/resources"
# 데모 엔진: 프로세스 안 스텁(고정표, 비율에서 계산하지 않는다). 응답은 운영과 같은 계약 스키마·정합성 검증을 거친다.
ENGINE_STUB="--ga.engine.mode=stub --ga.engine.stub-table=$DEMO/demo/engine-table.json"
# 봉인 산출물 버킷 자동 생성은 개발·데모 전용이다(운영 버킷은 인프라가 만든다, 설계서 §9).
LOCAL_BUCKET="--ga.storage.s3.create-bucket=true"
# 스텁 TSA(Phase 5, 승인 Q11): 키는 처음 쓸 때 저장소 밖에 만들고(권한 600), 신뢰 앵커 인증서만 gitignore된 build/demo로 내보낸다.
TSA_TRUST="build/demo/tsa-trust.pem"
TSA_STUB="--ga.tsa.mode=stub --ga.tsa.trust-pem=$TSA_TRUST"
OUT="build/demo/phase5"

cli() {
  ./gradlew -q :disclosure-app:bootRun --args="--spring.profiles.active=cli $*"
}

# 테넌트 KEK(Phase 8): 비밀 파일이 없으면 만들고, CURRENT가 아니면 등록한다(재실행 NOOP)
tenant_kek() {
  cli crypto kek init --tenant "$1" --kek-id "$1-KEK-1" --secrets-dir "$GA_SECRETS_DIR" --if-absent yes
  cli crypto kek register --tenant "$1" --kek-id "$1-KEK-1" --operator "$OPERATOR"
}

# 스키마(Phase 8): 앱은 기동 때 마이그레이션하지 않는다 — 마이그레이터 롤의 db migrate가 먼저(멱등, 앱 컨텍스트 없이). 그 뒤 명령은 스키마 버전 가드를 지난다.
# Phase 7 이전 볼륨은 init-roles.sql의 disclosure_health·disclosure_backup 두 롤과 CONNECT 한 줄을 superuser로 먼저(docs/DEVELOPMENT.md "업그레이드") — 없으면 V22가 멈춘다.
cli db migrate
cli secrets init --secrets-dir "$GA_SECRETS_DIR" --demo yes
cli demo seed --file disclosure-demo/src/main/resources/demo/phase1-seed.json --operator "$OPERATOR"
# 규제 번들은 DEMO1·DEMO2에만 — DEMO3은 데모 전용 GLOBAL 번들 하나(두 GLOBAL 룰이 겹치면 해석이 Ambiguous로 실패한다)
cli rules distribute --bundle rules/DISC-2026-07.bundle.json --tenants DEMO1,DEMO2 --operator "$OPERATOR"
cli rules distribute --bundle rules/DISC-2027-01.bundle.json --tenants DEMO1,DEMO2 --operator "$OPERATOR"
cli rules distribute --bundle templates/STANDARD-v1.bundle.json --tenants DEMO1,DEMO2 --operator "$OPERATOR"
# 서식 v2(Phase 8 — 해약환급예시 표, 상담일 2026-10-01부터): v1은 그 전날로 닫히고, 이미 봉인된 문서는 v1·판 1 그대로
cli rules distribute --bundle templates/STANDARD-v2.bundle.json --tenants DEMO1,DEMO2 --operator "$OPERATOR"
cli rules approve --tenant DEMO1 --rule DEMO1-HOUSE-2026 --operator "$OPERATOR"
cli rules activate --as-of "$AS_OF" --tenants all --operator "$OPERATOR"
cli rules reconcile --tenants DEMO1,DEMO2 --operator "$OPERATOR"

for tenant in DEMO1 DEMO2; do
  cli catalog import --tenant "$tenant" --file "$CATALOG/product-groups.json" --operator "$OPERATOR"
  cli catalog import --tenant "$tenant" --file "$CATALOG/insurer-panel.json" --operator "$OPERATOR"
  cli catalog import --tenant "$tenant" --file "$CATALOG/products.json" --operator "$OPERATOR"
done

for tenant in DEMO1 DEMO2; do
  tenant_kek "$tenant"
done

for tenant in DEMO1 DEMO2; do
  cli customer import --tenant "$tenant" --file "$DEMO/customers.json" --operator "$OPERATOR"
done
# 데모 확인서(부록 A-1·A-2, 3B 봉인·정정): 두 번째 실행은 같은 고객·상담일·상품군이면 NOOP, 봉인·정정도 이미 했으면 NOOP
# (데모 편의 규칙, 운영 동작 아님)
cli demo disclosures --tenant DEMO1 --file "$DEMO/demo/disclosures.json" --operator "$OPERATOR" $ENGINE_STUB $LOCAL_BUCKET

# Phase 4 서명 데모. 두 번째 실행: 완료된 사례는 NOOP, 링크도 다시 보내지 않는다(SIGN LINK 줄이 없으면 고객 경로를 건너뛴다), 만료도 NOOP.
SIGN="$DEMO/demo/sign"
SIGN_OUT="$(cli demo signatures --tenant DEMO1 --file "$DEMO/demo/signatures.json" --operator "$OPERATOR" $LOCAL_BUCKET)"
echo "$SIGN_OUT"
TOKEN="$(printf '%s\n' "$SIGN_OUT" | sed -n 's|^SIGN LINK .*/s#||p' | tail -n 1)"
REMOTE_ID="$(printf '%s\n' "$SIGN_OUT" | sed -n 's/^DEMO_SIGN DEMO1 A-3-REMOTE id=\([0-9a-f-]*\) .*/\1/p' | tail -n 1)"
if [ -n "$TOKEN" ]; then
  cli sign open --token "$TOKEN" --view-file "$SIGN/view.json" $LOCAL_BUCKET
  cli sign verify --token "$TOKEN" --inputs-file "$SIGN/identity-C01.json"
  cli sign capture --token "$TOKEN" --strokes-file "$SIGN/strokes.json" --image-file "$SIGN/signature.png" \
    --device-file "$SIGN/phone-device.json" --ip 203.0.113.10 $LOCAL_BUCKET
  cli sign agent --tenant DEMO1 --id "$REMOTE_ID" --operator demo-agent --strokes-file "$SIGN/strokes.json" \
    --image-file "$SIGN/signature.png" $LOCAL_BUCKET
  cli sign manager --tenant DEMO1 --id "$REMOTE_ID" --operator demo-manager --ack all $LOCAL_BUCKET
fi
# 만료 데모: 판정 시각을 기한 뒤(+30일)로 — 서명을 기다리던 A-5-EXPIRE만 만료된다
cli disclosure expire --tenants DEMO1 --as-of P30D --operator "$OPERATOR"
# Phase 5 첫 날 앵커: 데모 전용 시계 오프셋(-P1D, 데모 프로파일만)으로 "어제"의 시계에서 DEMO1·DEMO2의 앵커를 만든다(5 수용심사 R1 —
# 앵커 날짜 = 생성 시각의 KST 날짜, V12 CHECK. 다른 날짜를 --date로 고정하는 소급은 DATE_NOT_TODAY로 거부된다). 두 번째 실행은 NOOP.
# 이미 오늘 앵커가 있는 볼륨(다른 날 시드)은 CLOCK_BEHIND_LATEST로 거부되고 데모는 오늘 앵커만으로 잇는다.
cli anchor run --tenants DEMO1,DEMO2 --operator "$OPERATOR" $TSA_STUB --spring.profiles.active=cli,demo --ga.demo.clock-offset=-P1D \
  || echo "ANCHOR_RUN on yesterday's clock refused (a later anchor exists — use a fresh volume for the two-day demo)"
# 종이 스캔 데모(DEMO2): 봉인 사례 1건 → 스캔 업로드(각주 번호·해시 접두 대조) → 설계사 → 관리자 확인이 검토를 해소하며 완료
cli demo disclosures --tenant DEMO2 --file "$DEMO/demo/disclosures-demo2.json" --operator "$OPERATOR" $ENGINE_STUB $LOCAL_BUCKET
cli demo signatures --tenant DEMO2 --file "$DEMO/demo/signatures-demo2.json" --operator "$OPERATOR" $LOCAL_BUCKET

# ---------------------------------------------------------------------------------------------- Phase 5
mkdir -p "$OUT"
# 둘째 날 앵커(오늘): A-4-SCAN(DEMO2)의 매니페스트 anchor는 첫 날 앵커이고 오늘 앵커가 덮는다. 두 번째 실행은 NOOP(새 앵커·영수증 0).
cli anchor run --tenants DEMO1,DEMO2 --operator "$OPERATOR" $TSA_STUB
SCAN_ID="$(cli demo disclosures --tenant DEMO2 --file "$DEMO/demo/disclosures-demo2.json" --operator "$OPERATOR" $ENGINE_STUB $LOCAL_BUCKET \
  | sed -nE 's/^DEMO_DISCLOSURE DEMO2 A-4-SCAN .*(id|existing)=([0-9a-f-]+).*/\2/p' | head -n 1)"
cli anchor receipt export --tenant DEMO2 --id "$SCAN_ID" --out "$OUT/A-4-SCAN.receipt.json" --operator demo-auditor $LOCAL_BUCKET
cli artifacts get --tenant DEMO2 --id "$SCAN_ID" --kind EVIDENCE_ZIP --out "$OUT/A-4-SCAN.evidence.zip" --operator demo-auditor $LOCAL_BUCKET
cli verify package --package "$OUT/A-4-SCAN.evidence.zip" --report "$OUT/A-4-SCAN.verify.json"
cli verify package --package "$OUT/A-4-SCAN.evidence.zip" --receipt "$OUT/A-4-SCAN.receipt.json" --tsa-trust "$TSA_TRUST" \
  --report "$OUT/A-4-SCAN.verify-receipt.json"

# 짧은 보존 데모 테넌트 DEMO3(승인 Q5·Q6): 데모 전용 GLOBAL 번들(0년 1일)만 배포한다. 봉인·무효는 데모 프로파일 + 시계 오프셋(-P5D)으로 —
# 실제 시각으로는 지난 잠금 기한이라 저장소가 거부하고 잠금은 보류된다(봉인은 유효, 3B 규약). 이어서 실제 시계의 재적용이
# RETENTION_ALREADY_ELAPSED로 기록하고, 파기 배치가 1건을 파기하고 보류 1건을 건너뛴다. 두 번째 실행은 파기 0.
DEMO_BUNDLES="disclosure-demo/src/main/resources/demo/bundles"
PAST="--spring.profiles.active=cli,demo --ga.demo.clock-offset=-P5D"
cli demo seed --file "$DEMO/demo/phase5-seed.json" --operator "$OPERATOR"
tenant_kek DEMO3
cli rules distribute --bundle "$DEMO_BUNDLES/rules/DISC-DEMO-SHORT.bundle.json" --tenants DEMO3 --operator "$OPERATOR"
cli rules distribute --bundle templates/STANDARD-v1.bundle.json --tenants DEMO3 --operator "$OPERATOR"
cli rules distribute --bundle templates/STANDARD-v2.bundle.json --tenants DEMO3 --operator "$OPERATOR"
cli rules activate --as-of "$AS_OF" --tenants DEMO3 --operator "$OPERATOR"
cli rules reconcile --tenants DEMO3 --bundles-dir "contracts/rules/bundles,$DEMO_BUNDLES" --operator "$OPERATOR"
cli catalog import --tenant DEMO3 --file "$CATALOG/product-groups.json" --operator "$OPERATOR"
cli catalog import --tenant DEMO3 --file "$CATALOG/insurer-panel.json" --operator "$OPERATOR"
cli catalog import --tenant DEMO3 --file "$CATALOG/products.json" --operator "$OPERATOR"
cli customer import --tenant DEMO3 --file "$DEMO/customers.json" --operator "$OPERATOR"
DEMO3_OUT="$(cli demo disclosures --tenant DEMO3 --file "$DEMO/demo/disclosures-demo3.json" --operator "$OPERATOR" $ENGINE_STUB $LOCAL_BUCKET $PAST)"
echo "$DEMO3_OUT"
HOLD_ID="$(printf '%s\n' "$DEMO3_OUT" | sed -nE 's/^DEMO_DISCLOSURE DEMO3 A-7-HOLD .*(id|existing)=([0-9a-f-]+).*/\2/p' | head -n 1)"
cli artifacts reconcile --tenants DEMO3 --operator "$OPERATOR" $LOCAL_BUCKET
cli legal-hold place --tenant DEMO3 --id "$HOLD_ID" --reason-code LITIGATION --if-absent yes --operator demo-compliance $LOCAL_BUCKET
cli retention destroy --tenants DEMO3 --report-dir "$OUT" --operator "$OPERATOR" $LOCAL_BUCKET
cli anchor run --tenants DEMO3 --operator "$OPERATOR" $TSA_STUB
# 전 테넌트 무결성 검증(파기 건은 객체 부재가 정상, 영수증 토큰은 데모 신뢰 앵커로)
cli verify tenant --tenants all --tsa-trust "$TSA_TRUST" --report-dir "$OUT" --operator demo-auditor $LOCAL_BUCKET

# ---------------------------------------------------------------------------------------------- Phase 6B
# (6B 지시문 §9 데모) DEMO1: 청약번호를 둔 B-1-LINK를 과거 시계(-P3D, 데모 프로파일)로 봉인·3자 서명 완료 → 계약 피드 CSV(계약일 어제)가 청약번호로
# 연결해 보존기한을 늘린다 + 미매칭 1건 → 징구율 스냅샷(지난달, 내부 지표 — 규제 정의 없음) → 게이트 ALLOWED(B-1-LINK)·BLOCKED(없는 청약) →
# 봉인 전 초안 B-2-ABANDON 폐기 → 보존 재계산 dry-run. DEMO2: CHAIN_BROKEN — 감사 행 한 줄을 바꿨다가(사고) 되돌리는(백업 복구) 시연 —
# 근거 없는 해소는 거부, 복구 뒤 verify tenant(MATCH) 작업을 근거로 해소.
# 같은 날 두 번째 실행은 전부 NOOP: 확인서·서명은 데모 규칙, CSV 배치는 같은 (출처, 배치 ID) 원장, 스냅샷은 있으면 건너뜀, 폐기는 이미 ABANDONED,
# CHAIN_BROKEN은 해소된 플래그가 있으면 시연을 건너뛴다. 게이트는 질의라 실행마다 판정 감사가 한 줄씩 남는다(상태 변화 없음).
# 번호(증권·청약)는 파일로만 넘긴다 — CSV·게이트 요청은 build/demo/phase6b(gitignore)에 만든다. 변조·복구는 로컬 compose의 superuser psql
# (DEMO_PSQL로 바꿀 수 있다 — 감사 detail에는 개인정보가 없다).
OUT6B="build/demo/phase6b"
mkdir -p "$OUT6B"
PAST3="--spring.profiles.active=cli,demo --ga.demo.clock-offset=-P3D"
TODAY="$(date +%F)"
YESTERDAY="$(date -v-1d +%F 2>/dev/null || date -d yesterday +%F)"
LAST_MONTH="$(date -v-1m +%Y-%m 2>/dev/null || date -d 'last month' +%Y-%m)"
PSQL="${DEMO_PSQL:-docker compose exec -T postgres psql -U postgres -d disclosure}"
RECOMPUTE_RULE="$([[ "$AS_OF" < "2027-01-01" ]] && echo DISC-2026-07 || echo DISC-2027-01)"

B1_OUT="$(cli demo disclosures --tenant DEMO1 --file "$DEMO/demo/disclosures-6b.json" --operator "$OPERATOR" $ENGINE_STUB $LOCAL_BUCKET $PAST3)"
echo "$B1_OUT"
cli demo signatures --tenant DEMO1 --file "$DEMO/demo/signatures-6b.json" --operator "$OPERATOR" $LOCAL_BUCKET $PAST3
C03_REF="$(cli customer import --tenant DEMO1 --file "$DEMO/customers.json" --operator "$OPERATOR" | sed -n 's/^CUSTOMER_IMPORT DEMO1 C03 .* ref=\(.*\)$/\1/p')"

# 계약 피드 CSV: 1행은 B-1-LINK의 청약번호(연결 → 보존 연장), 1행은 없는 청약(미매칭). 배치 ID는 날짜별 — 같은 날 재실행은 원장이 같은 결과.
printf '%s\n' "policyNo,applicationNo,contractDate,insurerCode,customerRef,productKey" \
  "POL-DEMO-6B-0001,APP-DEMO-6B-0001,$YESTERDAY,INS-A,,INS-A:PRD-1001" \
  "POL-DEMO-6B-0002,APP-DEMO-6B-0002,$YESTERDAY,INS-B,," > "$OUT6B/contract-feed.csv"
LINK_OUT="$(cli contract-links import --tenant DEMO1 --file "$OUT6B/contract-feed.csv" --format csv --source DEMO_FEED --batch-id "demo-6b-$TODAY" \
  --operator "$OPERATOR" $LOCAL_BUCKET)"
echo "$LINK_OUT"
LINK_JOB="$(printf '%s\n' "$LINK_OUT" | sed -n 's/^JOB \([0-9a-f-]*\) .*/\1/p' | tail -n 1)"
cli jobs report --tenant DEMO1 --id "$LINK_JOB" --out "$OUT6B/contract-feed.report.json" --operator demo-compliance $LOCAL_BUCKET
echo "CONTRACT_LINK items: $(jq -c '[.items[] | {outcome, retention}]' "$OUT6B/contract-feed.report.json")"

# 징구율 스냅샷(지난달): 이미 있으면 건너뛴다(같은 (달, 룰 버전)은 409 — 재계산 거부)
if cli collection-rates list --tenant DEMO1 --from "$LAST_MONTH" --to "$LAST_MONTH" --operator demo-compliance | grep -q "^COLLECTION_RATE DEMO1 $LAST_MONTH / "; then
  echo "COLLECTION_RATE_SNAPSHOT DEMO1 $LAST_MONTH NOOP (snapshot exists)"
else
  cli collection-rates snapshot --tenant DEMO1 --period "$LAST_MONTH" --operator "$OPERATOR" $LOCAL_BUCKET
fi

# 게이트: 완료된 B-1-LINK의 청약(ALLOWED), 없는 청약(BLOCKED) — 번호는 파일로
jq -n --arg ref "$C03_REF" '{applicationNo: "APP-DEMO-6B-0001", customerRef: $ref}' > "$OUT6B/gate-allowed.json"
jq -n --arg ref "$C03_REF" '{applicationNo: "APP-DEMO-6B-9999", customerRef: $ref}' > "$OUT6B/gate-blocked.json"
cli gate check --tenant DEMO1 --file "$OUT6B/gate-allowed.json" --operator demo-gate
cli gate check --tenant DEMO1 --file "$OUT6B/gate-blocked.json" --operator demo-gate

# 보존 재계산 dry-run(시행 중 GLOBAL 버전 — 쓰기·감사 없음)
cli retention recompute --tenants DEMO1 --rule-version "$RECOMPUTE_RULE" --operator demo-compliance $LOCAL_BUCKET

# CHAIN_BROKEN(DEMO2): 해소된 시연 플래그가 있으면 건너뛴다
if cli flags list --tenant DEMO2 --type CHAIN_BROKEN --status RESOLVED --operator demo-compliance | grep -q '^FLAG DEMO2 '; then
  echo "CHAIN_BROKEN DEMO2 NOOP (the demo incident was resolved in an earlier run)"
else
  SEQ="$($PSQL -tA -c "SELECT min(seq) FROM audit_log WHERE tenant_id = 'DEMO2' AND action = 'RULE_ACTIVATE'")"
  $PSQL -tA -c "SELECT detail::text FROM audit_log WHERE tenant_id = 'DEMO2' AND seq = $SEQ" > "$OUT6B/audit-row.json"
  echo "INCIDENT DEMO2 audit seq=$SEQ altered (superuser, triggers off) — original kept in $OUT6B/audit-row.json"
  $PSQL -q -c "BEGIN; SET LOCAL session_replication_role = replica; UPDATE audit_log SET detail = '{\"tampered\": true}' WHERE tenant_id = 'DEMO2' AND seq = $SEQ; COMMIT;"
  cli verify tenant --tenants DEMO2 --tsa-trust "$TSA_TRUST" --report-dir "$OUT6B" --operator demo-auditor $LOCAL_BUCKET || echo "VERIFY DEMO2 exit=$? (mismatch expected)"
  FLAGS="$(cli flags list --tenant DEMO2 --type CHAIN_BROKEN --status OPEN --operator demo-compliance | sed -n 's/^FLAG DEMO2 \([0-9a-f-]*\) .*/\1/p')"
  for flag in $FLAGS; do
    cli flags resolve --tenant DEMO2 --id "$flag" --code VERIFIED_MATCH --operator demo-compliance || echo "FLAG_RESOLVE refused without a MATCH (expected)"
  done
  # psql 변수는 -c에서 펼쳐지지 않는다 — 표준 입력으로
  printf '%s\n' "BEGIN;" "SET LOCAL session_replication_role = replica;" \
    "UPDATE audit_log SET detail = :'detail'::jsonb WHERE tenant_id = 'DEMO2' AND seq = $SEQ;" "COMMIT;" \
    | $PSQL -q -v detail="$(cat "$OUT6B/audit-row.json")"
  echo "RESTORED DEMO2 audit seq=$SEQ from $OUT6B/audit-row.json (backup)"
  VERIFY_OUT="$(cli verify tenant --tenants DEMO2 --tsa-trust "$TSA_TRUST" --report-dir "$OUT6B" --operator demo-auditor $LOCAL_BUCKET)"
  echo "$VERIFY_OUT"
  VERIFY_JOB="$(printf '%s\n' "$VERIFY_OUT" | sed -n 's/^JOB \([0-9a-f-]*\) SUCCEEDED.*/\1/p' | tail -n 1)"
  for flag in $FLAGS; do
    cli flags resolve --tenant DEMO2 --id "$flag" --code VERIFIED_MATCH --verify-job "$VERIFY_JOB" --operator demo-compliance
  done
fi
