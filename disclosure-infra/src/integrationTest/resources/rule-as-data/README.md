# RuleAsDataIT 픽스처 (테스트 전용)

C1 "코드 diff 0" 시나리오용 번들. **정본이 아니다** — `contracts/`에 두지 않는다(확인되지 않은 서식 항목을 정본 번들에 넣지 않는다).

- `templates/STANDARD-v2.bundle.json`: STANDARD-v1 본문 + `TEST_ONLY_FIELD`(required=true) 1개. 가상 항목이다.
- `rules/DISC-TEST-REASON.bundle.json`: DISC-2026-07 본문 + 사유 코드 `TEST_ONLY_REASON` 1개. 가상 코드다.

두 파일 모두 `bundleId = {id}@{SHA-256(JCS(body)) 앞 12자}` 규칙을 따르며 로더가 재계산해 검증한다.
