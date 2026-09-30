# Phase 1 수용 심사 결과 (2026-09-29)

## 판정: 수용. PR #1 병합 승인

- 병합은 **merge commit**(squash 금지)으로 — 보고서가 인용하는 커밋 해시가 살아 있어야 한다. 병합 후 `main`에서 `phase-1` 태그가 도달 가능한지 확인.
- 브랜치 이름은 앞으로 `work/phase-N`(태그는 그대로 `phase-N`). `refs/heads/` 지정을 매번 해야 하는 충돌은 이름으로 없앤다.
- 설계서·CLAUDE.md는 이 회신의 §3 "반영 지시"대로 **저장소에서 직접 고친다**(같은 PR). 이후 심사에서도 문서 전체를 다시 배포하지 않고 이 방식으로 간다.

## 1. 인정하는 것

- **`no-docker` 잡을 실제로 성립하는 방식으로 고친 것.** 내 지시문의 컨테이너 방식은 틀렸다 — GitHub가 컨테이너 잡에 호스트 Docker 소켓을 마운트한다는 것을 잡의 첫 점검 단계가 잡아냈고, 그래서 성공 조건은 유지한 채 수단만 바꿨다. 지시문의 오류를 결과로 증명한 뒤 고친 올바른 순서다. Phase 0의 두 공백(C12, Docker 없음 실측)이 이것으로 닫혔다.
- **C1 "코드 diff 0"에서 데이터셋 차이까지 세어 보인 것**(4군데·1개·1개). "소스 diff 0"만 보이면 데이터 diff가 무엇인지 모른다. 둘을 같이 보여야 "이 변경이 정말 데이터만으로 흡수됐다"가 된다.
- **위반 주입 6종**(허용 목록·V4 트리거·리터럴 스캔·감사 락·활성화 순서·번들 대사). 활성화 순서(RETIRED 먼저)에 위반을 넣어 배타 제약이 잡는 것을 본 것은 설계서 §5의 주석이 실제로 작동한다는 증거다.
- **데모 스크립트 2회 실행 → 2회째 전부 no-op, 드리프트 0.** 멱등을 말이 아니라 실행으로 보였다.

## 2. 지시문과 다른 지점에 대한 판정

| # | 항목 | 판정 |
|---|---|---|
| D1 | `no-docker` 잡 방식 변경 | 수용. 지시문 오류. |
| D2 | `TenantDirectoryReader`(전용 롤, `tenant.tenant_id`만) | 수용. Phase 0 심사의 "유일한 예외"는 표현이 틀렸다. 정확한 규약은 **"직접 접근 허용 목록은 닫힌 FQN 열거이고, 각 항목은 테넌트 데이터를 읽지 않으며 전용 롤을 쓴다"** 이다. 현재 1건, Phase 8 헬스 지표로 2건. CLAUDE.md 규칙 5에 이 문장을 추가한다(§3). |
| D3 | 적용 구간이 끝난 APPROVED 룰은 활성화하지 않고 보고만 | 수용. 지시문 문구의 결함. 다만 "보고만"으로 끝내지 말고 `compliance_flag(type=RULE_ACTIVATION_MISSED)`를 올려 Phase 6 준법 콘솔에 보이게 한다. |
| D4 | 승인된 사규 재승인은 no-op | 수용. 감사 행은 `NOOP`로 남기는지 확인(배포 no-op과 같은 규약). |
| D5 | 사규 초안 scope 변경 금지, 사규는 DRAFT로만 생성, 승인 기록 CHECK | 수용. 좁히는 방향. |

## 3. 설계서·CLAUDE.md 반영 지시 (같은 PR에서 직접 수정)

**설계서 v1.5 변경 이력**에 아래를 한 줄로 요약하고 각 절을 고친다.

