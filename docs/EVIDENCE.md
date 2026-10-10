# 증거 체계 — 확인서 하나의 수명주기

> 처음 보는 엔지니어를 위한 문서다. 데모 확인서 하나(DEMO1 `A-2` — 현장 터치 서명으로 완료된 문서, 허구 데이터)를 초안부터 복구 뒤까지 따라가며 각 단계가
> 무엇을 남기고 무엇이 그것을 검사하는지 적는다. 값은 **2026-10-10 로컬 kind 실행**(`deploy/scripts/kind.sh up → deploy → seed`, 클러스터 안 DB·저장소)의
> 실제 산출물이고 해시는 앞 12자리다. 해시에는 봉인 시각·서명 시각이 들어가므로 다른 실행에서는 값이 다르다 — 같은 것은 **관계**(아래 식)다.
> 설계 정본은 설계서 §6.4(봉인)·§6.5(서명)·§6.7(감사·앵커)·§9(키·보존·파기).

## 0. 한눈에

```
초안 ── 엔진 스냅샷 ── 검증 ── 봉인 ──────────── 서명 3자 ── 완료 ── 앵커(일 1회) ── 영수증 ── (보존기간 뒤) 파기 묘비
                            │                 │                        │
                     canonical JSON(JCS)   signed_doc_hash          머클 잎 → 루트 → TSA 토큰
                     PDF/A-2b              = canonical             
                     chain = H(prev‖c‖p)   signed_pdf_hash = pdf
        모든 상태 전이·검증·서명·열람·내보내기 ── 감사 로그 해시체인(append-only)
검사: verify package(DB·저장소 없이 ZIP + 영수증만) · verify tenant(전 테넌트·한 스냅샷) · 복구 뒤 같은 검사
```

## 1. 초안 → 봉인 직전

설계사가 고객·상담일·상품군으로 초안을 만들면 그 날짜에 시행 중인 룰·서식 버전이 **박제**된다(`rule=DISC-2026-07`, `template=STANDARD@1`). 등급·순위는
엔진 응답을 스냅샷으로 받아 정합성(정수 `rankInSet`·`gradeOrdinal`)만 검사한다 — 이 저장소는 수수료율을 계산하지 않는다. 추천사유는 설계사 입력뿐이다.

이 문서의 감사 행(테넌트 체인 `seq` 38~55): `DISCLOSURE_CREATE` → `DISCLOSURE_TRANSITION` → `DISCLOSURE_VALIDATE` → … → `GRADE_FETCH` →
`EXCEPTION_APPROVE` ×2(관리자 예외 승인) → `DISCLOSURE_SEAL`. 각 행의 `prev_hash`는 바로 앞 행(이 문서의 행이든 다른 문서의 행이든 — 체인은 테넌트 단위)의
`entry_hash`다. 예: `seq 38 entry=e29bb36f800b` → `seq 39 prev=e29bb36f800b`.

## 2. 봉인

| 산출물 | 값 | 무엇을 보장하나 |
|---|---|---|
| 번호 | `DEMO1-2026-000002` | (테넌트, 봉인 연도 KST)별 무간격 카운터 — DB 트리거 GD090 |
| canonical 해시 | `7511d7c81404` | 본문을 RFC 8785(JCS)로 정규화한 바이트의 SHA-256. 이 값이 **문서의 정체**다 |
| PDF 해시 | `923c5ba94c4d` | canonical만으로 렌더한 PDF/A-2b의 SHA-256 — 같은 입력이면 다른 JVM·OS에서도 같은 바이트(골든·두 JVM 재렌더 시험) |
| 렌더러 판 | `2` | 봉인 때 고정, write-once(V23) — 렌더러 1 코드는 동결되어 옛 문서는 옛 판으로 재렌더된다 |
| 체인 | `chain_seq=2`, `chain=853da2794c4a` | `SHA-256(prev ‖ canonical ‖ pdf)`(소문자 hex ASCII를 이어 붙인 바이트), `prev`=직전 봉인의 체인 `6b89ed42ba34` — 앱과 DB(GD095)가 같은 식을 이중으로 강제 |
| 보존기한 | `2031-10-10` | 룰의 보존기간(예시 5년). 연장만 가능(GD094) |

