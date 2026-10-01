# Phase 4 지시문 — 서명·관리자 확인·만료·증거 패키지 (v1.0)

## 역할과 맥락

`ga-disclosure` Phase 3B(`phase-3B`) 수용 후. 대상은 설계서 **§6.5(서명), §6.6(진행 중 무효·정정), §6.1(SIGN·COMPLETE·EXPIRE 전이), §5(`sign_session`·`signature`·V8), §9(증거 암호화), D-9·D-10, §12 Phase 4**와 `phase-03B-수용심사.md` §2·§3. REST 엔드포인트(고객 공개 `/sign/{token}` 포함)·인가는 Phase 6, 앵커·TSA·`verify`·파기는 Phase 5다. 이번 Phase는 **유스케이스·토큰·채널·증거**까지이며 HTTP는 없다.

이 Phase가 끝나면 다음이 테스트로 증명돼야 한다.

1. 서명은 사람이 아니라 **문서(canonical·PDF 두 해시)에 귀속**되고, 다른 버전·다른 문서에는 붙지 않는다.
2. 서명자 집합·순서·기한·본인확인 수단은 **전부 룰 데이터**에서 오고, 코드에는 역할·채널의 닫힌 어휘만 있다.
3. 완료 시 만들어지는 서명본 PDF와 증거 패키지는 **결정론적**이고, 원본 PDF 바이트가 서명본의 접두로 남는다.
4. 서명 증거(스트로크·스캔·본인확인 결과)는 문서 키로 암호화돼 있고, 평문은 어디에도 남지 않는다.

## 시작 전 보고

계획 맨 앞에 **3B 보고서의 "설계서와 다르게 한 9건" 표**(불변식을 넓히는 항목 표시)와 **Phase 4 질문 7번째**를 붙인다. 이어서 ① V8 DDL ② 세션·토큰 수명 주기 ③ 서명본 증분 갱신 방법(PDF/A 유지 근거) ④ 증거 패키지 매니페스트 스키마 ⑤ 대리 서명 탐지 지표 산식(룰 파라미터와의 대응)을 넣고 승인 후 진행.

## 작업 목록

### 1. V8 마이그레이션
- `signature`: `signed_pdf_hash TEXT NOT NULL`(= `disclosure.pdf_hash`, 트리거 대조 추가), `stroke_key`·`stroke_cipher_sha256`(TOUCH_PAD), `scan_key`·`scan_cipher_sha256`(PAPER_SCAN), `identity_check JSONB NOT NULL`(결과만, 입력값 없음), `view_evidence JSONB`(열람 완료·스크롤·소요 시간), `device JSONB`, `ip INET`, `method`(`DRAWN|UPLOADED_SCAN|SSO_APPROVAL`). `UNIQUE (tenant_id, disclosure_id, signer_role)` 유지.
- `sign_session`: `token_hash`(SHA-256, 원문 미저장), `channel`, `signer_role`, `issued_by`, `expires_at`, `used_at`, `revoked_at`, `revoke_reason`, `identity_failures SMALLINT`, `status`(`OPEN|USED|EXPIRED|REVOKED`). 한 확인서·역할당 `OPEN` 세션은 1개(부분 유일 인덱스). 세션 행은 append-only가 아니라 상태 전이만 허용(트리거: `OPEN`→나머지 1회).
- `disclosure` 메타: `void_reason_code`·`void_reason_text`, `supersede_reason_code`·`supersede_reason_text`(수용심사 §2-2, `superseded_by_id`와 함께 write-once). 기존 `void_reason` 컬럼은 이관 후 폐기(V8에서 복사 → 컬럼 제거는 전방 호환 2단계 원칙에 따라 V9에서).
- `retention_until` 연장 전용 트리거는 3B 그대로. `document_artifact`에 `SIGNED_PDF`·`EVIDENCE_ZIP` kind 사용(V1 열거).
- 오류 코드 이어서 배정, `db-error-codes.md`.

### 2. 세션·토큰 (`disclosure-sign`, Spring 무의존)
- `CreateSignSession(disclosureId, role, channel, actor)`: 부모가 `SEALED|PARTIALLY_SIGNED`이고 룰의 `channels[channel]=true`이며 순서(`signOrder=SEQUENTIAL`이면 앞 역할이 서명 완료)일 때만. 토큰은 256비트 난수, 저장은 해시만, TTL은 룰 `remoteLinkTtlHours`(REMOTE_LINK) 또는 세션 기본(TOUCH_PAD는 짧게, 룰 파라미터). 발급 시 `signed_doc_hash`·`signed_pdf_hash`를 세션에 **고정**한다.
- `OpenSession(token)`: 해시 대조·만료·취소 검사 → 열람 기록(PDF 열람은 3B `get` 경로, 감사 `ARTIFACT_VIEW` 사유 `SIGN`). 실패는 세션 상태를 바꾸지 않되 시도 횟수를 센다.
- `VerifyIdentity(token, inputs)`: 룰 `identityCheck[channel]`이 요구하는 수단 전부를 통과해야 한다. `BIRTH_DATE`는 Phase 2 `BirthDate.matches`(상수 시간), `LINK_POSSESSION`은 토큰 자체, `AGENT_FACE_TO_FACE`는 설계사 세션의 체크(설계사 계정에 귀속), `SCROLL_COMPLETE`는 열람 증거. 실패 `maxFailures` 도달 시 세션 `REVOKED(IDENTITY_FAILED)` + 플래그. **입력값은 어떤 로그·감사·예외에도 남지 않는다**(결과만).
- 세션은 사용 1회(`USED`). 무효·정정·만료 시 그 확인서의 `OPEN` 세션 전부 `REVOKED`(3B 심사 §3-5).

