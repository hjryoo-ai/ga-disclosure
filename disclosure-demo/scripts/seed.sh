#!/usr/bin/env bash
# 데모 시드(Phase 1~2): 데모 테넌트 2개(DEMO1 대형 GA, DEMO2), 규제 번들 배포, DEMO1 사규 승인, 활성화, 번들 대사,
# 카탈로그 수입(상품군 → 보험사 패널 → 상품, 가상 코드 PG-…), 고객 데이터 키 준비(로컬 KEK 파일).
# 전제: docker compose up -d (PostgreSQL + init-roles.sql). 값은 전부 예시다(설계서 부록 B·D). 몇 번을 돌려도 결과가 같다(멱등).
# 사용: disclosure-demo/scripts/seed.sh [활성화 기준일, 기본 오늘]
#   GA_LOCAL_KEK_FILE(기본 ~/.ga-disclosure/kek.json): 저장소 밖 로컬 KEK 파일. 없으면 만든다(권한 600).
set -euo pipefail
cd "$(dirname "$0")/../.."

AS_OF="${1:-$(date +%F)}"
OPERATOR="demo-seed"
export GA_LOCAL_KEK_FILE="${GA_LOCAL_KEK_FILE:-$HOME/.ga-disclosure/kek.json}"
CATALOG="disclosure-demo/src/main/resources/demo/catalog"

cli() {
  ./gradlew -q :disclosure-app:bootRun --args="--spring.profiles.active=cli $*"
}

cli demo seed --file disclosure-demo/src/main/resources/demo/phase1-seed.json --operator "$OPERATOR"
cli rules distribute --bundle rules/DISC-2026-07.bundle.json --tenants all --operator "$OPERATOR"
cli rules distribute --bundle rules/DISC-2027-01.bundle.json --tenants all --operator "$OPERATOR"
cli rules distribute --bundle templates/STANDARD-v1.bundle.json --tenants all --operator "$OPERATOR"
cli rules approve --tenant DEMO1 --rule DEMO1-HOUSE-2026 --operator "$OPERATOR"
cli rules activate --as-of "$AS_OF" --tenants all --operator "$OPERATOR"
cli rules reconcile --tenants all --operator "$OPERATOR"

for tenant in DEMO1 DEMO2; do
  cli catalog import --tenant "$tenant" --file "$CATALOG/product-groups.json" --operator "$OPERATOR"
  cli catalog import --tenant "$tenant" --file "$CATALOG/insurer-panel.json" --operator "$OPERATOR"
  cli catalog import --tenant "$tenant" --file "$CATALOG/products.json" --operator "$OPERATOR"
done

if [ ! -f "$GA_LOCAL_KEK_FILE" ]; then
  cli crypto init-kek --file "$GA_LOCAL_KEK_FILE"
fi
