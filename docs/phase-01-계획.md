# Phase 1 계획 — 승인 기록 (2026-09-29)

Phase 1 지시문에 대한 사전 보고와 승인 결과. 사용자 회신: "권장승인"(아래 권장안 전부 승인), 원격 저장소는 public(포트폴리오), 엔진 저장소는 로컬 `ai-comm/commission-system`(Phase E3는 별도).

## 설계서 모순·빈칸 (승인된 처리)

| # | 발견 | 처리 |
|---|---|---|
| D1 | 설계서 v1.4 §5 `tenant.params DEFAULT '{}'` ↔ V1·v1.3 `'{"gateRequiresManager": true}'` | 설계서를 V1에 맞춤(v1.5) |
| D2 | `form_template`에 `pendingConfirmation` 저장 자리 없음 → 템플릿 해시를 DB로 재계산 불가, C10이 DB 경로에서 공허 | V4 `pending_confirmation JSONB NOT NULL DEFAULT '[]'`, 템플릿 번들 body = `{fields, layout, pendingConfirmation}` |
| D3 | R-REASON "텍스트 길이 상한"의 데이터 키 없음 | 룰 body `reasonTextMaxLength`(필수, 예시값 500, 사규 덮어쓰기 불가) |
| D4 | `--tenants all`: RLS로 앱·마이그레이터 롤 모두 테넌트 목록 조회 불가 | 롤 `disclosure_operator` — `tenant.tenant_id` 컬럼 SELECT + 전용 `tenant_directory` 정책만. 작업은 테넌트별 `disclosure_app` 바인딩 |
| D5 | 해석기가 ACTIVE만 보면 경계일 배치 후 과거 상담일 해석이 0건 | 포트 `findActive` = 기준일에 시행 중이었던 룰(ACTIVE·RETIRED, 구간 포함). 배타 제약을 ACTIVE·RETIRED로 확장 |
| D6 | 레이어상 `rules → audit` 불가인데 배포·승인·활성화는 감사와 같은 트랜잭션 | 룰 거버넌스 서비스는 `disclosure-compliance`(§6.8), 번들 모델·로더·해석기는 `rules`, 트랜잭션 경계는 compliance 포트 `TenantTransactions`를 infra가 구현 |
| D7 | R-GRADE-UNAVAILABLE "사유 표기"인데 `GradeSnapshotItem`에 사유 없음 | 도메인에 `unavailableReason`(불투명 문자열) 추가, DB 컬럼은 V6 |
| D8 | 규칙 4 ↔ Phase 0 enum `SignerRole`·`ManagerConfirmMode` | 닫힌 어휘로 유지. `managerConfirmMode`↔`signerSet` 정합은 룰 스키마 `if/then`으로 강제 → 규칙 코드에 역할 이름 0 |

## 닫힌 어휘 enum (승인)

Phase 0 유지: `DisclosureStatus`, `GradeStatus`, `RuleStatus`, `SignerRole`, `SignatureChannel`, `ManagerConfirmMode`, `GateMode`, `IssuerMode`, `ReconStatus`, `ArtifactKind`.
신규: `RuleScope`(GLOBAL|TENANT), `TieBreak`(SHARED_RANK|STRICT), `SignOrder`(SEQUENTIAL|PARALLEL), `FieldSource`(CATALOG|ENGINE|AGENT|SYSTEM), `FieldScope`(PER_ITEM|PER_DOCUMENT), `TemplateType`(STANDARD|AUTO), `BundleKind`(RULE|TEMPLATE), `AuditAction`(RULE_DISTRIBUTE|RULE_APPROVE|RULE_ACTIVATE|RULE_RETIRE|RULE_RECONCILE), 해석 실패 코드(NO_GLOBAL_RULE|AMBIGUOUS|DISALLOWED_OVERRIDE|UNKNOWN_VALIDATION|NO_TEMPLATE).
문자열 데이터로 두는 것: 규칙 ID(평가 함수 등록 지점에 1회), 사유 코드, 등급 코드, 정책 ID, 본인확인 수단, 산출불가 사유.

## JCS 라이브러리

`io.github.erdtman:java-json-canonicalization:1.1` — 유일하게 RFC 8785 벡터 전부 통과(§3.2 예제·참조 testdata 9/9, 부록 B 26/26, ES6 표본 1,000만/1,000만), 런타임 의존 0. titanium-jcs 3.0.0-M4는 부록 B 24/26, 1.x·2.x는 17/26으로 불합격. 보정: 짝 없는 서로게이트는 REPORT 모드 UTF-8 인코더로 거부(이슈 #5), JsonNode 직렬화는 `WRITE_NAN_AS_STRINGS` 해제, 번들 파서는 `STRICT_DUPLICATE_DETECTION`. 알려진 결함 #4(1e-320 부근 서브노멀)는 이 도메인의 해시 입력(정수·문자열·불리언)과 무관 — 문서화.

## 감사 append 직렬화

`pg_advisory_xact_lock(hashtextextended('audit_log:' || :tenantId, 0))` 후 마지막 행 조회. 새 테이블 없음, PK `(tenant_id, seq)`가 최후 방어.
