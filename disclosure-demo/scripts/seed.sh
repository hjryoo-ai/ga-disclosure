#!/usr/bin/env bash
# 데모 시드(Phase 1~4): 데모 테넌트 2개(DEMO1 대형 GA, DEMO2), 규제 번들 배포, DEMO1 사규 승인, 활성화, 번들 대사,
# 카탈로그 수입(상품군 → 보험사 패널 → 상품, 가상 코드 PG-…), 고객 데이터 키 준비(로컬 KEK 파일),
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
#   GA_LOCAL_KEK_FILE(기본 ~/.ga-disclosure/kek.json): 저장소 밖 로컬 KEK 파일. 없으면 만든다(권한 600).
set -euo pipefail
cd "$(dirname "$0")/../.."

AS_OF="${1:-$(date +%F)}"
OPERATOR="demo-seed"
export GA_LOCAL_KEK_FILE="${GA_LOCAL_KEK_FILE:-$HOME/.ga-disclosure/kek.json}"
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

cli demo seed --file disclosure-demo/src/main/resources/demo/phase1-seed.json --operator "$OPERATOR"
# 규제 번들은 DEMO1·DEMO2에만 — DEMO3은 데모 전용 GLOBAL 번들 하나(두 GLOBAL 룰이 겹치면 해석이 Ambiguous로 실패한다)
cli rules distribute --bundle rules/DISC-2026-07.bundle.json --tenants DEMO1,DEMO2 --operator "$OPERATOR"
cli rules distribute --bundle rules/DISC-2027-01.bundle.json --tenants DEMO1,DEMO2 --operator "$OPERATOR"
cli rules distribute --bundle templates/STANDARD-v1.bundle.json --tenants DEMO1,DEMO2 --operator "$OPERATOR"
cli rules approve --tenant DEMO1 --rule DEMO1-HOUSE-2026 --operator "$OPERATOR"
cli rules activate --as-of "$AS_OF" --tenants all --operator "$OPERATOR"
cli rules reconcile --tenants DEMO1,DEMO2 --operator "$OPERATOR"

for tenant in DEMO1 DEMO2; do
  cli catalog import --tenant "$tenant" --file "$CATALOG/product-groups.json" --operator "$OPERATOR"
  cli catalog import --tenant "$tenant" --file "$CATALOG/insurer-panel.json" --operator "$OPERATOR"
  cli catalog import --tenant "$tenant" --file "$CATALOG/products.json" --operator "$OPERATOR"
done

if [ ! -f "$GA_LOCAL_KEK_FILE" ]; then
  cli crypto init-kek --file "$GA_LOCAL_KEK_FILE"
fi

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
TOKEN="$(printf '%s\n' "$SIGN_OUT" | sed -n 's|^SIGN LINK .*/sign/||p' | tail -n 1)"
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
# Phase 5 첫 날 앵커: 어제(KST) 날짜로 DEMO1·DEMO2의 지금 머리를 고정한다("누락된 날을 늦게 채움" — created_at이 실제 시각을 남긴다).
# 이미 그 뒤 날짜의 앵커가 있으면(다른 날 시드한 볼륨) DATE_NOT_AFTER_LATEST로 거부되고 데모는 오늘 앵커만으로 잇는다.
YESTERDAY="$(TZ=Asia/Seoul date -v-1d +%F 2>/dev/null || TZ=Asia/Seoul date -d yesterday +%F)"
cli anchor run --date "$YESTERDAY" --tenants DEMO1,DEMO2 --operator "$OPERATOR" $TSA_STUB \
  || echo "ANCHOR_RUN --date $YESTERDAY refused (a later anchor exists — use a fresh volume for the two-day demo)"
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
cli rules distribute --bundle "$DEMO_BUNDLES/rules/DISC-DEMO-SHORT.bundle.json" --tenants DEMO3 --operator "$OPERATOR"
cli rules distribute --bundle templates/STANDARD-v1.bundle.json --tenants DEMO3 --operator "$OPERATOR"
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