### 3. 서명 수집
- `CaptureSignature(token, strokes|image, device, ip)`: 세션의 고정 해시가 현재 부모 컬럼과 같은지 재확인 → 스트로크(좌표·시각 시퀀스, JSON)와 렌더 이미지(PNG)를 **문서 DEK**로 암호화(AAD = `tenant_id`·`disclosure_id`·`signature_id`·`kind`)해 저장 → `signature` INSERT(트리거가 두 해시·부모 상태 검사) → 상태 `PARTIALLY_SIGNED`(첫 서명) → 감사 `SIGNATURE_CAPTURED` → `SignatureCaptured(role)` 이벤트는 아웃박스 테이블에 적재만(피드는 Phase 6).
- `UploadPaperScan(token 또는 agent 세션, image)`: 각주의 확인서 번호·해시 접두를 **설계사가 입력**해 원본과 대조(OCR 없음) → 스캔 암호화 저장 → `method=UPLOADED_SCAN` → 관리자 확인 강제(룰 `channels.PAPER_SCAN.requiresManagerReview`, 기본 true — 룰 데이터에 키 추가).
- `AgentSign(agentSession)`: 설계사 본인 OIDC 주체(= 확인서 `agent_id`, `identity_link`로 해석)만. 터치 서명 또는 승인 클릭은 룰 파라미터 `agentSignMethod`.
- `ManagerConfirm(managerSession)`: 역할·조직 범위는 Phase 6 인가가 검사하므로 지금은 `actor` 인자와 감사. 예외 플래그가 있으면 사유 확인 체크 필수(§6.5). `managerConfirmMode=OFF`면 MANAGER는 signerSet에 없고 이 유스케이스는 거부(예외 승인은 3A `ApproveException`이 담당, 분리 원칙).
- **대리 서명 탐지**: 룰 `proxySignatureDetection`(기기 지문·IP 재사용 횟수/일, 발송→서명 최소 초). 감지 시 `compliance_flag(SIGNATURE_DEVICE_REUSE)`, 서명 자체는 막지 않는다(사후 점검 대상).

### 4. 완료·만료
- `Complete`: 룰 `signerSet` 전원·`signOrder` 충족 시 자동(마지막 서명의 같은 트랜잭션). 서명본 PDF(원본 + 증분 갱신 서명 외관 페이지, 결정론, PDF/A-2b 유지)와 증거 패키지 생성·암호화·업로드 → `COMPLETED` → `retention_until` 재계산(앵커 `COMPLETION`, 연장만) → 커밋 후 잠금 적용(3B 순서) → 오버라이드 플래그는 이미 봉인 시 닫힘 → 감사 `DISCLOSURE_COMPLETED` → `DisclosureCompleted` 아웃박스.
- 증거 패키지(`EVIDENCE_ZIP`, 결정론: 고정 타임스탬프·정렬된 엔트리): `manifest.json`(스키마 `contracts/seal/v1/evidence-manifest.schema.json`: 각 파일의 SHA-256, 확인서 번호, canonical·pdf·chain 해시, 룰·사규·서식 버전과 번들 해시, 스냅샷 헤더, 서명 레코드 요약, 감사 seq 범위, 앵커 참조는 Phase 5 전까지 `null`), canonical JSON, PDF, SIGNED_PDF, `signatures/*.json`(입력값 없음, 결과·시각·기기·IP·해시), 감사 발췌(해당 확인서 전 행, 체인 해시 포함). 스트로크·스캔 원본은 **패키지에 넣지 않는다**(별도 암호화 객체, 매니페스트에 해시만).
- `ExpireJob`(주입된 `Clock`, 일 1회·CLI): `SEALED|PARTIALLY_SIGNED`이고 `sealed_at + signDeadlineDays < today` → `EXPIRED`, 세션 전부 `REVOKED(EXPIRED)`, 플래그 `SIGN_EXPIRED`, 감사. 스케줄 등록은 Phase 6.
- 게이트 산식(§4.4의 `pendingRoles`·`gateSatisfied`)은 여기서 순수 함수로 구현하고 Phase 6 API가 호출한다. `gateRequiresManager` 테넌트 파라미터 반영.