1. **§6.2 검증 단계(§6 질문 1).** `validations`를 문자열 배열에서 **객체 배열** `[{ "id": "R-MIN-COMPARE", "stages": ["COMPARE", "SEAL"] }, …]`로 바꾼다. 단계 어휘는 코드가 분기하는 닫힌 enum `ValidationStage {COMPARE, GRADE, REASON, SEAL, COMPLETE}`(§6.1 전이와 1:1). 실행기는 `run(stage)`로 그 단계에 나열된 규칙만 그 순서로 실행한다. 기본값 없음 — `stages`가 빈 규칙은 스키마 위반. 부록 D를 갱신: `R-SIGNER-SET`은 `["COMPLETE"]`, `R-RANK-MONOTONIC`·`R-GRADE-*`는 `["GRADE", "SEAL"]`, 나머지는 `["COMPARE", "SEAL"]` 또는 `["REASON", "SEAL"]`. SEAL 단계가 이전 단계 규칙을 다시 도는 것은 의도(방어적 재검증). 스키마 `rule-version.schema.json` 갱신, 번들 `DISC-2026-07`·`DISC-2027-01`을 **제자리 수정**한다 — 번들 형식의 스키마 진화이고 아직 배포된 테넌트가 없기 때문. 이 예외의 조건을 §5 주석에 적는다: "번들 형식 변경은 **첫 운영 배포 전**에만 제자리 수정 가능, 이후는 새 `rule_version_id`". C1 시나리오는 그대로 통과해야 한다.
2. **§6.5 관리자 확인 OFF(§6 질문 2).** 두 개념을 분리한다. `managerConfirmMode`는 **모든 확인서에 붙는 관리자 서명**(signerSet의 MANAGER)만 다스린다. 산출불가·임시등록·검증 오버라이드의 **예외 승인**은 별개의 `review` 기록이며 `managerConfirmMode`와 무관하게 항상 필요하다. 룰 데이터에 `exceptionApproval: { "role": "MANAGER" }`를 추가(GLOBAL 전용, `tenantOverridable` 불가). 따라서 OFF 테넌트에서는 관리자가 문서마다 서명하지는 않지만 예외 건은 승인한다(§7의 `POST /api/v1/reviews/{id}/confirm`이 이 경로). 예외 승인은 `compliance_flag.resolution` + `audit_log`에 남고 서명이 아니다.
3. **§5 주석.** 활성화 배치 문장에 "적용 구간이 이미 끝난 APPROVED 룰은 활성화하지 않고 `RULE_ACTIVATION_MISSED` 플래그"를 추가.
4. **§3.3 / §9 / CLAUDE.md 규칙 5.** D2의 문장으로 교체. 허용 목록의 현재 항목(`TenantDirectoryReader`)과 예정 항목(Phase 8 `DatabaseHealthIndicator`)을 §9에 표로 둔다.
5. **§11 플랫폼 아티팩트(§6 질문 4).** 아래 4절의 결정을 적는다.

## 4. 결정

**질문 1 — 검증 단계는 룰 데이터에 둔다.** §3-1 그대로. 권장안 채택.

**질문 2 — 관리자 확인 OFF일 때 예외 승인.** §3-2 그대로: 서명과 예외 승인을 분리하고 예외 승인은 항상 필요.

**질문 3 — E3 착수.** `phase-E3-지시문.md`를 첨부한다. 엔진 저장소에서 진행하며, Phase 2(카탈로그·고객 참조)와 **병렬** 가능하다. 두 저장소가 공유하는 유일한 접점은 `contracts/api/v1/engine-disclosure.openapi.yaml`이고 `CHECKSUMS`로 일치를 검사한다.

**질문 4 — 플랫폼 아티팩트 배포: GitHub Packages(Maven), 태그 `platform-v*`에서 CI 발행.** 근거: 두 저장소가 같은 계정 아래 있고 CI에는 `GITHUB_TOKEN`이 있다. 단, GitHub Packages는 공개 저장소여도 Maven 소비에 `read:packages` 토큰이 필요하므로 포털 빌드는 **`mavenLocal()`을 먼저** 보고 없으면 GitHub Packages를 본다. 토큰 없는 리뷰어는 `ga-disclosure`를 받아 `./gradlew publishToMavenLocal` 한 번이면 된다 — README에 두 경로를 그대로 적는다. 버전은 `0.1.0`에서 시작해 플랫폼 모듈 변경이 있는 Phase에서만 올린다(SemVer, `platform-core`·`platform-spring`·`platform-canonical` 동일 버전). Maven Central·JitPack·Pages 정적 저장소는 기각(각각 과잉·외부 의존·편법).

**§6의 나머지 4건**은 이 회신에 없다. Phase 2·E3 착수를 막는 것이 있으면 다음 보고 맨 앞에 붙이고, 아니면 Phase 2 보고서 §6에서 다시 올린다.

## 5. 다음

- **Phase 2**(`ga-disclosure`): `phase-02-지시문.md` 첨부. §3의 문서 반영과 D3 플래그를 선행 소과제로 포함.
- **Phase E3**(`ga-commission-engine`): `phase-E3-지시문.md` 첨부.
- 병렬로 갈 경우 두 보고서를 따로 올리되, `engine-disclosure.openapi.yaml`에 손을 댄 쪽이 먼저 보고한다.
