#!/usr/bin/env bash
# Phase 1 데모 시드: 데모 테넌트 2개(DEMO1 대형 GA, DEMO2), 규제 번들 배포, DEMO1 사규 승인, 활성화, 번들 대사.
# 전제: docker compose up -d (PostgreSQL + init-roles.sql). 값은 전부 예시다(설계서 부록 B·D).
# 사용: disclosure-demo/scripts/seed-phase1.sh [활성화 기준일, 기본 오늘]
set -euo pipefail
cd "$(dirname "$0")/../.."

AS_OF="${1:-$(date +%F)}"
OPERATOR="demo-seed"

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