### 5. 통지 포트·CLI·데모
- `NotifyPort.sendSignLink(customerRef, url)` 인터페이스 + 콘솔 구현(토큰 원문은 콘솔에만, 로그 금지). 전화번호는 Phase 2 `phoneForNotification` 경로(감사 사유 `SIGN_LINK`).
- CLI: `sign session/open/verify/capture/scan/agent/manager`, `disclosure expire --as-of`. 데모 시드에 3자 서명 완료 1건(TOUCH_PAD 고객 → AGENT → MANAGER), 원격 링크 서명 1건(콘솔 출력 토큰으로 이어 실행), 종이 스캔 1건, 만료 1건. 2회 실행 NOOP.

## 완료 기준 (전부 테스트로 증명)

| # | 기준 | 증명 방법 |
|---|---|---|
| G1 | 두 해시 귀속: `signed_doc_hash`·`signed_pdf_hash`가 부모와 다르면 INSERT 거부(트리거, 10상태 × 불일치 조합), 다른 버전(정정본)에 이전 세션 토큰으로 서명 불가 | `SignatureBindingIT` |
| G2 | 서명자 집합·순서·채널 허용·기한·본인확인 수단을 룰 데이터만 바꿔 동작 변경(코드 diff 0): `PARALLEL`, `managerConfirmMode=OFF`, `channels.PAPER_SCAN=false`, `identityCheck` 수단 교체 | `SignRulesAsDataIT` |
| G3 | 토큰: 재사용·만료·취소·해시 불일치 거부, 실패 `maxFailures` 후 `REVOKED` + 플래그, 토큰 원문이 DB·로그에 0건 | `SignSessionIT` + 평문 스캔 |
| G4 | 본인확인 입력값(생년월일)이 로그·감사·예외·DB 어디에도 없음, 결과만 `identity_check`에 | `PlaintextLeakScanIT` 확장(센티널 생년월일) |
| G5 | 스트로크·스캔이 문서 DEK로 암호화돼 저장, 키 파기 후 복호화 불가, 버킷 원시 바이트에 PNG 시그니처·좌표 평문 없음 | `SignatureEvidenceEncryptionIT` |
| G6 | 완료 자동 전이: `SEQUENTIAL`에서 순서 위반 세션 생성 거부, 전원 서명 시 같은 트랜잭션에서 `COMPLETED`; 관리자 OFF 테넌트는 2자로 완료 | `CompletionIT` |
| G7 | 서명본 PDF: 원본 바이트가 접두(byte prefix), 같은 입력 2회 바이트 동일, veraPDF PDF/A-2b 실패 0(CI 잡에 추가), 골든 기대값 커밋 | `SignedPdfGoldenTest`·CI |
| G8 | 증거 패키지: 매니페스트 스키마 통과, 엔트리 해시 전부 일치, 결정론(2회 동일), 스트로크 원본 미포함 | `EvidencePackageTest` |
| G9 | 만료 배치: 경계일 전후, 세션 일괄 `REVOKED`, 만료 문서에 서명 시도 거부 | `ExpireJobIT` |
| G10 | 무효·정정 중 세션 `REVOKED`, 기존 서명은 보존, 새 버전에 이월 없음 | `LifecycleIT` 확장 |
| G11 | 대리 서명 탐지: 같은 기기 지문으로 2명 이상 고객 서명 → 플래그, 발송 후 최소 초 미만 서명 → 플래그, 임계치는 룰 데이터 | `ProxyDetectionTest` |
| G12 | `retention_until`이 완료 시 연장만(단축 시도 거부 트리거), 앵커 목록이 룰 데이터 | `RetentionAnchorIT` |
| G13 | 게이트 산식: `gateRequiresManager` 양쪽, `pendingRoles` 정확 | `GateFunctionTest` |
| G14 | Phase 0~3B 무손상, 평문·jqwik·BOM 검사, 위반 주입 기록(최소: 트리거 해시 대조 제거, 본인확인 결과만 저장 규칙 위반, 세션 1회 사용 해제, 증분 갱신 대신 재생성) | 빌드 로그·보고서 |

## 하지 말 것

- HTTP 엔드포인트·인가·푸시/알림톡 실연동(Phase 6), 인정 전자서명 사업자 연동(v2 어댑터 인터페이스만), 앵커·TSA·파기(Phase 5), 프론트(Phase 7).
- 서명 이미지를 패키지나 PDF 외관 외의 평문 경로로 내보내는 것. 본인확인 입력값을 어떤 형태로든 저장하는 것. 역할·채널·수단 이름을 룰 데이터 밖(코드 상수)으로 꺼내는 것.
- 서명본을 재생성(원본 비접두)으로 만드는 것.

## 보고 형식

3B와 동일. 추가로 ① 세션 상태 전이표(문서 `state-table`과 같은 방식으로 기계 판독) ② 증거 매니페스트 예시 1건 ③ 서명본 PDF 증분 갱신 검증 방법 ④ Phase 5(앵커·TSA·검증·파기) 질문.