체인을 손으로 다시 계산할 수 있다:

```bash
printf '%s%s%s' <prev 64자> <canonical 64자> <pdf 64자> | shasum -a 256    # → 853da2794c4a…(이 실행)
```

산출물(canonical JSON·PDF·서명본 PDF·증거 ZIP)은 문서마다 새 데이터 키(DEK)로 암호화해 S3 호환 저장소에 올리고, DB 커밋 **뒤에** Object Lock(COMPLIANCE,
retain-until = 보존기한)을 건다. DEK는 테넌트 KEK로 감싸 `document_key`에 둔다(KEK 바이트는 DB에 없다 — 비밀 저장소).

## 3. 서명 — 사람이 아니라 문서 해시에 귀속

| 서명자 | 채널 | `signed_doc_hash` | `signed_pdf_hash` |
|---|---|---|---|
| 고객 | `TOUCH_PAD`(현장 서명 창) | `7511d7c81404` | `923c5ba94c4d` |
| 설계사 | `SSO` | `7511d7c81404` | `923c5ba94c4d` |
| 관리자 | `SSO`(`SSO_APPROVAL`) | `7511d7c81404` | `923c5ba94c4d` |

`signed_doc_hash ≠ canonical_hash`인 서명 행은 DB가 받지 않는다(GD022), PDF 해시도 같다(GD102). 서명 세션은 발급 때 두 해시를 고정하고(GD101), 서명자 집합은
박제된 룰의 `signerSet`이다(GD104). 고객 쪽 감사 행: `SIGN_SESSION_ISSUE` → `SIGN_SESSION_VIEW`(문서를 실제로 본 쪽 기록) → `SIGN_IDENTITY_CHECK` →
`SIGNATURE_CAPTURED`, 마지막 서명 뒤 `DISCLOSURE_COMPLETED`(`seq 111`). 서명본 PDF(`3bdc90e94bf7`)와 증거 ZIP(`2f0de3fc9a1d`)이 함께 남는다.

## 4. 앵커 — 하루 한 번, 모든 테넌트를 한 루트로

| 항목 | 값 |
|---|---|
| 앵커 | DEMO1 `anchor_seq=1`, 날짜 `2026-10-09` |
| 덮는 범위 | 봉인 체인 `seq 4`까지(머리 `a0b231eba20b`), 감사 체인 `seq 138`까지(머리 `c79c493e4d12`) — 이 문서(`chain_seq=2`)를 덮는다 |
| 머클 잎 | `b903cbb726d3` = `SHA-256(0x00 ‖ JCS{anchorDate, anchorSeq, auditHead, auditSeq, sealChainHead, sealChainSeq, tenantId, v:1})` — DB가 다시 계산한다(GD110) |
| 루트 | `04bdc48f12b3`(깊이 16, 잎 위치 1) |
| TSA 토큰 | RFC 3161, 생성 시각 `2026-10-10T14:08:04Z`, 일련번호 `cc3b6b706b72…` |

잎도 손으로 다시 계산할 수 있다(`printf '\x00'` 뒤에 위 JSON을 키 순서대로 공백 없이 — 이 실행에서 `b903cbb726d3`). 데모 장치 하나: 시드가 "이틀 치 앵커"를
보이려고 첫 앵커를 데모 프로파일 전용 시계 오프셋(`-P1D`)으로 만들어 날짜가 하루 앞이다. TSA 토큰 시각은 실제 시각이고 봉인 시각(`14:07:36Z`) 뒤다.

**영수증**은 그 테넌트의 잎·경로·루트·토큰만 담는다(`anchor receipt export` — `covering=1@2026-10-09 links=4`). 다른 테넌트의 데이터는 루트 하나로만 섞인다.

## 5. 독립 검증

`verify package`는 생산자 코드에 의존하지 않는 검증기다(ArchUnit) — DB·저장소 없이 증거 ZIP과 (있으면) 영수증·신뢰 앵커만 읽는다. 이 실행의 클러스터 안 결과
(VERIFY_TENANT CronJob의 파드 틀로 한 번):

