# Phase 4 완료 보고 — 서명·관리자 확인·만료·증거 패키지

작성 2026-10-03 · 대상 지시문 `docs/phase-04-지시문.md` v1.0 · 계획 `docs/phase-04-계획.md`(승인 2026-10-02, `docs/phase-04-계획승인.md` — Q13만 대안, Q12 폐기, 나머지 권장안, 보강 B1~B3) · 설계서 v1.9 · 브랜치 `work/phase-4` · PR [#7](https://github.com/hjryoo-ai/ga-disclosure/pull/7)

## 요약

- **서명은 문서에 귀속된다(지시문 목표 1).**
  - `signature`는 `signed_doc_hash = canonical_hash`이면서 `signed_pdf_hash = pdf_hash`여야 들어간다(GD021·022·102). 확인서 상태 10종 × 두 해시 일치·불일치 전수를 시험했다.
  - 고객 서명은 그 확인서·역할의 OPEN 세션을 거쳐야 하고, 세션이 발급 때 고정한 두 해시가 현재 부모와 같아야 한다(GD103).
  - 무효·정정·만료는 같은 트랜잭션에서 OPEN 세션을 전부 닫는다. 그래서 옛 버전의 토큰으로 정정본에 서명할 수 없다(`LifecycleIT`).
- **서명 규칙은 전부 룰 데이터다(목표 2).**
  - 서명자 집합·순서, 채널 활성·관리자 검토, 채널별 본인확인 수단, TTL·기한, 대리 서명 임계치, 보존 앵커가 모두 룰 데이터다.
  - 같은 빌드에서 룰만 바꿔 다섯 시나리오가 달라진다: `PARALLEL`, `managerConfirmMode=OFF`, `channels.PAPER_SCAN.enabled=false`, `identityCheck` 교체, `signDeadlineDays`(`SignRulesAsDataIT`).
  - 코드에는 역할·채널·수단의 닫힌 어휘만 있다.
- **서명본과 증거 패키지는 결정론적이다(목표 3).**
  - 서명본 PDF는 봉인 PDF를 바이트 접두로 둔 **증분 갱신**이다. 두 번 만들면 바이트가 같고, 골든 SHA-256이 커밋돼 있으며, veraPDF PDF/A-2b 실패는 0이다(CI `pdfa-verify`).
  - 증거 패키지는 STORED ZIP이고 고정 시각이며, 엔트리는 매니페스트 다음 경로 순이다. 매니페스트는 스키마를 통과하고 모든 엔트리 해시를 묶는다.
- **서명 증거는 문서 키로 암호화된다(목표 4).**
  - 스트로크·이미지·스캔은 확인서 문서 DEK로 암호화한다. AAD는 `{disclosureId, kind, signatureId, tenantId, v}`다.
  - 버킷 원시 바이트에 PNG 시그니처와 좌표 평문이 없다. 키를 파기하면 증거도 읽을 수 없다.
  - 본인확인 입력값(생년월일)·전화번호·토큰 원문은 로그·감사·예외·DB 어디에도 없다(센티널 스캔).
- **완료·만료·게이트.**
  - 마지막 필수 서명의 트랜잭션에서 COMPLETED가 되고, 같은 트랜잭션에서 서명본·증거 패키지를 만든다. 보존기한은 앵커 일반식으로 연장만 하며, 커밋 뒤 잠금을 다시 건다.
  - 만료 배치는 서명 기한 끝(봉인일 KST + `signDeadlineDays`, 23:59:59.999999 KST)을 µs 단위로 판정한다.
  - 게이트 산식은 순수 함수다(`gateRequiresManager` × 상태 × 서명 부분집합 전수).
- **아웃박스(Q13 대안).** §4.5의 8개 이벤트를 상태 변경과 같은 트랜잭션에 적재한다. 적재 때 계약 스키마로 검증하고, 테넌트별 seq에 간격이 없다.
- **테스트 11,733건, 실패 0, 스킵 0**(3B: 10,790건). 위반 주입은 56건이며 전부 잡혔다(§3). 1건(S9 첫 시도)은 실패 0이었다. 주입 지점이 중복 방어여서였고, 빠진 경계 사례를 테스트로 추가한 뒤 다른 주입으로 대체했다.
- **CI**: PR #7 첫 run `37063019897`(pull_request, head `ac6bed9`)에서 `build`·`pdfa-verify`·`no-docker`가 전부 success였다. `gh run view`와 아티팩트 내려받기로 직접 확인했다(§3 CI). CI의 테스트 보고서는 로컬과 같은 11,733건이었다. CI가 렌더한 서명본 골든의 SHA-256도 커밋된 기대값(macOS 생성)과 같다.
- **데모**: 새 스택에서 `seed.sh`를 2회 실행했다. 1회째에 3자 터치 서명 완료(A-2), 원격 링크 서명 완료(A-3-REMOTE, 콘솔 토큰으로 이어 실행), 만료(A-5-EXPIRE), 종이 스캔 → 관리자 확인 완료(DEMO2 A-4-SCAN)가 나왔다. 2회째는 전부 NOOP이었다(§4).
- **엔진**: 계획 승인대로 E4 첫 커밋 전까지 엔진 저장소에 손대지 않았다.

## 1. 커밋·파일

커밋 목록(`main..work/phase-4`, 보고서 커밋은 이 표 뒤에 붙는다):

| 커밋 | 계획 §10 | 요약 |
|---|---|---|
| `724f35c` | — | docs: 3B 수용 심사 기록, Phase 4 지시문, CLAUDE.md 저장소 문구(승인된 사실 정정) |
| `3810b71` | — | docs: Phase 4 계획(승인 대기) |
| `077a513` | 1 | docs: 계획 승인 반영 — Q13 대안, D1 유지 + 로더 가드 |
| `4318322` | 2+3 | V8 스키마(GD100~GD106), 사유 코드, Phase 4 룰 데이터 |
| `3f18b82` | 4 | sign 순수 규칙 — 세션 상태표, 토큰, 본인확인, 대리 서명, 게이트, 보존 앵커 |
| `676eab6` | 5 | 서명본 PDF 증분 갱신, 증거 패키지 |
| `c5e02d9` | 6 | 서명 증거 암호화, 산출물·증거의 보존 경로 하나로 |
| `ff67743` | 7(앞) | 아웃박스 — 계약 이벤트를 같은 트랜잭션에, 확인서 `agent_id`는 `identity_link`에서 |
| `f59b0b9` | 7(앞) | 고정 룰 로더 가드 — 시행된 버전과 기록된 본문 해시(승인 Q1 조건) |
| `62c41d8` | 7 | 서명 유스케이스 — 세션·본인확인·고객 서명·SSO 서명자·종이 스캔·완료 |
| `f0132b0` | 8 | 만료 배치, 무효·정정·만료가 세션을 닫음 |
| `ac6bed9` | 9 | 서명 CLI, 데모 서명 흐름, 콘솔 통지 |

주요 추가 파일:

| 영역 | 파일 |
|---|---|
| DB | `V8__sign.sql`(사유 코드·`completed_at`·`sign_session`·`signature` 두 해시·`signature_evidence`·`retention_applied_until`·`outbox_event`/`outbox_head`), `docs/db-error-codes.md` GD100~GD106 |
| 계약 | `contracts/seal/v1/evidence-manifest.schema.json`, 이벤트 계약 `signatureChannel += SSO`, 룰 스키마(`channels` 객체·`identityCheck`·`sessionTtlMinutes`·`agentSignMethod`·`retentionAnchors`·사유 목록), `CHECKSUMS` |
| 룰 데이터 | `DISC-2026-07@e9bd163247cc`·`DISC-2027-01@64ab37ed001f`(제자리 재해시, 배포된 번들 없음), 서식 `signaturePage` 라벨 |
| sign(순수) | `session/{SessionStateTable, SignSessionState, SessionWindow, SessionEvent}`, `token/{SignToken, TokenSource}`, `proxy/ProxySignatureDetector`, `gate/GateFunction`, `retention/{RetentionAnchors, SignDeadline}` |
| seal | `signed/{SignedPdfAppender, SignaturePageLayout}`, `evidence/{EvidencePackageBuilder, EvidencePackageReader, …}`, 골든 `golden/signed-01/` |
| 도메인 | 애그리게이트 `sign`·`complete`·`expire`, `CompletionStamp`, 서명 표식(역할·시각) |
| 워크플로 | `disclosure/{SignSessionService, SignService, ExpireService, SessionClosing, Completion, RetentionLocks, SignSupport}`, `sign/*` 포트·값 |
| 감사·아웃박스 | `OutboxPort`·`EventType`·`OutboxPayloads`, 감사 동작 `SIGN_SESSION_*`·`SIGN_IDENTITY_CHECK`·`SIGNATURE_CAPTURED`·`DISCLOSURE_COMPLETED`·`DISCLOSURE_EXPIRE` |
| 인프라 | `SignSessionRepository`, `SignatureRepository`, `OutboxRepository`, `SecureRandomTokenSource`, 증거 암호화(`DocumentCryptoPort.encryptEvidence`) |
| 앱·데모 | `SignConfiguration`, `ConsoleSignLinkNotifier`, CLI `SignCommands`·`DemoSignatureSeeder`, `demo/sign/*`(허구 입력 파일), `signatures*.json`, `disclosures-demo2.json`, `seed.sh` Phase 4 절 |

## 2. 지시문 추가 보고 4항목

### ① 세션 상태 전이표 (기계 판독)

정본은 설계서 §6.5의 `session-state-table` 블록이다. 확인서 `state-table`과 같은 방식이다. `SessionStateTableTest.designDocumentTableEqualsTheCodeTableCellByCell`이 이 블록을 파싱해 코드의 `EnumMap`과 칸 단위로 양방향 대조한다(주입 J1·J2).

```session-state-table
state,event,result
OPEN,OPEN_VIEW,OPEN
OPEN,IDENTITY_PASS,OPEN
OPEN,IDENTITY_FAIL,OPEN|REVOKED
OPEN,CAPTURE,USED
OPEN,TTL_ELAPSED,EXPIRED
OPEN,REISSUE,REVOKED
OPEN,DOCUMENT_VOID,REVOKED
OPEN,DOCUMENT_SUPERSEDE,REVOKED
OPEN,DOCUMENT_EXPIRE,REVOKED
```

- 닫힌 상태(USED·EXPIRED·REVOKED)는 어떤 사건도 받지 않는다. 표 밖은 거부이고 세션은 그대로다. DB의 GD101(OPEN → 나머지 1회)도 같은 규칙을 강제한다.
- `IDENTITY_FAIL`은 실패 횟수가 `identityCheck.maxFailures`에 닿으면 `REVOKED(IDENTITY_FAILED)`가 되고 플래그 `IDENTITY_FAILED`를 남긴다.
- **누가 어떤 사건을 일으키는가**(7·8단계 구현):
  - `TTL_ELAPSED`: 접근 때는 판정만 해서 거부하고 상태는 바꾸지 않는다. 기록은 재발급과 만료 배치가 한다.
  - `DOCUMENT_*`: 확인서의 무효·정정·만료가 일으키며 **언제나 REVOKED**다. 문서 사건이 닫는 세션은 TTL이 지났어도 REVOKED로 남는다(§5 D7).

### ② 증거 매니페스트 예시 1건

데모 A-2(`DEMO1-2026-000002`)의 실제 증거 패키지에서 꺼낸 것이다. 3자 터치 서명(고객 TOUCH_PAD → 설계사 SSO 터치 → 관리자 SSO 승인)을 받았고 데모 실행 1회째에 만들어졌다. `artifacts get --kind EVIDENCE_ZIP`로 받았으며, 저장된 형태는 키 정렬·공백 없는 JCS 바이트다. 아래는 읽기 쉽게 들여쓴 것이다.

- 패키지 엔트리(STORED 8개, DOS 시각 1980-01-01 00:00:02): `manifest.json`, `audit.jsonl`, `canonical.json`, `disclosure-signed.pdf`, `disclosure.pdf`, `signatures/1-CUSTOMER.json`, `signatures/2-AGENT.json`, `signatures/3-MANAGER.json`.
- 스트로크·서명 이미지는 패키지에 없다. 매니페스트 `signatures[].evidence`에 평문 해시와 암호문 해시만 있다.
- 본인확인은 결과(`type`·`result`·`at`)만 있다.

```json
{
    "anchor": null,
    "audit": {
        "file": "audit.jsonl",
        "fromSeq": 36,
        "lastEntryHash": "16fe0942bacd929d1ff7d3fd637457f63a741c26988bfe002a98834a34e2a430",
        "rows": 21,
        "toSeq": 108
    },
    "completedAt": "2026-10-02T20:46:33.135228Z",
    "disclosureId": "37a9b924-361f-4539-b83a-0d375b285fe8",
    "disclosureNo": "DEMO1-2026-000002",
    "files": [
        {
            "bytes": 17959,
            "path": "audit.jsonl",
            "sha256": "74413396268535de389172c638de80314f74f42fe00c64d09642e67f7db5c515"
        },
        {
            "bytes": 2707,
            "path": "canonical.json",
            "sha256": "f6d163252fd6e4ddd66265b46d0315b6e910e36d3b6e8e8ffd8a0f8c566c760f"
        },
        {
            "bytes": 65176,
            "path": "disclosure-signed.pdf",
            "sha256": "7b8463c61465ecc7a0fd250557d2364417e18f24d82cb32373fa43c2020a2170"
        },
        {
            "bytes": 38304,
            "path": "disclosure.pdf",
            "sha256": "fc73c7abb9faa7b32df2c2c6de4bc2bbb341925ed085290268e1c61c1d0bc1ec"
        },
        {
            "bytes": 775,
            "path": "signatures/1-CUSTOMER.json",
            "sha256": "e94ec9bda47887ebdb7bc54c6e3b4359e60c0bbfc342921dacf9bc7f132d5f2f"
        },
        {
            "bytes": 429,
            "path": "signatures/2-AGENT.json",
            "sha256": "e8761cfa16aaedfcb3f44c36bd4dadcff986c2db4a879a0dee7ac14d9ec36662"
        },
        {
            "bytes": 515,
            "path": "signatures/3-MANAGER.json",
            "sha256": "bc4d2b7576764891e0ffd0441fee162549594d3b6042d8757fc63036b2632e22"
        }
    ],
    "hashes": {
        "canonical": "f6d163252fd6e4ddd66265b46d0315b6e910e36d3b6e8e8ffd8a0f8c566c760f",
        "chain": "4ad3969b8d64ac4464f990e945e7070ea49def101e0eb6993c9dde2f36da24ec",
        "chainSeq": 2,
        "pdf": "fc73c7abb9faa7b32df2c2c6de4bc2bbb341925ed085290268e1c61c1d0bc1ec",
        "signedPdf": "7b8463c61465ecc7a0fd250557d2364417e18f24d82cb32373fa43c2020a2170"
    },
    "manifestVersion": 1,
    "pinned": {
        "ruleBundleHash": "e9bd163247cced45432acfa6b4c144217676b51d024bbdfc823f879a2bb932b4",
        "ruleVersionId": "DISC-2026-07",
        "templateBundleHash": "1f822bce8bdb0d2377de69c9b8eabfbdc165d2894590802513ec4690c5a04dea",
        "templateId": "STANDARD",
        "templateVersion": 1,
        "tenantRuleBundleHash": "f5fc4cb20cb14837fe2729cbe1398fd61791ce33eef98e65bba630c22b5da489",
        "tenantRuleVersionId": "DEMO1-HOUSE-2026"
    },
    "retentionUntil": "2031-10-03",
    "sealedAt": "2026-10-02T20:46:31.123427Z",
    "signatures": [
        {
            "channel": "TOUCH_PAD",
            "evidence": [
                {
                    "bytes": 210,
                    "cipherSha256": "45d05c3c5cc85930db2f685f5e5328fc6d577f020c4ca904c751831b2ecf2e2c",
                    "kind": "IMAGE",
                    "sha256": "b594519a11f9eb99f27528bda9921cd3b45ab76ace2980d246bdac999ec16eb6"
                },
                {
                    "bytes": 145,
                    "cipherSha256": "c94d0f277a5196dabcda22fe7c7b4c304878a89f954868c3542f7eab812fcfa6",
                    "kind": "STROKES",
                    "sha256": "6feed68fb9db9b997d54d67cd28210747ac8af3eb7d44a580e1611ae97e33300"
                }
            ],
            "file": "signatures/1-CUSTOMER.json",
            "identityCheck": [
                {
                    "at": "2026-10-02T20:46:32.952033Z",
                    "result": "PASS",
                    "type": "AGENT_FACE_TO_FACE"
                },
                {
                    "at": "2026-10-02T20:46:32.952033Z",
                    "result": "PASS",
                    "type": "SCROLL_COMPLETE"
                }
            ],
            "method": "DRAWN",
            "role": "CUSTOMER",
            "sessionId": "4389fc79-a3dc-4563-991b-ed7403687a5a",
            "signatureId": "bcd709f7-227f-4ebe-ad3a-7122b9fee8a8",
            "signedAt": "2026-10-02T20:46:32.952033Z",
            "signedDocHash": "f6d163252fd6e4ddd66265b46d0315b6e910e36d3b6e8e8ffd8a0f8c566c760f",
            "signedPdfHash": "fc73c7abb9faa7b32df2c2c6de4bc2bbb341925ed085290268e1c61c1d0bc1ec"
        },
        {
            "channel": "SSO",
            "evidence": [
                {
                    "bytes": 210,
                    "cipherSha256": "4c7c54edf36ca75dc79bc079f6c09e340f54086f7ce12cb7ac723014e1fa5d9e",
                    "kind": "IMAGE",
                    "sha256": "b594519a11f9eb99f27528bda9921cd3b45ab76ace2980d246bdac999ec16eb6"
                },
                {
                    "bytes": 145,
                    "cipherSha256": "aaf9a5ff0ba99172a3733ea7fba73e2af81a12ab7dcb96e00edd4bff27957698",
                    "kind": "STROKES",
                    "sha256": "6feed68fb9db9b997d54d67cd28210747ac8af3eb7d44a580e1611ae97e33300"
                }
            ],
            "file": "signatures/2-AGENT.json",
            "identityCheck": [],
            "method": "DRAWN",
            "role": "AGENT",
            "sessionId": null,
            "signatureId": "e5b2f629-a205-4df3-a5ac-4c78799c7e1f",
            "signedAt": "2026-10-02T20:46:33.105525Z",
            "signedDocHash": "f6d163252fd6e4ddd66265b46d0315b6e910e36d3b6e8e8ffd8a0f8c566c760f",
            "signedPdfHash": "fc73c7abb9faa7b32df2c2c6de4bc2bbb341925ed085290268e1c61c1d0bc1ec"
        },
        {
            "channel": "SSO",
            "evidence": [],
            "file": "signatures/3-MANAGER.json",
            "identityCheck": [],
            "method": "SSO_APPROVAL",
            "role": "MANAGER",
            "sessionId": null,
            "signatureId": "3ef6637a-6481-481b-a3a6-1002cd9ac7cb",
            "signedAt": "2026-10-02T20:46:33.135228Z",
            "signedDocHash": "f6d163252fd6e4ddd66265b46d0315b6e910e36d3b6e8e8ffd8a0f8c566c760f",
            "signedPdfHash": "fc73c7abb9faa7b32df2c2c6de4bc2bbb341925ed085290268e1c61c1d0bc1ec"
        }
    ],
    "snapshot": {
        "generatedAt": "2026-10-02T20:46:31.090464Z",
        "gradingPolicyVersionId": "GRADING-2026-07",
        "rankingPolicyVersionId": "RANK-2026-07",
        "snapshotId": "GRD-20261003-1000002",
        "tieBreak": "SHARED_RANK"
    },
    "tenantId": "DEMO1",
    "version": 1
}
```

독립 확인(Python, 저장소 코드 미사용): 매니페스트 `files` 7개의 SHA-256·크기가 전부 엔트리와 일치했다. `audit.jsonl` 마지막 행의 `entryHash`는 `audit.lastEntryHash`와 같다. 범위는 seq 36..108의 21행이고, 사이의 빈 seq는 다른 대상의 감사 행이다. `manifest.json`은 키 정렬·공백 없는 직렬화와 바이트가 같다. 관리자 서명 파일(`3-MANAGER.json`)의 `acknowledgedFlags`에는 이 확인서의 플래그 2건이 있다. 둘 다 봉인 때 승인으로 닫힌 `VALIDATION_OVERRIDE`(R-GRADE-UNAVAILABLE·R-TEMP-PRODUCT)다(승인 Q9: 열림·닫힘 전부가 확인 대상).

### ③ 서명본 PDF 증분 갱신 검증 방법

서명본이 "봉인 PDF + 증분 갱신"이라는 사실은 세 층에서 확인한다.

1. **바이트 접두(누구나 재현 가능).** `SIGNED_PDF`의 앞 `len(PDF)` 바이트가 봉인 PDF와 같고, 그 SHA-256이 확인서의 `pdf_hash`·서명의 `signed_pdf_hash`와 같아야 한다. 나머지는 증분 갱신분(새 객체·xref·trailer)뿐이다.
   - 단위: `SignedPdfGoldenTest.sealedPdfIsAByteprefixAndTwoRunsAreIdentical`(주입 K1: 전체 재생성 → 실패 4건).
   - 데모 산출물로 직접 확인한 결과는 아래와 같다.

```
SHA-256(disclosure.pdf)                     = fc73c7ab…c1ec  (38,304 bytes)
SHA-256(disclosure-signed.pdf 앞 38,304바이트) = fc73c7ab…c1ec
disclosure-signed.pdf = 65,176 bytes (증분 갱신분 26,872 bytes: 새 페이지 객체 30 0 obj…, xref, trailer — %%EOF 2개)
EVIDENCE_ZIP 안의 disclosure-signed.pdf == artifacts get --kind SIGNED_PDF (cmp 동일)
manifest.hashes.pdf = signatures[*].signedPdfHash = fc73c7ab…c1ec
```

2. **결정론.** 같은 입력(봉인 PDF, 서명 레코드 요약, 서명 이미지)이면 바이트가 같다. 시각 의존 지점은 네 곳이고 전부 입력에서 온다.
   - 정보 사전 `/ModDate`와 XMP `ModifyDate`·`MetadataDate`는 마지막 서명 시각(+09:00)이다. XMP는 고정 템플릿으로 다시 쓴다.
   - `/ID` 둘째 원소의 시드는 `SHA-256(pdf_hash ‖ JCS(서명 요약))`의 앞 8바이트다(주입 K2: 시각 기반 → 골든 실패).
   - 외관 페이지는 저장해서 다시 읽은 뒤 가져온다. 그래야 폰트 서브셋이 확정된다(주입 K3).
   - 골든 `signed-01`의 SHA-256 `7a1f6a86…22c1`(68,416바이트)은 macOS에서 만들었다. 같은 커밋을 Linux 컨테이너(eclipse-temurin:25-jdk aarch64)에서 돌려도 일치했다(`c5e02d9` 기록). CI(ubuntu x64)에서도 `SignedPdfGoldenTest`가 통과했고, CI가 렌더한 `signed-01.pdf`의 SHA-256이 같은 값이었다(§3 CI).
3. **PDF/A-2b 유지.** veraPDF 1.30.2로 검사한다. CI `pdfa-verify` 잡은 `renderGolden`이 만드는 서명본 골든을 봉인 골든 3건과 함께 검사한다. 데모 서명본의 결과도 아래에 있다.

```
$ ./gradlew --project-dir verification/pdfa-verify run --args=<A-2 산출물 디렉터리>
demo-A2-sealed.pdf flavour=2b declared=2b compliant=true failedChecks=0
demo-A2-signed-from-zip.pdf flavour=2b declared=2b compliant=true failedChecks=0
demo-A2-signed.pdf flavour=2b declared=2b compliant=true failedChecks=0
3 PDFs, 0 non-compliant
```

- 이 서명본은 **PAdES 전자서명이 아니다.** 증거력은 서명 행의 두 해시 귀속, 봉인 체인, 증거 패키지에 있다(설계서 §6.5).
- 서명본 자체의 무결성 확인은 `manifest.hashes.signedPdf`와 `document_artifact`의 평문 해시 두 곳에서 한다.

### ④ Phase 5(앵커·TSA·검증·파기) 질문 — §7

## 3. 테스트와 완료 기준

### 테스트 수 (`ac6bed9`에서 `./gradlew build --continue`)

| 모듈 / 스위트 | Phase 3B | Phase 4 |
|---|---|---|
| platform-core test | 3,058 | 3,058 |
| platform-canonical test | 1,054 | 1,054 |
| platform-spring test | 13 | 13 |
| disclosure-domain test | 1,084 | 1,085 |
| disclosure-rules test | 1,308 | 1,334 |
| disclosure-workflow test | 1,094 | 1,136 |
| disclosure-seal test | 32 | 57 |
| disclosure-sign test | — | 487 |
| disclosure-audit test | 3 | 3 |
| disclosure-app archTest | 44 | 44 |
| disclosure-infra integrationTest | 3,092 | 3,453 |
| disclosure-app integrationTest | 8 | 9 |
| **합계** | **10,790** | **11,733** (실패 0, 스킵 0) |

- 증가분의 큰 몫은 sign 순수 규칙이다: `GateFunctionTest` 241(`gateRequiresManager` 2 × 상태 10 × 서명자 집합 3자·2자의 서명 부분집합 8 + 4 = 240, 이름 붙인 사례 1), `SignTokenTest` 214(시드 고정 왕복 200 + 형식 오류 10 + 단위 4).
- infra 쪽: `SealColumnCheckIT`가 1,341 → 1,452(V8 `completed_at`·사유 컬럼 편입), `ImmutabilityTriggerIT`가 508 → 548, `SignatureBindingIT` 69(상태 10종 × 해시 조합 + 경로), `SignSessionGuardIT` 42.
- 결과 XML 기준 집계다(`*/build/test-results/*/*.xml`, 실패·오류·스킵 합 0).

### 완료 기준

| # | 기준 | 증거 |
|---|---|---|
| G1 | 두 해시 귀속, 정정본에 옛 토큰 불가 | `SignatureBindingIT`(69): `signatureBindsBothHashesOnlyOnSignableStatuses`(상태 10종 × doc·pdf 해시 일치/불일치 전수, GD021·022·102), `sessionPinnedToOtherHashesRejected`(GD103), `managerOnlyWhenTheRuleHasIt`(GD104), `closedSessionRejected`·`sessionOfAnotherDisclosureRejected`. `LifecycleIT.supersedingWhileSigningRevokesTheOldTokenAndCarriesNoSignatureOver`(옛 토큰 → `SignTokenRejected`, 정정본 서명 0) |
| G2 | 룰 데이터만 바꿔 동작 변경(코드 diff 0) | `SignRulesAsDataIT`(5): `parallelOrderLetsTheAgentSignFirst`, `managerConfirmOffCompletesWithTwoSigners`, `aDisabledChannelIssuesNoSession`(`channels.PAPER_SCAN.enabled=false`), `swappingIdentityMethodsChangesWhatACaptureNeeds`, `theSignDeadlineIsData`. 각 시나리오는 `WorkflowSetup.withRule`로 룰 body만 바꾼다 |
| G3 | 토큰 재사용·만료·취소·불일치 거부, `maxFailures` → REVOKED + 플래그, 원문 0 | `SignSessionIT`(8): `aTokenIsUsedOnce`, `anElapsedSessionIsRefusedWithoutChangingItAndReissueRecordsTheExpiry`, `reissuingRevokesTheOpenSession`, `unknownMalformedAndForeignTokensAreTheSameRejection`(B2: 없는 테넌트·틀린 토큰이 같은 예외 타입), `identityFailuresRevokeTheSessionAtTheRuleLimit`, `theTokenPlaintextIsNowhereInTheDatabase`. 단위 `SignTokenTest`(J7·J8) |
| G4 | 본인확인 입력값이 어디에도 없음 | `PlaintextLeakScanIT.signPathLeavesNoIdentityInputPhoneOrToken`(센티널 생년월일로 성공·실패, 로그·감사·예외·DB 전 테이블 텍스트에서 0, 결과만 `identity_check`), `scanPlaintextLeaks`(결과 XML) |
| G5 | 증거 문서 DEK 암호화, 키 파기 후 불가, 버킷에 PNG·좌표 없음 | `SignatureEvidenceEncryptionIT`(6): `bucketHoldsOnlyCiphertextAndViewingDecryptsToTheRecordedHash`, `theCapturePathStoresOnlyCiphertextAndThePackageCarriesOnlyHashes`, `ciphertextMovedToAnotherSignatureKindOrDisclosureDoesNotOpen`, `shreddingTheDocumentKeyMakesEvidenceUnreadableToo`. DB GD105(`SignatureEvidenceGuardIT`) |
| G6 | 순서 위반 거부, 같은 트랜잭션 완료, OFF 2자, 종이 스캔 검토 전 미완료 | `CompletionIT`(4): `sequentialOrderIsABusinessRejection`, `theLastRequiredSignatureCompletesInItsOwnTransaction`, `aFailureWhileCompletingRollsTheLastSignatureBack`. `SignRulesAsDataIT.managerConfirmOffCompletesWithTwoSigners`. `PaperScanIT`(3: 검토 전 `COMPLETE` 거부 → 관리자 확인 또는 예외 승인 역할 검토 뒤 완료) |
| G7 | 서명본 접두·2회 동일·PDF/A-2b·골든 | `SignedPdfGoldenTest`(5): `signedPdfMatchesTheCommittedExpectation`, `sealedPdfIsAByteprefixAndTwoRunsAreIdentical`, `datesAndIdComeFromTheInputs`, `documentIdSeedFollowsTheSignatureSummary`. CI `pdfa-verify`(§3 CI) |
| G8 | 매니페스트 스키마, 엔트리 해시, 결정론, 스트로크 미포함 | `EvidencePackageTest`(5): `manifestFirstThenPathOrderAndEveryHashMatches`, `sameInputSameBytesStoredEntriesAtAFixedTime`, `signatureImageAndStrokesAreHashesOnly`, `tamperingWithAnyEntryIsDetected`. `EvidenceManifestSchemaTest`(15) |
| G9 | 만료 경계, 세션 일괄 REVOKED, 만료 문서 서명 거부 | `ExpireJobIT`(3): `theLastInstantOfTheDeadlineDayIsStillOpenAndTheNextOneExpires`(23:59:59.999999 KST는 그대로, +1µs에 EXPIRED, 세션 `REVOKED/DOCUMENT_EXPIRED`, 2회째 NOOP), `signaturesSurviveExpiryAndAnExpiredDisclosureTakesNoMore`, `elapsedSessionsAreRecordedAsExpiredWithoutTouchingTheDisclosure` |
| G10 | 무효·정정 중 세션 REVOKED, 서명 보존, 이월 없음 | `LifecycleIT`(12): `voidingWhileSigningRevokesOpenSessionsAndKeepsSignatures`, `supersedingWhileSigningRevokesTheOldTokenAndCarriesNoSignatureOver`, `reasonCodesComeFromThePinnedRuleAndAreWrittenOnce` |
| G11 | 대리 서명 탐지, 임계치 룰 데이터 | 단위 `ProxySignatureDetectorTest`(9) + 통합 `ProxyDetectionIT`(6): 같은 지문 · 같은 KST 날 · 고객 2명 → 플래그(서명은 통과), KST 날짜 경계, UTC 자정을 넘는 하나의 KST 날, TOUCH_PAD 제외, 발송 후 30초 < 60초 → 플래그, 임계치 교체. 감사에 지문·IP 원값 없음 |
| G12 | 완료 시 연장만, 앵커 목록 룰 데이터 | `RetentionAnchorIT`(3): `aLaterCompletionDayExtendsRetentionAndRelocksEverything`, `theAnchorListIsRuleData`, `shorteningIsRefusedByTheDatabase`(GD094). 단위 `RetentionAnchorsTest`(J6), `retention_applied_until` 증가만(`SignatureEvidenceGuardIT`, I9) |
| G13 | 게이트 산식 | `GateFunctionTest`(241): `gateFollowsStatusAndSignatures`(`gateRequiresManager` 양쪽 × 상태 10종 × 서명 부분집합 전수), `namedCases`(J5) |
| G14 | 0~3B 무손상, 평문·jqwik·BOM, 위반 주입 | 위 11,733건, 아래 주입 기록, CI `build`의 jqwik 그래프 검사·`scanPlaintextLeaks` |

계획 §8의 추가 테스트:
- `SessionStateTableTest`: 설계서 블록과 `EnumMap`의 양방향 대조.
- 아웃박스: `OutboxEventsIT`(6)와 `OutboxGuardIT`. 계획의 `OutboxContractTest`는 `OutboxEventsIT`에 합쳤다. 적재 payload의 계약 통과와 seq 갭 0을 실제 유스케이스 경로에서 본다.
- 로더 가드: `PinnedRuleGuardIT`(3).

**BOM·의존성**: `main` 대비 락 파일 변경은 0건이다(락 파일·버전 카탈로그 무변경 — `git diff main..HEAD -- gradle.lockfile */gradle.lockfile gradle/libs.versions.toml` 빈 출력). 새 외부 의존성 없이 PDFBox·openhtmltopdf·JCS(3B 도입분)로 서명본과 증거 패키지를 만들었다. `net.jqwik`은 락 파일에 0건이다.

### 규칙 테스트 위반 주입 기록 (주입 → 실패 확인 → 제거)

각 주입은 코드나 마이그레이션을 임시로 고쳐 대상 스위트를 돌린 뒤 원복했다. 기록 원문은 각 커밋 메시지 본문에 있다. 지시문 G14의 최소 4종과 보강 B1의 2종은 굵게 표시했다.

**V8 트리거·제약**(`4318322`):

| # | 주입 | 실패 |
|---|---|---|
| **I1** | **GD102 PDF 해시 대조 제거** | `SignatureBindingIT` 2 |
| I2 | GD103 세션 OPEN 검사 제거 | `SignatureBindingIT` 3 |
| I3 | GD103 고정 해시 비교 제거 | `SignatureBindingIT` 1 |
| I4 | GD104 서명자 집합 제거 | `SignatureBindingIT` 2 |
| **I5** | **GD101 1회 사용 해제(OPEN 외 전이 허용)** | `SignSessionGuardIT` 3 |
| I6 | GD101 발급 해시 고정 제거 | `SignSessionGuardIT` 2 |
| I7 | GD101 실패 횟수 +1 제거 | `SignSessionGuardIT` 1 |
| I8 | GD105 문서 키 검사 제거 | `SignatureEvidenceGuardIT` 2 |
| I9 | GD105 적용 기한 증가만 제거 | `SignatureEvidenceGuardIT` 1 |
| I10 | GD106 무간격 seq 제거 | `OutboxGuardIT` 5 |
| I11 | GD106 발행 1회 제거 | `OutboxGuardIT` 1 |
| I12 | GD100 `completed_at` 한 번 쓰기 제거 | `ImmutabilityTriggerIT` 1 |
| I13 | GD100 무효 사유 한 번 쓰기 제거 | `ImmutabilityTriggerIT` 2 + `LifecycleIT` 1 |
| I14 | GD093 산출물 적용 기한 증가만 제거 | `AppendOnlyTriggerIT` 1 |
| I15 | 백필에서 테넌트 RLS 해제 창 제거 | `V8MigrationIT`: V8이 `ck_disclosure_void`로 실패(행 미이관) |

**sign 순수 규칙**(`3f18b82`):

| # | 주입 | 실패 |
|---|---|---|
| J1 | 설계서 블록에서 `OPEN,REISSUE` 행 삭제 | `SessionStateTableTest` 1 |
| J2 | 코드 표의 IDENTITY_FAIL이 끝내 REVOKED 안 됨 | 표 테스트 + 상태 테스트 2 |
| J3 | 대리 서명 탐지가 TOUCH_PAD도 평가 | `ProxySignatureDetectorTest` 4 |
| J4 | 탐지 날짜를 UTC로 | `ProxySignatureDetectorTest` 1 |
| J5 | 게이트가 `gateRequiresManager` 무시 | `GateFunctionTest` 7 |
| J6 | 보존기한 단축 허용 | `RetentionAnchorsTest` 1 |
| J7 | 토큰 `toString`이 비밀 노출 | `SignTokenTest` 1 |
| J8 | 토큰 해시를 비밀 부분에만 | `SignTokenTest` 1 |
| J9 | 서명 수집이 본인확인 생략 | `SignSessionStateTest` 1 |

**서명본·증거 패키지**(`676eab6`):

| # | 주입 | 실패 |
|---|---|---|
| **K1** | **증분 갱신 대신 재생성(B1)** | `SignedPdfGoldenTest` 4(접두 단언 포함) |
| **K2** | **`/ID` 시드를 시각 기반으로(B1)** | `SignedPdfGoldenTest` 2(골든 포함) |
| K3 | 외관 페이지를 저장 전에 가져옴 | `SignedPdfGoldenTest` 1 |
| K4 | 증거 ZIP을 Deflate로 | `EvidencePackageTest` 2 |
| K5 | 매니페스트 `files`에서 `audit.jsonl` 누락 | `EvidencePackageTest` 1 |
| K6 | 서명 해시 결속 검사 제거 | `EvidencePackageTest` 1 |

**증거 암호화**(`c5e02d9`) · **아웃박스**(`ff67743`) · **로더 가드**(`f59b0b9`):

| # | 주입 | 실패 |
|---|---|---|
| L1 | 증거 AAD에서 `signatureId` 제거 | `SignatureEvidenceEncryptionIT` 1 |
| L2 | reconcile이 증거 행 무시 | `SignatureEvidenceEncryptionIT` 1 |
| L3 | gc 참조 검사가 증거 무시 | `SignatureEvidenceEncryptionIT` 1 |
| L4 | 목록에 없는 `signature_evidence` 쓰기 | `SealWriteScanTest` 1 |
| O1 | 무효 경로가 이벤트를 안 냄 | `OutboxEventsIT` 1 |
| O2 | 계약 검증 생략 | `OutboxEventsIT` 1 |
| O3 | 플래그 재사용 때 다시 냄 | `OutboxEventsIT` 1 |
| O4 | 초안 생성이 행위자 subject를 `agent_id`로 | `OutboxEventsIT` 3 |
| P1 | 로더가 본문 해시 검사 생략 | `PinnedRuleGuardIT` 2 |
| P2 | 리졸버가 DRAFT·APPROVED 고정 허용 | `RuleResolverTest` 1 |

**서명 유스케이스**(`62c41d8`) · **만료·무효·정정**(`f0132b0`) · **CLI·데모**(`ac6bed9`):

| # | 주입 | 실패 |
|---|---|---|
| S1 | 거부 감사에서 테넌트 행 확인 제거 | `SignSessionIT` 1 |
| **S2** | **본인확인 입력값을 감사에 기록** | `PlaintextLeakScanIT` 1 |
| S3 | 서명 수집이 세션을 OPEN으로 둠(1회 사용 해제, 애플리케이션 층) | `SignSessionIT` 1 |
| S4 | SEQUENTIAL 순서 검사 제거 | `CompletionIT` 1 |
| S5 | 관리자 확인의 플래그 acknowledge 미요구 | `PaperScanIT` 1 |
| S6 | 완료가 열린 종이 스캔 검토 무시 | `PaperScanIT` 1 |
| S7 | 서명 시각 하한(직전 + 1µs) 제거 | `SigningTimeTest` 1 |
| S8 | 목록 밖 `sign_session` UPDATE | `DisclosureWriteScanTest` 1 |
| S9 | 대리 서명 날짜를 UTC로(유스케이스 경로) | `ProxyDetectionIT` 1 |
| S10 | 완료가 커밋 뒤 재잠금 생략 | `RetentionAnchorIT` 2 |
| T1 | 무효가 OPEN 세션을 남김 | `LifecycleIT` 1 |
| T2 | 정정이 OPEN 세션을 남김 | `LifecycleIT` 1 |
| T3 | 기한 마지막 순간에 만료 | `ExpireJobIT` 1 |
| T4 | 만료가 세션을 열어 둠 | `ExpireJobIT` 1 |
| T5 | 문서 사건이 TTL 지난 세션을 EXPIRED로 기록 | `ExpireJobIT` 1 |
| U1 | 데모 서명이 COMPLETED 사례를 NOOP으로 넘기지 않음 | `OperatorCliIT` 1(2회째 실행) |

- **S9의 첫 시도는 실패 0이었다.** 유스케이스의 채널 필터(REMOTE_LINK만)를 지웠는데, 탐지기와 집계 SQL도 같은 필터를 갖고 있어 중복 방어였다. 테스트는 그대로 두었다. 대신 빠져 있던 경계 사례("UTC 자정을 넘는 하나의 KST 날", `oneKstDayAcrossTheUtcMidnightIsOneDay`)를 추가하고, 그 경계를 깨는 주입으로 S9를 대체했다.
- 모든 주입은 제거한 뒤 전체 빌드가 통과했다. 거짓 양성은 없었다.

### CI (1차 증거, `gh run view`)

**run `37063019897`**(pull_request, head `ac6bed9`) — 전부 success. 로그는 `gh run view --job <id> --log`, 아티팩트는 `gh run download`로 직접 받아 확인했다.

- `build`(job `111023959505`, ubuntu-latest x64, Temurin 25.0.4)
  - `net.jqwik` 의존 그래프 검사 통과.
  - `scanPlaintextLeaks: 117 result files, 14 forbidden strings, 0 hits`, `BUILD SUCCESSFUL in 4m 5s`.
  - C11 발행 아티팩트 소비 빌드 `BUILD SUCCESSFUL in 8s`.
  - 아티팩트 `test-reports`의 모듈별 HTML 보고서 합계는 **11,733건, 실패 0, 무시 0**이다. 모듈별 수가 §3 표의 로컬 값과 하나도 다르지 않다. `SignedPdfGoldenTest` 5건(커밋된 골든 SHA-256 일치 포함)과 `SealGoldenTest`도 여기 들어 있다.
- `pdfa-verify`(job `111023959233`)
  - `case-01.pdf`·`case-02.pdf`·`case-03.pdf`·**`signed-01.pdf`** 각각 `flavour=2b declared=2b compliant=true failedChecks=0`, `4 PDFs, 0 non-compliant`.
  - 아티팩트 `golden-pdfs`의 `signed-01.pdf`는 SHA-256 `7a1f6a86d8758e67c8e0217a6c3b3be7a3b36496ba17e160f82351c656b222c1`이다. 이는 macOS에서 만든 커밋 기대값(`expected.properties`)과 같다. 그래서 서명본 결정론이 OS·아키텍처를 넘어 성립한다.
- `no-docker`(job `111023959606`)
  - `disclosure-infra` 통합 테스트 `267 tests completed, 267 failed`, `result files: 52, with failures/errors: 52, skipped: 0`, `OK: integration tests failed (not skipped) because Docker is missing`.
- 보고서 커밋이 올린 새 head의 CI는 PR 코멘트로 덧붙인다(문서만 바뀐다).

### CLAUDE.md 규칙 9 기록

- 빌드·테스트·데모 로그(데모 2회 994행)에서 지시문 형태의 문장은 0건이었다("ignore previous" 류의 영문·국문 패턴 검색).
- 데모 고객 파일과 본인확인 입력 파일의 이름·전화·생년월일 11개 값(하이픈 제거형 포함)도 두 데모 로그에서 0건이었다.
- `net.jqwik`은 락 파일과 의존 그래프에 0건이다(CI `build`의 그래프 검사).

## 4. 데모

새 스택(`docker compose down -v` → `up -d --wait postgres seaweedfs`, PostgreSQL 18.6 + SeaweedFS digest 고정)에서 `disclosure-demo/scripts/seed.sh 2026-09-23`을 그대로 2회 실행했다(`ac6bed9`의 코드). 두 번 다 exit 0이었다. 로그는 3B 단계 줄과 Phase 4 줄만 발췌했고, UUID는 앞 8자리만 남겼다.

```
(1회)
DEMO_DISCLOSURE DEMO1 A-1 CREATED id=da95808d-… status=REASONED
  A-1 seal -> SEALED no=DEMO1-2026-000001
  A-1 supersede -> SUPERSEDED next=852b05d4-…
DEMO_DISCLOSURE DEMO1 A-1 CORRECTED id=852b05d4-… version=2 status=REASONED
DEMO_DISCLOSURE DEMO1 A-2 CREATED id=37a9b924-… status=REASONED
  A-2 approve R-GRADE-UNAVAILABLE by demo-manager
  A-2 approve R-TEMP-PRODUCT by demo-manager
  A-2 seal -> SEALED no=DEMO1-2026-000002
DEMO_DISCLOSURE DEMO1 A-3-REMOTE CREATED id=881f4e5d-… status=REASONED
  A-3-REMOTE seal -> SEALED no=DEMO1-2026-000003
DEMO_DISCLOSURE DEMO1 A-5-EXPIRE CREATED id=4c06434c-… status=REASONED
  A-5-EXPIRE seal -> SEALED no=DEMO1-2026-000004
DEMO_DISCLOSURE DEMO1 A-1-REQUEST CREATED id=e4f9567c-… status=REASONED
DEMO_SIGN DEMO1 A-2 id=37a9b924-… TOUCH_PAD -> COMPLETED
SIGN LINK https://sign.example.invalid/sign/DEMO1~<토큰 비밀부 — 콘솔에만 출력>
DEMO_SIGN DEMO1 A-3-REMOTE id=881f4e5d-… REMOTE_LINK sent=true — continue with: sign verify / sign capture (token from the SIGN LINK line), then sign agent / sign manager
SIGN_OPEN bytes=36511 view=RECORDED
SIGN_VERIFY session=56ffec67-… results=[LINK_POSSESSION:PASS, BIRTH_DATE:PASS] missing=[] failures=0
SIGN_CAPTURE 881f4e5d-… PARTIALLY_SIGNED signature=ec0d0e5e-…
SIGN_AGENT 881f4e5d-… PARTIALLY_SIGNED signature=3c657e05-…
SIGN_MANAGER acknowledging 1 flag(s): [1d0e4b1b-…]
SIGN_MANAGER 881f4e5d-… COMPLETED signature=5c841a82-…
EXPIRE DEMO1 asOf=2026-11-01T20:46:45.274535Z expired=1 stillOpen=0 sessionsExpired=0 [4c06434c-…]
DEMO_DISCLOSURE DEMO2 A-4-SCAN CREATED id=22099425-… status=REASONED
  A-4-SCAN seal -> SEALED no=DEMO2-2026-000001
DEMO_SIGN DEMO2 A-4-SCAN id=22099425-… PAPER_SCAN -> COMPLETED
(2회)
DEMO_DISCLOSURE DEMO1 A-1 NOOP existing=da95808d-… (demo rule: same customer, date, group)
  A-1 seal NOOP (already sealed)
  A-1 supersede NOOP (a corrected version exists)
DEMO_DISCLOSURE DEMO1 A-2 NOOP existing=37a9b924-… (demo rule: same customer, date, group)
  A-2 seal NOOP (already sealed)
DEMO_DISCLOSURE DEMO1 A-3-REMOTE NOOP existing=881f4e5d-… (demo rule: same customer, date, group)
  A-3-REMOTE seal NOOP (already sealed)
DEMO_DISCLOSURE DEMO1 A-5-EXPIRE NOOP existing=4c06434c-… (demo rule: same customer, date, group)
  A-5-EXPIRE seal NOOP (already sealed)
DEMO_DISCLOSURE DEMO1 A-1-REQUEST NOOP existing=e4f9567c-… (demo rule: same customer, date, group)
DEMO_SIGN DEMO1 A-2 id=37a9b924-… NOOP (COMPLETED)
DEMO_SIGN DEMO1 A-3-REMOTE id=881f4e5d-… NOOP (COMPLETED)
EXPIRE DEMO1 asOf=2026-11-01T20:47:22.190482Z expired=0 stillOpen=0 sessionsExpired=0 NOOP
DEMO_DISCLOSURE DEMO2 A-4-SCAN NOOP existing=22099425-… (demo rule: same customer, date, group)
  A-4-SCAN seal NOOP (already sealed)
DEMO_SIGN DEMO2 A-4-SCAN id=22099425-… NOOP (COMPLETED)
```

- **원격 링크**는 `SIGN LINK` 줄의 토큰을 스크립트가 받아 실행한다. 토큰은 콘솔(표준 출력)에만 나가고 로거로는 나가지 않는다. 그 토큰으로 `sign open`(봉인 PDF 열람, `ARTIFACT_VIEW` 사유 SIGN) → `sign verify --inputs-file`(링크 소유 + 생년월일, 결과만 출력) → `sign capture`(스트로크·이미지·기기 파일) → `sign agent` → `sign manager --ack all`로 이어진다. 전화번호는 콘솔에 나오지 않는다.
- A-3-REMOTE에서 관리자가 확인한 플래그 1건은 `SIGNATURE_DEVICE_REUSE`(대상 SIGNATURE)다. 데모 스크립트가 링크 발송 5초 뒤에 서명해 생긴 FAST_SIGN 지표이다(감사 `FLAG_RAISE`의 `indicators: [{kind: FAST_SIGN, value: 5, threshold: 60}]`). 탐지는 서명을 막지 않는다. DB 조회 결과 이 플래그는 열린 채로 남아 사후 점검 대상이다.
- **만료**: `disclosure expire --tenants DEMO1 --as-of P30D`(시계 + 30일)가 서명이 없는 A-5-EXPIRE를 EXPIRED로 바꾼다. 같은 판정에서 A-2·A-3은 이미 COMPLETED라 대상이 아니다.
- **종이 스캔**은 DEMO2에서 한다(§5 D8). 각주의 확인서 번호와 해시 접두를 데모 파일에서 읽어 대조한다. 그 뒤 `PAPER_SCAN_REVIEW` 플래그를 관리자 확인이 해소하고 완료된다.
- A-2 산출물(봉인 PDF·서명본·증거 패키지)의 확인 결과는 §2 ②·③에 있다.
- `OperatorCliIT.demoSignaturesCompleteThreeFlowsExpireOneAndRepeatAsNoop`이 같은 흐름을 Testcontainers로 반복한다. 상태 SUPERSEDED·COMPLETED×3·EXPIRED, 2회째 NOOP 2줄, `SIGN LINK` 없음을 확인한다(주입 U1).

## 5. 설계서와 달리 구현했거나 해석한 지점

설계서는 해당 코드와 같은 커밋에서 v1.9 변경 이력 ⑯~⑲로 고쳤다. 아래는 계획·지시문의 문언과 다른 지점이다.

| # | 지점 | 이유 |
|---|---|---|
| D1 | **잠금 순서는 확인서 → 세션**이다(계획 §7.1은 세션 → 확인서) | 무효·정정·만료는 확인서를 잡은 뒤 세션을 닫는다. 서명 경로가 반대 순서로 잡으면 둘이 교착한다. 모든 경로가 확인서를 먼저 잠근다(설계서 §6.5 ⑰) |
| D2 | **서명 시각은 확인서별로 엄격히 증가한다**: `max(시계를 µs로 절삭, 직전 서명 + 1µs)` | 같은 시계 값이나 늦은 인스턴스 시계 때문에 두 서명의 시각이 같거나 거꾸로 되면, 저장된 순서가 서명 순서를 증명하지 못한다. 실제로 `PaperScanIT`에서 같은 시각 두 서명의 순서가 흔들려 SEQUENTIAL 검사가 거짓으로 실패했다(S7) |
| D3 | **토큰 경로는 거부 하나**(`SignTokenRejected`): 모르는 토큰, 형식 오류, 닫힌 세션, 경과한 세션. `SIGN_SESSION_DENIED` 감사는 **접두의 테넌트 행이 있을 때만** 남긴다 | B2(존재 누설 금지)를 유스케이스 층에서 맞춘 것이다. `audit_log`에는 테넌트 FK가 없어, 위조 접두가 없는 테넌트 이름으로 감사 행을 쓸 수 있었다(S1이 이 확인을 지키는지 본다). 응답 지연을 같게 하는 것은 Phase 6 공개 엔드포인트의 몫이다 |
| D4 | 룰 키 `identityCheck.PAPER_SCAN = [AGENT_FACE_TO_FACE]` 추가 | 종이 스캔도 고객 세션을 거치는데, 그 채널의 본인확인 수단이 룰에 없었다. 코드에 기본값을 두지 않으려고 데이터에 넣었다. 번들은 제자리에서 재해시했다(배포된 번들 없음). `RuleAsDataIT` 픽스처도 같은 키를 받아 재해시했다(`DISC-TEST-REASON@61c4a20c64c9`) |
| D5 | `NotifyPort.sendSignLink(Sensitive<PhoneNumber>, SignToken)`이다(지시문 `sendSignLink(customerRef, url)`). 번호 조회 감사 목적은 `REMOTE_LINK`다(지시문·계획 `SIGN_LINK`) | 번호는 유스케이스가 `phoneForNotification`으로 꺼내 넘긴다. 그래서 포트 구현이 고객 저장소에 닿지 않고, 임의 번호로 보낼 수 없다는 규칙이 한 곳에 모인다. URL은 배포 설정이라 구현이 만든다. 목적 어휘는 Phase 2의 닫힌 열거 `NotificationPurpose`에 이미 `REMOTE_LINK`가 있어 새 값을 만들지 않았다. 콘솔에는 번호를 아예 찍지 않는다(계획은 마스킹 번호) |
| D6 | **완료는 COMPLETE 단계 검증 결과가 전부 통과해야 한다.** 완료 때는 오버라이드·승인 경로가 없다 | 오버라이드는 봉인 때 승인으로 닫혔다. 완료 시점에 새로 실패하는 검증(서명자·순서·종이 스캔 검토)은 서명을 더 받거나 검토해서 푸는 것이지 승인으로 넘길 것이 아니다 |
| D7 | **문서 사건(무효·정정·만료)은 언제나 REVOKED**로 닫는다. TTL 경과를 EXPIRED로 기록하는 것은 재발급과 만료 배치의 TTL 정리뿐이다 | 세션 만료는 서명 기한 끝을 넘지 않는다(`SessionWindow`). 그래서 문서가 만료되는 순간에는 열린 세션의 TTL이 이미 다 지나 있다. 지시문 "세션 전부 REVOKED(EXPIRED)"를 `REVOKED(DOCUMENT_EXPIRED)`로 구현했다. 처음 구현은 TTL 경과를 먼저 보아 EXPIRED를 남겼고, `ExpireJobIT`가 이를 잡았다 |
| D8 | **종이 스캔 데모는 DEMO2에서 한다** | DEMO1 사규(`DEMO1-HOUSE-2026`)는 Phase 1 지시문에 따라 PAPER_SCAN을 끈다. 첫 데모 실행이 `CHANNEL_DISABLED`로 거부됐다. 사규를 바꾸지 않고 사례를 옮겼다 |
| D9 | 만료 판정 시각 `--as-of`는 순간 또는 ISO 기간(`P30D` = 시계 + 30일)을 받는다 | 데모 만료 사례를 과거 상담일 데이터가 아니라 판정 시각으로 만든다(계획 §7.6). 감사에는 `asOf`와 실제 시각이 함께 남는다 |
| D10 | 증거 패키지 ZIP의 DOS 시각은 1980-01-01 **00:00:02**다(계획 00:00) | JDK는 00:00:00을 "1980년 이전" 표식으로 보고 시스템 시간대로 계산한 확장 타임스탬프 extra 필드를 붙인다. 그러면 결정론이 깨진다(G8 테스트가 잡았다, `676eab6`) |
| D11 | 확인서 `agent_id`를 `identity_link`로 해석해 기록한다. 3A·3B는 행위자 subject를 그대로 저장했다 | CLAUDE.md 규칙 5. 이벤트 계약의 `agentId` 패턴도 subject로는 어긋났다(`ff67743`). 데모 시드와 CLI 테스트가 링크를 준비한다 |

## 6. 엔진

- 엔진 저장소는 손대지 않았다.
- E4 첫 커밋에서 할 일(3B 수용심사 §4 승인분):
  - ① 마이그레이션 번호 전역 단조 문서화.
  - ② V104 `product_key` 폭 40.

## 7. Phase 5(앵커·TSA·검증·파기) 질문

1. **앵커 대상·주기·테넌트 경계.**
   - 앵커할 값: 테넌트별 봉인 체인 머리(`chain_seq`·`chain_hash`)와 감사 체인 머리(`seq`·`entry_hash`).
   - 권장: 하루 1회(KST) 테넌트마다 앵커 레코드 `{tenantId, sealChainSeq, sealChainHead, auditSeq, auditHead, at}`를 만든다. 그 JCS 해시들의 머클 루트 1개만 외부에 고정한다. 테넌트 검증자는 자기 잎과 경로(형제 해시)만 받으므로 다른 테넌트의 내용은 드러나지 않는다. 경로 길이로 테넌트 수의 규모는 드러난다.
   - 대안: 테넌트마다 따로 외부 고정. 비용이 테넌트 수만큼 늘고 규모 노출은 없다.
2. **증거 패키지의 `anchor` 필드.** 패키지는 완료 트랜잭션에서 만들어져 잠기고, 그날의 앵커는 그 뒤에 생긴다.
   - 권장: 패키지는 다시 만들지 않는다.
     - `anchor`에는 완료 시점에 **이미 있던 최신 앵커**의 참조(앵커 ID·seq·루트)를 넣는다. 첫 앵커 전이면 null이다. 이것이 "그 앵커부터 이 문서까지 체인이 이어진다"의 검증 시작점이 된다.
     - 문서 **이후**의 앵커 증명(앵커 레코드 + 머클 경로 + TSA 토큰)은 별도 불변 객체 `anchor_receipt`로 둔다. 검증 명령이 `chainSeq`·`audit.toSeq`를 덮는 첫 영수증을 찾아 잇는다.
   - 대안: 앵커 뒤에 패키지를 새 산출물 kind로 한 번 더 만든다. 산출물과 보존 객체가 두 배가 된다.
3. **TSA.**
   - 권장: RFC 3161 타임스탬프는 일일 머클 루트에만 받는다. 서명본은 계속 PAdES가 아니다(설계서 §6.5).
   - 테스트는 로컬 TSA 스텁을 쓴다. 토큰 생성·검증에 BouncyCastle `bcpkix-jdk18on`이 필요하다. Boot BOM이 관리하지 않는 새 의존성이므로 착수 때 최신 안정판을 확인해 고정한다. **추가 승인이 필요하다.**
   - 실제 TSA 기관 선택과 그 인증서 체인 보관 정책은 운영 결정이므로 질문으로 남긴다. 지금은 포트 + 스텁만 둔다.
4. **`verify` 범위.** 권장: 명령 둘.
   - `verify package <zip>`: 오프라인, DB 없이. 매니페스트 해시, 서명본 접두 = `disclosure.pdf`, 서명의 두 해시 결속, 감사 발췌 행별 `entry_hash` 재계산, 영수증이 있으면 머클 경로와 TSA 토큰까지 본다.
   - `verify tenant [--from --to]`: 온라인. 봉인 체인·감사 체인을 처음부터 재계산하고, 저장소 객체를 복호화해 기록 해시와 대조하고, 앵커 레코드와 일치하는지 본다.
   - 감사 발췌는 다른 대상의 행이 빠져 있어 패키지만으로는 체인 연속을 증명하지 못한다. 그 공백을 메우는 것이 앵커다(2번과 함께 결정).
5. **파기의 의미와 규칙 2.**
   - 보존기한이 지난 확인서에 대해 권장 순서:
     1. 문서 키 파기(암호 소거). 산출물과 서명 증거가 함께 무의미해진다.
     2. Object Lock 만료를 확인한 뒤 객체 삭제.
     3. DB 행은 번호·상태·해시·시각만 남는 묘비로 둔다. 체인과 채번의 무결성을 유지하기 위해서다.
   - 문제는 DB에 평문으로 남는 봉인 본문 컬럼이다(추천사유 자유 텍스트 `recommendation.reason_text`, 무효·정정 사유 텍스트). 권장: 이 컬럼들만 전용 롤의 파기 함수로 NULL로 바꿀 수 있게 하는 V9 트리거 예외를 둔다. 파기 감사에는 원문 해시를 남긴다.
   - 이것이 규칙 2("봉인 본문 불변")의 예외인지 판단이 필요하다. 권장 해석은 "파기는 본문을 바꾸는 것이 아니라 보존 의무가 끝난 개인정보를 지우는 것"이다. 다만 규칙 문구에 한 줄 추가가 필요하다.
6. **계약일 앵커(`CONTRACT_DATE`)가 아직 없는 문서의 파기 판정.** 계약 연결은 Phase 6이다. 앵커 일반식은 날짜가 생길 때마다 연장만 하므로, 늦게 온 계약일이 이미 파기된 문서의 기한을 늘릴 수 있다.
   - 권장: 룰 `retentionAnchors`에 날짜가 아직 없는 앵커가 있으면 파기를 미룬다. 룰 파라미터 `contractLinkWaitDays`(완료 후 대기 일수)가 지나면 "계약 없음"으로 확정하고 판정한다.
   - 대안: 파기 판정은 지금 있는 앵커만 본다(계약일이 늦게 오면 이미 파기됐을 수 있다).
7. **법적 보존(소송 보류).**
   - 권장: `legal_hold`(설정·해제를 감사하고, 준법 역할만 다룬다)를 둔다. 보류 중이면 파기 배치가 건너뛴다. 보존기한은 바꾸지 않는다.
   - 저장소 쪽은 S3 Object Lock legal hold API를 쓸지 정해야 한다. SeaweedFS 지원 여부는 계약 테스트(`ArtifactStoreContract`)에 항목을 추가해 확인한다.
8. **만료·무효 문서의 보존 기산점.** 지금 앵커는 봉인·완료·계약일이고, 만료·무효된 문서는 봉인일 기준 그대로다.
   - 권장: 그대로 둔다. 무효·만료 시각은 앵커로 쓰지 않는다.
   - 대안: 룰 앵커 어휘에 `TERMINAL`(무효·만료 시각)을 추가해 분쟁 대응 기간을 늘린다.