```
ARTIFACT_GET DEMO1 … EVIDENCE_ZIP sha256=2f0de3fc9a1d… bytes=132497
VERIFY_PACKAGE MATCH findings=0                                   # ZIP만: 내용·서명 귀속·체인 구간
VERIFY_PACKAGE MATCH findings=0                                   # + 영수증·TSA 신뢰 앵커: 잎 → 경로 → 루트 → 토큰
```

결론의 형태는 "이 문서는 {토큰 시각} 이전에 이 내용으로 존재했다"이다. 법적 효력을 단언하지 않는다(면책).

`verify tenant`는 한 스냅샷에서 감사·봉인 체인, 채번, 저장소 객체(있음·잠금), 앵커·영수증, 살아 있는 모든 키가 테넌트 KEK로 풀리는지를 걷는다 — 이 실행에서 세
테넌트 모두 `MATCH findings=0`. 불일치는 준법 큐의 `CHAIN_BROKEN` 플래그가 되고, 그 플래그는 새 검증 작업 ID를 증거로만 해소된다(E2E가 화면으로 한 번 돈다).

## 6. 파기 — 묘비

보존기간이 끝나면(데모는 데모 전용 번들의 0년 1일 + 데모 시계) 파기 배치가 문서 키 파기 → 객체의 모든 버전·마커 삭제 → 묘비 순으로 진행한다. DEMO3의 예:

| 항목 | 값 |
|---|---|
| 묘비 | `DEMO3-2026-000001`, 상태 `VOID`, canonical `8ae01a6230f8`, 체인 `aea1e125cd7b` — 번호·상태·해시·체인은 남는다 |
| 문서 키 | `DOCUMENT_KEY_SHREDDED` — `wrapped_dek`를 NULL로, 감사에는 지운 값의 SHA-256(`60e727095fca`) |
| 확인서 | `DISCLOSURE_DESTROYED` — `objectsDeleted=2`, 지정 개인정보 컬럼 중 값이 있던 것 0(`erased: []` — 이 문서는 무효 사유 텍스트·청약번호가 비어 있었다) |

DEK가 사라졌으므로 백업에 남은 암호문 사본도 읽을 수 없다(crypto-shredding). 법적 보류가 걸린 문서는 건너뛴다(DB가 통제, 저장소 legal hold는 보조). 지정
컬럼 전수는 설계서의 기계 판독 표 `pii-columns`가 정본이고 시험이 DB 함수·카탈로그와 양방향으로 대조한다.

## 7. 복구 뒤에도 같은가

백업(물리 백업 + 산출물의 모든 버전 복제, 봉투 암호화)을 빈 환경에 복구한 뒤 같은 검사를 다시 돈다 — 표 38개의 내용 해시가 백업 때와 같고, 세 테넌트
`verify tenant` MATCH, 같은 문서의 증거 ZIP이 바이트 단위로 같고, 영수증과 함께 `verify package` MATCH(`RestoreProofIT`, kind는 `kind.sh backup`·`restore`).
같은 kind 클러스터에서 위 값을 뽑은 뒤 백업 → DB 볼륨 삭제·새 버킷 → 복구하고 같은 질의를 다시 돌린 결과: `RESTORE_TABLES MATCH tables=38`, 세 테넌트 MATCH,
이 문서의 번호·해시·체인·서명·산출물 해시·감사 행·앵커·영수증 값과 DEMO3 묘비가 **복구 전과 한 글자도 다르지 않았고**(질의 출력 diff 0), 증거 ZIP은 같은 바이트
(`2f0de3fc9a1d`), `verify package`는 영수증과 함께 다시 `MATCH findings=0`이었다. 절차는 [`operations/backup-restore.md`](operations/backup-restore.md).

## 8. 이 체계가 증명하지 않는 것

- 실 TSA와 연동하지 않았다 — 데모·시험은 스텁 TSA(같은 RFC 3161 형식)이고, 실 사업자 응답 형태는 opt-in 계약 시험(`tsaContractTest`)으로만 본다(§14 #4).
- 감사 로그의 `entry_hash`는 앱이 계산하고 DB는 append-only만 강제한다 — 트리거를 우회한 변조는 `verify tenant`가 재계산으로 찾는다(시험 있음), 쓰는 순간 막지는 않는다.
- 서명은 일반 전자서명 + 증거 패키지다(설계서 D-10). 인정 사업자 서명이 아니다.
