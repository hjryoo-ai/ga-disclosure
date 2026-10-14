# Phase 8 완료 보고 — 배포·운영·정비·포트폴리오 문서

작성 2026-10-14 · 대상 지시문 `docs/phase-08-지시문.md`(v1.0) · 계획 `docs/phase-08-계획.md`(승인 2026-10-10, `docs/phase-08-계획승인.md` — 계획의 "승인 반영"이 본문보다 우선)
- 설계서 v1.18(변경 이력 ①~⑱) · 브랜치 `work/phase-8` · PR [#13](https://github.com/hjryoo-ai/ga-disclosure/pull/13)
- 단계별 수용 회신(10·11·12단계, 2026-10-10~14)은 별도 문서 없이 대화로 받았고, 요구 사항은 각 커밋과 이 보고서에 반영했다.
- **병합은 수용 심사 회신 뒤**에 한다. 태그 `phase-8`은 이 보고서 커밋에 단다.

## 0. Phase 7에서 넘어온 표 (승인 지시 — 보고서 맨 앞)

### Phase 7 보고서 §5(전체) — Phase 8에서 한 것

| # | Phase 7에서 달리 한 것 | Phase 8 결과 |
|---|---|---|
| ① | 멱등 키는 의도마다 무작위 | 그대로. 키 회전 뒤 E2E(클러스터 대상, 12/12)도 같은 재전송 검사를 지났다 |
| ② | 서식 응답 `pinned` 언제나 `true` | 그대로 |
| ③ | 서식 조회 감사 없음 | 그대로 |
| ④ | pdf.js `isEvalSupported` 미사용(옵션 없음, `eval` 0) | 운영 정적 서빙(nginx 웹 이미지)의 CSP가 데모 서빙과 **같은 문자열**임을 `WebImageTest`가 대조 |
| ⑤ | 로그인 새 창 + PKCE 검증자 메모리 | 설계서 §8에 "같은 창 리다이렉트 + `sessionStorage` 검증자는 채택하지 않음"과 이유, 운영 문서 `ingress.md`에 팝업 허용 전제(7 심사 §2) |
| ⑥ | 스크롤 끝 전 서명 버튼을 막지 않음 | 그대로 |
| ⑦ | 사유 코드 자유 입력 | **룰 어휘 API로 닫음**(`GET /api/v1/rules/vocabulary`, G9) — 선택 입력, 서버 거부 불변 |
| ⑧~⑫ | 자동 회색화 없음·라디오 묶음·텍스트 날짜·공개 서명 라벨 없음·"징구율" | 그대로(⑨ 어휘 선택도 같은 `Choice` 부품) |

### Phase 7 넓힘·계약 추가 표 — Phase 8 영향

Q1(`FLAG_READ` AGENT OWN)·Q3(서식 조회 경로)·미리보기·Q5(룰 키 `preview.watermark`)·Q6(데모 OIDC 계약) 모두 변화 없음. 환급금 표의 `render.columns`는 서식 응답에
나가지 않는다(라벨 집합만). 데모 OIDC 경로는 kind 데모 오버레이에만 있고 운영 오버레이에는 없다.

### 6B 이월분(계획 ②) — 결과

| # | 내용 | 결과 |
|---|---|---|
| 6B-a | 룰 없는 테넌트의 쓰기 POST가 500 | `/api`·`/internal` 503 `TENANT_RULES_NOT_ACTIVE`(계약 추가), `/public`은 같은 404 — `TenantRulesNotActiveIT`(주입 P8-13), 온보딩 런북 |
| 6B-b | 게이트 분당 한도가 인스턴스 메모리 | 감사 행 DB 집계 + 주체별 advisory 잠금 — `GateApiIT.theLimitIsOneLimitAcrossTwoInstances`(주입 P8-14: 6B 방식이면 한도의 2배가 통과). **인그레스 주체별 한도(계획의 "벨트")는 하지 않았다** — §5 ⑯ |
| 6B-c | 정정의 행위자·재배정(§14 #20) | 열어 둠(승인) |
| 6B D-2 | 간헐 실패 원인 미증명(연결 재사용 가설) | Phase 7·8 전 실행(로컬 전체 빌드 여러 번, kind·복구 증명·E2E)에서 재발 0 — **"미증명·재발 0"으로 닫는다** |
| 6B D-6 잔여 | Playwright `error-context.md`의 입력값 | 실패 시 입력 칸 값 지움 + 산출물 전체 센티널 스캔(7a, 주입 P8-20a·b) |

## 요약

- **비밀·키(지시문 §2, G3·G5)**: 모든 서버 비밀이 `SecretSource`(파일·환경변수·외부 저장소 어댑터)의 **이름**으로만 온다 — 테넌트 KEK·커서·요청 해시·영수증·데모 OIDC·TSA 신뢰 앵커·백업 키·엔진 토큰. 앱은 비밀을 만들지 않는다(`secrets init`·`crypto kek init`). **테넌트 KEK가 처음으로 참이 됐다**(승인 Q2): `tenant_kek` 레지스트리(V21), 이중 읽기 → `KEK_REWRAP`(행마다 감사·dry-run 기본·멱등) → 전역 키 경로 제거(1b, `GlobalKekPathScanTest`).
- **2단 업그레이드 실측(11단계 회신 ①)**: Phase 7 판 시드(전역 KEK) → 1a 재래핑 DEMO1 15·DEMO2 8·DEMO3 6(두 번째 0) → HEAD에서 전역 키 파일 없이 세 테넌트 MATCH, 레지스트리 밖 KEK로 감싼 살아 있는 키 29 → 0(§4.3).
- **키 회전 여섯 종(G5)**: kind에서 커서·요청 해시·영수증·데모 OIDC·테넌트 KEK(DEMO1 17건 재래핑, 두 번째 0)·TSA 신뢰 앵커 집합을 각 1회 — 키마다 "회전 전에 만든 것"의 영향 단언, 재기동마다 5xx 0, 뒤에 세 테넌트 MATCH·클러스터 대상 E2E 12/12(§4.2).
- **백업·복구(G2)**: 물리 백업(`disclosure_backup` — REPLICATION, 표 권한 0) + 산출물 모든 버전 복제, 백업 키 봉투, Object Lock 백업 저장소. `RestoreProofIT`(음성 대조 포함)와 kind 복구 — 표 38개 내용 해시 일치, 세 테넌트 MATCH, 같은 문서 증거 ZIP 바이트 동일(§4.4).
- **배포 묶음(지시문 §1, G3·G4·G6~G8)**: 이미지 2개(digest 고정 베이스, 비루트, 읽기 전용 루트, 레이어 전수 스캔), Kustomize 기반·운영·kind 오버레이, Traefik 두 컨트롤러(공개 A·B / 내부 C mTLS), `/internal` 3겹(라우트 없음·포트·NetworkPolicy), CronJob 15종(KST·Forbid + 앱 잠금), 마이그레이션 선행 Job + 스키마 버전 가드, 운영 필수 키 가드(키 이름만), 헬스 전용 롤, 미터 라벨 닫힌 목록, 배포 전 로그 스캔.
- **정비(지시문 §3, G9~G11)**: 룰 어휘 API, 환급금 표(렌더러 2판 — 렌더러 버전을 봉인 때 고정, 기존 골든 불변), 목록 인덱스 V24(10만 행 실측), 화면 폰트 상한, E2E 실패 스냅샷 마스킹.
- **포트폴리오 문서(지시문 §4, G12)**: README(알려진 한계 첫 절·CI 숫자·그림·15분 둘러보기), ARCHITECTURE·EVIDENCE·DECISIONS·운영 런북 7종, 문서 검사 `PortfolioDocsTest`. 맥락 없는 에이전트가 README만으로 세 테넌트 MATCH까지(§4.1).
- **kind가 찾은 배포 결함 13건**(KIND-1~13) — 그중 KIND-10(분리 호스트에서 현장 서명 불가 → 계약 2.12.0 `signUrl`, 사후 승인)·KIND-12(`.mjs` MIME)는 Phase 7까지의 시험(같은 출처)으로는 드러나지 않았다.
- **테스트**: JVM 13,887건(`build` 13,835 + `deployTest` 47 + `imageTest` 5, 실패 0·스킵 0) + 화면 단위 27 + E2E 12(격리 컨테이너) + 12(kind 클러스터 대상) · 위반 주입 **63건**(ID 단위) 전부 의도한 방어선에서 실패 확인 · 보안 검토 발견 7건(SEC-1~7) 수정.
- **CI**: PR #13 run [`38517344797`](https://github.com/hjryoo-ai/ga-disclosure/actions/runs/38517344797)(head `0e0d393`) — 일곱 잡(`build`·`e2e`·`kind`·`deploy-lint`·`images`·`pdfa-verify`·`no-docker`) 전부 success. `kind` 잡이 배포 → 스모크 → 시드 → 백업·복구 → 스모크 → 키 회전 여섯 종 → 클러스터 대상 E2E → 로그 스캔을 20분 안에 끝냈다. CI 테스트 수는 로컬과 모듈별로 같다. 이력: 앞선 PR run [`38510785852`](https://github.com/hjryoo-ai/ga-disclosure/actions/runs/38510785852)(`a0c1833`) success, 보고서 1판의 태그 push run [`38512687590`](https://github.com/hjryoo-ai/ga-disclosure/actions/runs/38512687590) **kind 잡 실패** → 원인 BUG-5(앱)·BUG-6(E2E 경합), `0e0d393`로 수정(D-8).

## 1. 커밋

| 커밋 | 단계 | 내용 |
|---|---|---|
| `c304947` | — | Phase 7 수용심사·Phase 8 지시문·계획 |
| `6b839f2` | — | 계획 승인·계획 갱신(테넌트 KEK·백업 롤·렌더러 고정·ingress-nginx 상태 출처) |
| `270e18d` | 1a | 테넌트 KEK 레지스트리(V21)·이중 읽기·`KEK_REWRAP`(승인 Q2) |
| `9857b5b` | 1b | 전역 KEK 읽기 경로 제거, `verify tenant`의 KEK 검사 |
| `5f278dc` | 1c | 모든 서버 비밀을 `SecretSource`로, `secrets init` |
| `e1616ff` | 2 | 헬스·백업 롤(V22), 앱 기동 마이그레이션 제거 → `db migrate` + 스키마 버전 가드, `DatabaseHealthIndicator`, 관리 포트 |
| `ea5e392` | 3 | 운영 기동 가드·미터·`/internal` 별도 포트·503(6B-a)·게이트 DB 집계(6B-b) |
| `efae5e8` | 3 뒤 | 보안: 포트·접두 판정을 라우팅 파서로(SEC-1), prod+demo 거부(SEC-2) |
| `2f96a39` | 3 뒤 | 화면 시험의 코드 수 17(REG-1) |
| `a85365f` | 5 | 렌더러 판 고정(V23)·환급금 표 렌더러 2·서식 STANDARD-v2·매니페스트 2판 |
| `d912662` | 6 | 룰 어휘 API·화면 선택 입력 |
| `56459d0` | 7a | E2E 실패 스냅샷 마스킹·산출물 스캔·HTML 보고서 제거(FIND-1) |
| `c2545dc` | 7b | 화면 폰트 바이트 상한(서브셋은 측정 뒤 안 함) |
| `02d6240` | 7c | 목록 정렬 인덱스(V24)·실측 도구·플래너 시험 |
| `ebbb2d5` | 8 | 이미지 2개·레이어 스캔·PostgreSQL digest 고정 |
| `8c269f8` | 9a | `jobs run <KIND>` CLI |
| `ab19106` | 9b | 쿠버네티스 기반·Traefik·ESO·운영 오버레이·deploy 린트 |
| `7d9faf1` | 9b 뒤 | 보안: 컨트롤러 네임스페이스 Role만·내부 mTLS 기본 옵션·원 IP 보존(SEC-3~5) |
| `b921daf` | 9c | kind 데모 오버레이·`kind.sh`·CI kind 잡 |
| `af04b22` | 10a-c | 봉인된 물리 백업·객체 모든 버전 복제·백업 저장소·`RestoreProofIT` |
| `7ef5d18` | 10 뒤 | 보안: 소유자 전용 파일을 한 번의 CREATE_NEW로(SEC-6 TOCTOU) |
| `b1bf1ee` | 10d | 백업 CronJob·kind 복구 오버레이·`kind.sh` seed/verify/backup/restore |
| `76ae134` | 10 수용 | 운영자 파일 쓰기 전부 소유자 전용 생성으로(ArchUnit), 백업 보류 문장, 온보딩 런북 |
| `1e517cb` | 11 | 키 회전 여섯 종·클러스터 대상 E2E·발견 결함(KIND-10~13, ENV-1) |
| `9549c9d` | 12 | 포트폴리오·운영 문서, 문서 검사, 2단 업그레이드 실측 |
| `2ded5b2` | 12 | 맥락 없는 재현 기록, README가 추측하게 한 6곳 |
| `a0c1833` | 13 | 시험 입력 선언 대조(`verifyTestInputs`)·CLAUDE.md 규약 한 줄·주입 세는 단위 |
| `f3a14d3` | 13 | 보고서 1판(태그 `phase-8` 첫 위치) — 태그 push CI의 kind 잡이 실패(BUG-5·6) |
| `0e0d393` | 13 | 공개 본인확인 마지막 실패의 응답·E2E 경합 수정(BUG-5·6) |
| (이 커밋) | 13 | 보고서 |

## 2. 완료 기준

| # | 기준 | 증거 |
|---|---|---|
| G1 | 빈 kind 클러스터에서 README만 보고 배포 → 데모 → `verify tenant` MATCH | 스크립트 `deploy/scripts/kind.sh`(up·deploy·smoke·seed·verify), CI 잡 `kind`. **맥락 없는 에이전트 재현**(README만, 새 클론 `9549c9d`): 세 테넌트 MATCH(§4.1). 사람 재현은 승인 Q14대로 심사와 별개 |
| G2 | 복구 증명 | `RestoreProofIT`(백업 → 빈 환경 복구 → 세 테넌트 MATCH·영수증 `verify package` MATCH·`artifacts reconcile` 실패 0·증거 ZIP 바이트 동일, 음성 대조: 객체 하나 뺀 복구 → 그 키만 `OBJECT_MISSING`), kind `backup`·`restore`(§4.4) |
| G3 | 이미지·저장소·로그에 비밀 0, 필수 키 누락 시 기동 실패(키 이름만) | `ImageLayerScanTest`(두 이미지 레이어 전수 — P8-23, BUG-2 대조), `kind.sh logscan`(모든 파드·Job 로그, 바늘 98 — 회전한 옛 키 포함, 대조 P8-43), `ProdStartupGuardIT`·`ProdProfileYamlTest`(P8-10·P8-30·P8-31), `GlobalKekPathScanTest` |
| G4 | `/internal` 외부 거부, `/public` 별도 호스트·IP 한도·크기 한도, 게이트 mTLS 종단 | `kind.sh smoke`(16 단언: NetworkPolicy 4·A 4·B 4·평문 1·C 3), `DeployRulesTest`(P8-25·25b·25c·26·35~39), `PortSeparationIT`(P8-12·SEC-1) |
| G5 | 키 5종 회전 각 1회 뒤 MATCH·E2E, `KEK_REWRAP` 멱등·감사 | `kind.sh rotate all`(여섯 종 — §4.2), `TenantKekIT`(P8-1·P8-2), 2단 업그레이드(§4.3), 회전 뒤 클러스터 대상 E2E 12/12 |
| G6 | CronJob 겹침 금지 + 앱 잠금 2중, 종류마다 매니페스트 1개, KST | `CronJobManifestTest`(JobKind ↔ CronJob 양방향, KST·Forbid — P8-24·P8-29), `JobRunnerIT.theSameLockKeyNeverRunsTwice`, 운영 문서 `schedule.md` |
| G7 | `DatabaseHealthIndicator` 허용 목록·테넌트 데이터 미접근, 준비성/활성 분리 | `ArchitectureRulesTest.dbAccessOnlyViaTenantScopedRepository`(P8-9), `V22RolesIT`(P8-5·P8-6), `HealthEndpointsIT`(P8-7) |
| G8 | 미터 라벨 닫힌 목록, 로그 누출 스캔 배포 전 | `MetricsIT`(P8-11), CI `kind` 잡의 `logscan`(E2E·회전 뒤 — 0) |
| G9 | 룰 어휘 API 닫힌 목록, 화면이 쓰고 서버 거부 불변 | `RuleVocabularyApiIT`(P8-18b), `ContractSchemaTest.theRuleVocabularyCarriesNoNumberOrBooleanAnywhere`(P8-18a), `reasons.test.tsx`(P8-19), E2E 직접 요청 422 |
| G10 | 환급금 표 가독 렌더, 기존 골든 불변, 기존 문서 재렌더 바이트 동일 | `TemplateVersionsIT`((서식 v1·v2 × 렌더러 1·2) 허용 3·거부 1 — P8-16a·b·P8-17), `SealGoldenTest`(기존 골든 diff 0, 새 case-04만), 계약 시험(P8-15) |
| G11 | 인덱스 실측·플래너 사용, 폰트 상한, 스냅샷 마스킹 | `V24ListIndexIT`(P8-22, 10만 행 실측표 — 7c 커밋), `scan-dist.mjs` 폰트 상한(P8-21), 마스킹(P8-20a·b) |
| G12 | 포트폴리오 문서: 숫자 CI 출처, 한계 첫 화면, 15분 둘러보기 재현 | README 숫자 표(각 run의 `test-reports`와 대조), `PortfolioDocsTest`(첫 절 = 알려진 한계 — P8-55·P8-56), 재현 기록(§4.1) |
| G13 | Phase 0~7 무손상, 평문·jqwik·BOM, 최소 주입 8종 | 전체 빌드(§3), `scanPlaintextLeaks`, jqwik 차단 CI 단계, 최소 주입: 이미지 키 파일 P8-23 · 필수 키 기본값 P8-10 · `/internal` 공개 노출 P8-25 · CronJob `Allow` P8-24 · 헬스가 테넌트 표 P8-7 · 미터 라벨에 확인서 번호 P8-11 · 어휘 API 임계치 P8-18a·b · 렌더러 버전 미고정 P8-16a·b |

## 3. 테스트와 주입

### 테스트 수

로컬 강제 재실행(13단계, `clean build :disclosure-app:deployTest :disclosure-app:imageTest` — 통합 테스트 포함 전부 실행, 2026-10-14) 실패 0·스킵 0.
CI run [`38517344797`](https://github.com/hjryoo-ai/ga-disclosure/actions/runs/38517344797)의 `test-reports`(Gradle 보고서)·`deploy-lint-reports`·`image-test-reports`·vitest·E2E JUnit과 모듈별로 같다(아래 표의 로컬 수 = CI 수).

| 모듈·소스셋 | 로컬 | Phase 7 대비 |
|---|---:|---|
| platform-core `test` | 3,067 | 같음 |
| platform-canonical `test` | 1,054 | 같음 |
| platform-spring `test` | 12 | 같음 |
| disclosure-domain `test` | 1,094 | 같음 |
| disclosure-rules `test` | 2,473 | +6(2,467) |
| disclosure-seal `test` | 70 | +5(65) |
| disclosure-sign `test` | 515 | 같음 |
| disclosure-audit `test` | 142 | +1(141) |
| disclosure-workflow `test` | 1,372 | 같음 |
| disclosure-infra `test` | 20 | 신규(Phase 7 0 — 백업 봉투·스키마 버전 단위 시험) |
| disclosure-infra `integrationTest` | 3,781 | +47(3,734) |
| disclosure-app `archTest` | 81 | +12(69) |
| disclosure-app `integrationTest` | 154 | +55(99) |
| disclosure-app `deployTest`(Phase 8 신설) | 47 | 신규 |
| disclosure-app `imageTest`(Phase 8 신설, `check` 밖 — CI 잡 images) | 5 | 신규 |
| **JVM 합계** | **13,887** | `build`만(deployTest·imageTest 제외)은 13,835 — Phase 7 `build` 13,689 대비 +146 |
| 화면 단위·컴포넌트·스캔(vitest) | 27 | Phase 7 24 |
| E2E(Playwright, 데스크톱·모바일) | 12 | 같음 — 격리 컨테이너 + kind 클러스터 대상(`e2eKind`) 두 모드 |

같은 실행의 `scanPlaintextLeaks` 결과 파일 247개·금지 문자열 14개·0건, `verifyTestInputs` 시험 태스크 12개 전부 선언.

### 위반 주입 기록 (주입 → 실패 확인 → 제거)

세는 단위는 주입 ID 하나(13단계 규약 — README). Phase 8: **63건**, 첫 시도에 의도한 방어선이 못 잡은 것 3건(P8-16b·P8-25 — 시험·오버레이 결함, P8-55 — 빌드 입력, 모두 원인을 고친 뒤 재주입으로 실패 확인).

| ID | 단계 | 주입 | 잡은 것 |
|---|---|---|---|
| P8-1 | 1a | (B1) 재래핑이 행 감사 없이 진행 | `TenantKekIT`(감사 3행 단언) |
| P8-2 | 1a | (B1) 이행 중 이중 읽기 제거 | `TenantKekIT` 2건(옛 문서·고객·보고서 열람) |
| P8-3 | 1b | (B1) 전역 KEK 설정 키를 남김 | `GlobalKekPathScanTest.noCodeConfigOrScriptReachesTheGlobalEraKek` |
| P8-4 | 1b | 재래핑 함수를 다른 저장소에서 호출 | `SealWriteScanTest`(호출 지점 닫힌 목록) |
| P8-5 | 2 | (B2) 백업 롤에 표 SELECT(실행 시점) | `V22RolesIT` 표 권한 단언 |
| P8-6 | 2 | (B2) 같은 GRANT를 마이그레이션 끝에 | V22 단언 — 하네스 기동 실패 |
| P8-7 | 2 | (G13) 헬스가 테넌트 표 조회 | `HealthEndpointsIT` 2건(헬스 롤 권한 없음 → 준비성 503) |
| P8-8 | 2 | Flyway를 `SchemaMigrator` 밖에서 참조 | `ArchitectureRulesTest.flywayOnlyInTheMigrator` |
| P8-9 | 2 | 헬스 지표를 허용 목록에서 뺌 | `dbAccessOnlyViaTenantScopedRepository`·`allowlistsHaveNoStaleEntries` |
| P8-10 | 3 | (G13) 운영 필수 키에 기본값 | `ProdProfileYamlTest.everyRequiredKeyIsAPlaceholderWithoutDefault` |
| P8-11 | 3 | (G13) 미터 라벨에 확인서 번호 | `MetricsIT`(미터 필터가 등록 거부 → 계열 없음) |
| P8-12 | 3 | 앱 포트에서 `PortChannelFilter` 제거 | `PortSeparationIT.eachPrefixAnswersOnlyOnItsPort` |
| P8-13 | 3 | (6B-a) 룰 없는 테넌트 가로채기 제거 | `TenantRulesNotActiveIT` — 6B 결함 그대로 재현(500) |
| P8-14 | 3 | (6B-b) 게이트 한도를 인스턴스 메모리로 | `GateApiIT.theLimitIsOneLimitAcrossTwoInstances`(한도의 2배 통과) |
| P8-15 | 5 | 서식 계약 `columns` ⇒ `unavailableText` 조건 제거 | `ContractSchemaTest`(1차 주입은 불충실 → 폐기, 재주입) |
| P8-16a | 5 | (G13) 완료가 저장된 판 대신 현재 판 | DB GD093 → `TemplateVersionsIT` |
| P8-16b | 5 | (G13) 서명본 덧붙임만 현재 판 | 1차 주입은 **통과(시험 결함)** → 시험을 XMP 대조로 강화 → 실패 |
| P8-17 | 5 | 운영 배선이 판 지정 생성자로 V1 | `ArchitectureRulesTest.productionSealsOnlyWithTheCurrentRendererVersion`(거짓 양성은 코드를 옮겨 해소) |
| P8-18a | 6 | (G13) 어휘 계약에 정수 임계치 | `ContractSchemaTest.theRuleVocabularyCarriesNoNumberOrBooleanAnywhere` |
| P8-18b | 6 | (G13) 응답 DTO에 임계치 | 모든 IT의 응답 계약 검증 — `RuleVocabularyApiIT` |
| P8-19 | 6 | (규칙 7) 사유 체크 상자 사전 체크 | `reasons.test.tsx` 2건 |
| P8-20a | 7 | 센티널 입력 직후 일부러 실패(마스킹 켬) | 실패 1, `error-context.md` 입력 칸 값 없음, 스캔 0 |
| P8-20b | 7 | 같은 시험 + 마스킹 끔 | 산출물 스캔 3건 → `run.mjs` 실패 |
| P8-21 | 7 | 폰트 상한을 측정값 − 1 | `scan-dist.mjs` → webBuild 실패 |
| P8-22 | 7 | V24 플래그 큐 인덱스 제거 | `V24ListIndexIT` 2건 |
| P8-23 | 8 | (G13) 이미지에 개인키 파일 | `ImageLayerScanTest` |
| P8-24 | 9b | (G13) CronJob `Allow` | `CronJobManifestTest` |
| P8-25 | 9b | (G13) 공개 인그레스에 `/internal` | `DeployRulesTest` 2건. **1차 주입에서 발견**: 운영 오버레이의 번호 기반 패치가 주입을 지워 서비스 검사로만 잡힘 → 무의미한 패치 삭제 |
| P8-25b | 9b | `/api/` 라우트에 `|| PathPrefix(/internal/)` | `DeployRulesTest` |
| P8-25c | 9b | 8081 NetworkPolicy 출발지를 공개 컨트롤러로 | `networkPoliciesAdmitEachAppPortFromItsEntryPointOnly` |
| P8-26 | 9b | 서명 라우트에서 헤더 지움 미들웨어 제거 | `DeployRulesTest` |
| P8-27 | 9b | Traefik 이미지 digest 제거 | `DeployRulesTest` |
| P8-28 | 9b | 모르는 필드 `replica` | `KubeconformTest`(strict) |
| P8-29 | 9b | CronJob 종류 라벨 변경 | `CronJobManifestTest`(종류 집합 불일치) |
| P8-30 | 9b | 운영 ConfigMap에서 `GA_TSA_URL` 제거 | `DeployRulesTest`(운영 가드를 렌더된 파드 환경으로) |
| P8-31 | 9b | ESO 키 이름 오타 | 같은 시험 |
| P8-32 | 9b | 앱에 마이그레이터 자격 증명 | `DeployRulesTest` |
| P8-33 | 9b | 저장소의 Traefik 사본 편집 | `vendoredFilesMatchTheirLockedHashes` |
| P8-34 | 9b | 비밀 볼륨 item 경로 불일치 | `DeployRulesTest`(15 CronJob) |
| P8-35~P8-39 | 9b 뒤 | SEC-3~5 회귀(내부 TLSOption 이름, 공개 컨트롤러가 앱 네임스페이스 감시, ClusterRole, `externalTrafficPolicy` 제거, ExternalName이 내부 서비스) | `DeployRulesTest` 각 1 |
| P8-40 | 9b 뒤 | `tools.lock`에 없는 상류 사본 | `vendoredFilesMatchTheirLockedHashes` |
| P8-41 | 9c | kind PostgreSQL에서 namespace 제거(KIND-1 회귀) | `everyNamespacedResourceNamesOneOfOurNamespaces` |
| P8-42 | 9c | kind.sh Secret 이름 불일치 | `everyReferencedSecretAndConfigMapHasASource` |
| P8-43 | 9c | 로그 스캔 대조: 비밀을 출력하는 파드 | `LOGSCAN hits=1` → 0 |
| P8-44 | 10 | (B2) 백업 롤에 SELECT(V25 임시) | `V22RolesIT` |
| P8-45 | 10 | (B2) 백업 암호화 생략 | `RestoreProofIT` 평문 스캔 |
| P8-46 | 10 | 객체 복제가 법적 보류를 옮기지 않음 | `RestoreProofIT`·`ObjectVersionCopierIT` |
| P8-47 | 10 뒤 | SEC-6 회귀: `Files.createFile` | `ArchitectureRulesTest.filesAreCreatedAndOpenedInOneStep` |
| P8-48·49 | 10d | 운영 DB 연결 `sslmode` 약화(백업 `prefer`, JDBC `verify-full` 제거) | `everyDatabaseConnectionVerifiesTheServerInProd` |
| P8-50·51 | 10 수용 | 운영자 파일 쓰기를 `Files.write`·`new FileOutputStream`으로 | `fileWritesGoThroughOwnerOnlyCreation` |
| P8-52 | 11 | preStop 10초 → 1초 | `theAppDrainsBeforeItStopsAndNeverDropsBelowItsReplicas`(오버레이 3개) |
| P8-53 | 11 | SeaweedFS 설정 파일 모드 제거 + umask 077 데몬 | `SeaweedArtifactStoreIT` 18/18 실패 |
| P8-54 | 11 | 현장 서명 창을 상대 경로로(웹 이미지 재적재) | kind E2E TOUCH_PAD 흐름 데스크톱·모바일 실패 → 원복 12/12 |
| P8-55 | 12 | README에 금지 형용사 | `PortfolioDocsTest` — **1차 시도는 "최신"으로 건너뛰어 통과(BUG-3)** |
| P8-56 | 12 | 문서의 시험 메서드 이름을 낡게 | `PortfolioDocsTest` |
| P8-57 | 13 | 시험 입력 선언 한 줄 제거 | `verifyTestInputs` |
| P8-58 | 13 | 재현 장치: 원격 링크 시험에서 공개 본인확인 응답을 1.5초 늦춤 | 수정 전 E2E 도우미 → CI와 같은 실패(데스크톱·모바일), 응답을 기다리는 도우미 → 마지막 실패의 `passed:true`가 드러남, 두 수정 뒤 12/12 — 장치 제거 |

### 결함·발견 기록 (주입 아님)

| ID | 무엇 | 어떻게 찾았나 | 처리 |
|---|---|---|---|
| SEC-1 | `PortChannelFilter`가 원 URI 접두로 판정 — `/%69nternal`이 앱 포트에서 200 | 커밋 뒤 보안 검토 | 라우팅 파서(`PathPatternRequestMatcher`), 회귀 시험이 수정 전 코드에서 실패 |
| SEC-2 | `prod`+`demo` 동시 활성 | 자체 점검 | `ProdStartupGuardIT.theDemoProfileCannotRideAlongWithProd` |
| SEC-3 | 내부 라우트 TLS 옵션이 SNI로 기본 옵션에 떨어질 수 있음(mTLS 우회) | 보안 검토 | 내부 네임스페이스의 `default` 옵션 = 인증서 필수, 라우트별 옵션 금지 |
| SEC-4 | Traefik이 모든 네임스페이스 Secret 읽기 | 보안 검토 | 컨트롤러마다 자기 네임스페이스 Role만, 앱은 ExternalName |
| SEC-5 | SNAT로 IP 한도를 모두가 나눠 씀 | 보안 검토 | `externalTrafficPolicy: Local` |
| SEC-6 | 소유자 전용 파일 생성 TOCTOU(심볼릭 링크 경합) | 보안 검토 | CREATE_NEW 한 번 + ArchUnit |
| SEC-7 | 백업 복제 연결이 `prefer`(앱 JDBC는 `verify-full`) — 형제 비대칭 | 배경 보안 검토 | `verify-full` + 채널 바인딩 + 린트 |
| KIND-1~5 | namespace 누락, Traefik nodes ClusterRole 필요, LibreSSL v1 CA·SHA-1, IP 한도가 복제본마다, 평문 = Traefik 404 | 첫 kind 배포 | 각 수정 + 린트·문서 |
| KIND-6 | DEMO3 KEK가 Secret 뒤에 생겨 verify 실패 | kind | 온보딩 순서 일반화(KEK 먼저) — `tenant-onboarding.md` |
| KIND-7 | 끝난 Job 파드 틀 불변 → 재적용 멈춤 | kind | 지우고 다시(대안: 설정 해시를 Job 이름에 — 채택 안 함, D) |
| KIND-8 | 중단된 복구 시도 위의 재시도 | kind | 같은 모양이면 `alreadyPresent=true`, 다르면 거부 — 실행마다 새 버킷 |
| KIND-9 | 스모크 "000" 검사가 포트 포워드가 죽어도 통과(공회전) | 간헐 실패 | TLS 실패 종료 코드로 단언 |
| KIND-10 | 현장 서명 창이 상대 경로 — 분리 호스트에서 서명 불가 | 클러스터 대상 E2E | 계약 2.12.0 `signUrl`(승인 전 변경 → **사후 승인**, D) |
| KIND-11 | 롤링 재기동 직후 502 | 키 회전 첫 실행 | preStop·`maxUnavailable: 0`·유예 + 린트·재기동마다 5xx 계수 |
| KIND-12 | nginx가 `.mjs`를 `octet-stream` + `nosniff`로 → PDF 화면 빈칸 | 클러스터 대상 E2E | 자산 형식 닫힌 표 + 이미지 시험 전수 |
| KIND-13 | 스텁 신뢰 앵커 = 자기 인증서 → TSA 회전 뒤 옛 앵커 `TSA_UNTRUSTED` | 회전 뒤 E2E | 비밀 앵커 집합이 모드와 무관하게 우선 |
| ENV-1 | umask 077 Gradle 데몬 → 컨테이너 설정 파일 0600 → SeaweedFS 종료 | 전체 빌드 대량 실패(첫 가설 틀림 → 되돌림) | 하네스 파일 모드 0644, `kind.sh`의 Gradle은 umask 022 |
| FIND-1 | Playwright HTML 보고서가 입력값(허구 센티널)을 단계 제목에 실음 — Phase 7부터 CI 산출물 | 새 산출물 스캔 첫 실행 | HTML 보고서 끔 |
| FIND-2 | Ubuntu JRE 베이스의 GnuTLS 시험용 개인키 | 첫 레이어 스캔 | 규칙을 좁히지 않고 베이스를 Alpine으로 |
| FIND-3 | 결정 기록 초안의 줄 인용이 파일 끝 밖 | 문서 검사 첫 실행 | 수정 |
| FIND-4 | 둘러보기의 준법 계정이 `seed`만으로는 없음 | kind 실측 | `seed`가 6A 주체도 |
| FIND-5 | 승인 문서의 "Phase 2부터 테넌트 KEK" — 태그별 설계서로는 3B(v1.8)부터 | 출처 대조 | DECISIONS에 기록(승인자 정정 수용) |
| FIND-6 | 시험 일곱 태스크가 소스셋 밖 파일 29건을 선언 없이 읽음 | `verifyTestInputs` 첫 실행 | 전부 선언 |
| BUG-2 | 레이어 스캐너가 512바이트 미만 레이어를 건너뜀 | 스캐너 대조 시험 | 크기 검사 제거 |
| BUG-3 | archTest가 저장소 전체 스캔인데 입력 미선언 → 주입이 "최신"으로 통과(세 번째 같은 패턴) | P8-55 1차 시도 | 매번 실행 + **CLAUDE.md 규약 한 줄**(12단계 회신 승인) + `verifyTestInputs` |
| BUG-4 | `verifyTestInputs` 자체: 주석 제거 정규식이 첫 `//`부터 파일 끝까지 지움(미보고), 최상위 파일 이름을 디렉터리로 취급 | 알려진 읽기(`init-roles.sql`)가 안 잡힌 것을 의심 | 정규식 분리·규칙 수정(18 → 29건) |
| REG-1 | 3단계가 계약 코드를 더하고 화면 시험의 크기 고정을 안 고침 | 5단계 전체 실행 | 수정 — 단계마다 `build` 전체 |
| BUG-5 | **공개 본인확인: 한도에 닿아 세션이 닫히는 마지막 실패가 `{"missing":[],"passed":true}`** — 유스케이스가 닫힌 세션의 빠진 수단을 빈 목록으로 돌려주고 공개 매퍼가 "빠진 것 없음 = 통과"로 읽었다(유스케이스 Phase 4 `62c41d8`부터, 공개 응답 6A부터). 세션은 실제로 닫혀 서명으로 이어지지 않지만 고객에게 틀린 결과를 알리고 마지막 시도만 다른 응답이었다 | 태그 push run `38512687590`의 kind E2E 간헐 실패 → 재현 장치(P8-58) | `0e0d393`: 빠진 수단 유지 + 공개 매퍼는 닫힌 세션을 통과로 내지 않음. 회귀: `PublicSignUniformResponseIT`(다섯 응답 같은 바이트 — 이 시험은 응답을 보내기만 하고 읽지 않았다), `SignSessionIT`(닫힌 결과에도 BIRTH_DATE) — 둘 다 수정 전 코드에서 실패 |
| BUG-6 | E2E `verifyBirthDate`가 안내 문구만 대조 — 두 번째 실패부터 직전 "실패" 문구가 남아 있어 자기 응답을 보지 않고 통과, 다음 단계가 진행 중 요청을 끊어 실패 횟수가 모자람(간헐) — **이 경합이 BUG-5를 가렸다**(Phase 7 D-5·KIND-9와 같은 "통과할 때만 안전한 검사") | 같은 태그 run | 응답을 기다린 뒤 문구 대조 |

### CI (1차 증거)

PR #13 run [`38517344797`](https://github.com/hjryoo-ai/ga-disclosure/actions/runs/38517344797) — 이벤트 `pull_request`, head `0e0d393`, 결론 success. 수치는 이 run의 아티팩트를 새 디렉터리에 받아 센 것이다.

| 잡 | 결론 | 시간 | 아티팩트에서 본 것 |
|---|---|---|---|
| `build` | success | 11분 14초 | JVM 13,835(모듈 13개 — 로컬과 같음), 실패 0·스킵 0, 화면 단위(vitest) 27 |
| `e2e` | success | 5분 43초 | Playwright 12(실패 0), 산출물 스캔 39파일 0건, 재전송 61 → 재생 59 + 재생 불가 2, 테이블 38개 행 수 그대로 |
| `kind` | success | 19분 42초 | `SMOKE failures=0`(복구 전·후 두 번), 시드 `SEED_LINES 44`·세 테넌트 MATCH, 백업 `keys=59 versions=59 held=2` → 복구 `RESTORE_TABLES MATCH tables=38`·세 테넌트 MATCH, 회전 PASS 32줄·FAIL 0(재기동 다섯 번 각 141~143요청 5xx 0, KEK `rewrapped=17` → 두 번째 0, TSA 대조 `TSA_UNTRUSTED` 의도한 MISMATCH), 회전 뒤 세 테넌트 MATCH, 클러스터 대상 E2E 12(실패 0, 산출물 스캔 0), `LOGSCAN files=26 needles=98 hits=0` |
| `deploy-lint` | success | 1분 28초 | deploy 시험 47(실패 0) |
| `images` | success | 2분 8초 | 이미지 시험 5(실패 0 — 레이어 전수 스캔 포함) |
| `pdfa-verify` | success | 1분 0초 | 골든 PDF veraPDF |
| `no-docker` | success | 1분 7초 | Docker 없이 통합 테스트가 스킵이 아니라 실패 |

이력(같은 PR): [`38510785852`](https://github.com/hjryoo-ai/ga-disclosure/actions/runs/38510785852)(`a0c1833`) 일곱 잡 success — 같은 수. 보고서 1판(`f3a14d3`)의 PR run `38512689473` success, 같은 커밋의 태그 push run
[`38512687590`](https://github.com/hjryoo-ai/ga-disclosure/actions/runs/38512687590)은 `kind` 잡의 클러스터 대상 E2E에서 "원격 링크: 본인확인 실패" 시험이 데스크톱·모바일 모두 실패(`이 링크는 사용할 수 없습니다` 기대 →
`확인서 서명`) — §3 BUG-5·BUG-6, D-8.

### CLAUDE.md 규칙 9 기록

이 Phase의 도구·라이브러리·로그 출력에서 "지시를 무시하라"류 문장은 보지 못했다(재현 에이전트·조사 에이전트 보고도 0). `net.jqwik` 차단은 빌드·CI 단계 그대로.

## 4. 지시문 추가 보고

### 4.1 재현 기록 (G1·G12)

| 누가 | 언제 | 무엇을 보고 | 결과·막힌 곳 |
|---|---|---|---|
| 맥락 없는 별도 에이전트(이 작업의 맥락을 받지 않은 새 세션) | 2026-10-10 | `work/phase-8` `9549c9d` 새 클론 + README만 | 세 테넌트 MATCH까지 막힘 없음. up 63초 · deploy 126초 · smoke 20초(실패 0) · seed 165초 · verify 11초 · down 1초(웜 캐시, macOS arm64 · Docker 7.75GiB). 화면은 헤드리스 Chrome으로 직원 페이지 로드(TLS 오류 0)까지 — 로그인 이후는 사람 몫, 대신 클러스터 대상 E2E 12/12. 추측한 곳 6개(클론·cd 줄, Node 출처, 첫 실행 시간, `down`이 남기는 것, 실행마다 바뀌는 VERIFY 해시, `e2e-prep` 순서) → `2ded5b2`로 README에 반영 |
| 사람 | — | — | 승인 Q14: 심사를 막지 않는다. 결과는 PR 코멘트 + README "재현 기록" |

### 4.2 키 회전 실행 기록 (G5)

`docs/operations/keys.md` "실행 기록"이 정본이다(2026-10-10 로컬 kind, CI 잡 `kind`가 같은 순서). 새 클러스터에서 up → deploy → smoke → seed → backup → restore →
smoke → `rotate all` → 클러스터 대상 E2E → logscan.

| 키 | 단언(전부 PASS) |
|---|---|
| 커서 | 회전 전 커서 → 400 `INVALID_CURSOR`, 새 목록 200 |
| 요청 해시 | 회전 전 재전송 재생 → 회전 뒤 같은 키·본문 422 `IDEMPOTENCY_KEY_REUSED`, 새 키 201·재생 |
| 영수증 | 만료 뒤 같은 등록 키: 회전 전 같은 가명·영수증 → 회전 뒤 같은 가명·다른 영수증 |
| 데모 OIDC | 옛 토큰 200 → 회전 뒤 401, 새 로그인 200 |
| 테넌트 KEK(DEMO1) | 옛 KEK로 감싼 살아 있는 키 17 → `DEMO1-KEK-2` 등록 → dry-run pending 17(행 그대로) → 적용 17·실패 0 → 두 번째 0 → 옛 KEK 행 0, 감사 17행, 레지스트리 CURRENT/RETIRED |
| TSA 신뢰 앵커 집합 | 1개 → 스텁 키 회전·덧붙임 → 2개, 새 키 앵커, 대조: 새 인증서만으로는 **정확히 옛 앵커들만** `TSA_UNTRUSTED` |
| 재기동 다섯 번 | 각 약 100요청 중 5xx·연결 실패 0 |

뒤에 세 테넌트 MATCH, 클러스터 대상 E2E 12/12(산출물 스캔 0), 로그 스캔 0(바늘 98). 엔진 토큰·백업 키·DB/S3 자격 증명은 런북만.

### 4.3 전역 → 테넌트 KEK 이행 (11단계 회신 ①·②)

| | DEMO1 | DEMO2 | DEMO3 |
|---|---|---|---|
| Phase 7 판(`9b7f2f1`) 시드 뒤 전역 키 `KEK-LOCAL-1`로 감싼 살아 있는 키(문서·고객·작업 보고서) | 15 (5·1·9) | 8 (1·1·6) | 6 (1·1·4) |
| 1a(`270e18d`) `crypto kek init` + `register` | `DEMO1-KEK-1 CURRENT` | `DEMO2-KEK-1 CURRENT` | `DEMO3-KEK-1 CURRENT` |
| `rewrap --tenants all` dry-run | pending 15 | 8 | 6 |
| `--apply yes` 1회 / 2회 | 15 / 0 | 8 / 0 | 6 / 0 |
| 감사 `KEK_REWRAPPED` | 15행 | 8행 | 6행 |
| HEAD: 롤 두 개·CONNECT → `db migrate`(V22~V24) → **전역 키 파일을 치운 채** `verify tenant` | MATCH | MATCH | MATCH |

- **옛 전역 키로 감싼 살아 있는 키: 0**(레지스트리 밖 KEK로 감싼 살아 있는 키를 SQL로 셈 — 세 테넌트 합 29 → 0).
- **전역 키 ID 읽기 경로 제거**: 1b(`9857b5b`)에서 `LocalFileKeyProvider`·`ga.crypto.local-kek-file`·`crypto init-kek`·이중 읽기를 지웠다. `GlobalKekPathScanTest`가 코드·설정·스크립트를 걸러 되살아나지 않게 하고(주입 P8-3 — 설정 한 줄을 남기면 실패), `verify tenant`의 KEK 검사가 레지스트리에 없는 KEK ID로 감싼 행을 `KEK_UNREGISTERED`로 보고한다. 위 HEAD 단계가 실행 증거다.
- 일회용 컨테이너·별도 홈 디렉터리 — 사용자 compose 볼륨·`~/.ga-disclosure` 불관여. 기록은 `docs/operations/keys.md` "2단 업그레이드".

### 4.4 복구 증명 출력 (G2)

```
BACKUP_OBJECTS_EXPORTED run=20261010T105914Z keys=59 versions=59 deleteMarkers=0 retained=35 held=2 maxRetainUntil=2031-10-10T15:00:00Z
BACKUP_SEALED bytes=53686272 sha256=d678c9c69a5a…
BACKUP_UPLOADED run=20261010T105914Z retainUntil=2031-10-11T00:00:00Z artifactRetentionFloor=2031-10-10T15:00:00Z
BACKUP_OPENED bytes=53686272 sha256=d678c9c69a5a…
BACKUP_OBJECTS_IMPORTED run=20261010T105914Z keys=59 versions=59 deleteMarkers=0 retained=35 held=2 maxRetainUntil=2031-10-10T15:00:00Z
RESTORE_TABLES MATCH tables=38
VERIFY_TENANT DEMO1·DEMO2·DEMO3 MATCH findings=0
```

12단계 EVIDENCE 실행(다른 클러스터, 2026-10-10)에서도 같은 순서 → `RESTORE_TABLES MATCH tables=38`, 같은 문서의 수명주기 질의 출력 diff 0, 증거 ZIP 바이트 동일, 영수증과 함께 `verify package` MATCH(`docs/EVIDENCE.md` §7).

## 5. 설계서·계획과 달리 구현했거나 해석한 지점

| # | 계획·지시 | 구현 | 이유 |
|---|---|---|---|
| ① | 1단계 하나 | 1a(레지스트리·이중 읽기·`KEK_REWRAP`)·1b(전역 경로 제거)·1c(나머지 비밀) | 승인 Q2 — 운영은 2단 업그레이드. `KEK_REWRAP`은 4단계에서 1a로 앞당김 |
| ② | V21에 헬스·백업 롤·`renderer_version` | V21 `tenant_kek`, V22 롤, V23 `renderer_version`, V24 인덱스 | 단계 순서대로 번호 |
| ③ | 앵커 미터 이름 | `ga.anchor.unstamped.days` | 단위를 이름에 |
| ④ | 미리보기 워터마크에도 렌더러 판 | 판 없음 | 미리보기는 저장하지 않는 화면용 — 고정할 대상이 없다 |
| ⑤ | 어휘 API 계약 2.9.0, ETag = (룰 버전 ID, 날짜) 해시 | 2.11.0(앞 단계가 2.10.0까지 씀), ETag = 응답 본문 해시, 플래그 유형은 코드 순 | 본문 해시가 "같은 응답 = 같은 ETag"를 직접 보장. JSONB 키 순서는 보장되지 않는다 |
| ⑥ | 화면 폰트 서브셋 | 측정 뒤 하지 않음(약 4% 감소 — 계획 기준 10% 미만), 측정값 상한만 | 승인 "이득이 없으면 그대로" |
| ⑦ | `jobs run` CLI 4단계 | 9a | CronJob 진입점이 필요한 단계로 |
| ⑧ | Ubuntu JRE 베이스 | Temurin Alpine JRE(같은 판) | FIND-2 — 규칙을 좁히지 않고 베이스를 바꿈 |
| ⑨ | kubeconform 전부 strict | CRD 정의 두 개만 건너뜀 | 고정 스키마 저장소에 없음 — 저장소 사본의 해시 대조로 대신 |
| ⑩ | 백업 CronJob도 JobKind | JobKind 아님 — `CronJobManifestTest`의 닫힌 예외 1개 | 테넌트 작업이 아니라 클러스터 작업 |
| ⑪ | 스모크 "평문 거부" | Traefik 자신의 404(앱·웹 미도달) | KIND-5 — 실제 모양 |
| ⑫ | 키 회전 5종 | 6종 실행(TSA 신뢰 앵커 6번째), 엔진 토큰은 런북만 | 승인 Q15 |
| ⑬ | 계약 변경은 승인 뒤 | `signUrl`(2.12.0)을 먼저 바꾸고 바로 보고 → 사후 승인 | KIND-10 — 승인된 흐름이 배포 형태에서 불가능(D) |
| ⑭ | kind 복구 | 실행마다 새 버킷 | KIND-8 — 중단된 시도 위의 "성공"을 깨끗한 통과로 적지 않기 위해 |
| ⑮ | README 첫 화면 | 옛 README 본문은 `docs/DEVELOPMENT.md` | 첫 화면 = 요약·한계·숫자·둘러보기 |
| ⑯ | 6B-b "인그레스에도 클라이언트 인증서 주체별 한도(벨트)" | **하지 않았다 — 놓쳤다** | 앱의 DB 집계(통제)는 있다. 벨트를 단다면 내부 라우트에 인증서 주체 헤더를 키로 한 Traefik 한도 — §14 #23(주체 형식)과 같이 정할 일이라 질문 Q2로 |
| ⑰ | 지시문 "롤 5종"·"앱 롤 헬스" | 롤 9종 정본·헬스 전용 롤 | 지시문 오류 둘(승인) |
| ⑱ | 사람 재현(G1) | 맥락 없는 에이전트 재현 먼저, 사람 재현은 심사와 별개 | 승인 Q14 |

## 6. 넓힘·계약 추가 표시 (승인 단서)

| 항목 | 종류 | 내용 |
|---|---|---|
| Q2 | **[넓힘] 불변식** | `tenant_kek`(GD140), `ga_kek_rewrap`(정의자 소유, GD141) + `document_key`·`customer_data_key`·`async_job` 가드의 재래핑 분기 |
| Q3 | **[넓힘] 롤** | `disclosure_health`·`disclosure_backup`(V22) |
| Q8 | **[넓힘] 인가 표** | `RULE_VOCABULARY_READ`(사람 역할 셋 TENANT), `KEK_REGISTER`·`KEK_REWRAP`(운영자만) |
| 6B-a | [계약 추가] | 503 `TENANT_RULES_NOT_ACTIVE`(api 2.10.0·internal 4.1.0) |
| 1a | [계약 추가] | `Job.kind` `KEK_REWRAP`(api 2.9.0), `verify/v1/kek-rewrap-report.schema.json`, verify-report KEK 검사 |
| 5 | [계약 추가] | 서식 스키마 `render.columns`·`unavailableText`, 서식 STANDARD-v2, 증거 매니페스트 2판(`verify package`가 1·2판 모두) |
| 6 | [계약 추가] | `GET /api/v1/rules/vocabulary`(2.11.0) |
| 11 | [계약 추가 — 사후 승인] | `SessionIssueReceipt.signUrl`(2.12.0) |

## D. 결정·결함 기록 (Phase 8)

| # | 분류 | 내용 |
|---|---|---|
| D-1 | **승인 전 계약 변경, 사후 승인** | `signUrl`(2.12.0). 사유: 승인된 흐름(현장 서명)이 분리 호스트 배포에서 불가능했다(KIND-10). 심사 규약: 같은 상황이 또 오면 먼저 바꾸고 바로 보고 |
| D-2 | 심사 누락(승인이 기록) | 설계서의 "테넌트 KEK"가 구현에서는 전역 키 하나였다 — 3B(v1.8)부터의 문구, 놓친 심사 3B~7 여섯 번(FIND-5로 시작점 정정). Phase 8에서 참이 됐다 |
| D-3 | 결정 | 마이그레이션 Job은 적용 전에 지우고 다시 만든다. 대안(설정 해시를 Job 이름에)은 옛 Job이 남아 정리 대상이 늘고 CI가 이름을 추적해야 해 채택하지 않았다 |
| D-4 | 패턴 | 주입이 "최신"으로 건너뛰어 통과한 일 세 번(E3.2 계약 디렉터리, 12단계 archTest, `GlobalKekPathScanTest`) — 원인은 매번 빌드 입력 선언. CLAUDE.md 규약 + `verifyTestInputs`(FIND-6: 29건 선언) |
| D-5 | 규약 | 주입 수는 ID 하나 단위로 센다(Phase 7: 27 → 28, Phase 0: 16, Phase 1: 10) |
| D-6 | 작업 기록 손실 | 10-10 이후 기계 재부팅으로 임시 디렉터리(주입 메모·숫자 표 초안·업그레이드 실측 로그)가 지워졌다. 주입 표는 세션 기록에서 복구했고, 실측 값은 커밋된 운영 문서(`keys.md`·`backup-restore.md`)와 이 보고서가 정본이다 |
| D-7 | 6B D-2 닫음 | 간헐 실패 — 미증명·재발 0 |
| D-8 | 간헐 실패를 넘기지 않음 | 보고서 1판의 태그 push run에서만 kind E2E가 실패했다(같은 커밋의 PR run 두 번은 통과). 재실행으로 넘기지 않고 재현 장치로 결정적으로 재현해 원인 두 겹(BUG-6 시험 경합이 BUG-5 앱 결함을 가림)을 고쳤다. 태그 `phase-8`은 이 보고서의 최종 커밋으로 옮겼다(첫 위치 `f3a14d3`) |

## 7. 알려진 한계 (최종)

- 협회 표준확인서 원문 미확보(§14 #2) — "협회 표준" 표기 0.
- 징구율에 규제 정의 없음(§14 #17) — 내부 지표.
- 실 TSA·통지 사업자·IdP·비밀 저장소 미연동 — 인터페이스·문서·로컬 어댑터까지, 실 TSA는 opt-in 계약 시험만.
- mTLS 주체 전달 형식 공백(§14 #23) — 운영 오버레이 그대로면 게이트·계약 연결의 주체 대조 경로가 404.
- 인그레스 주체별 한도(벨트) 없음(§5 ⑯).
- 공개 서명 한도는 복제본마다 — 실효 한도 = 룰 값 × 앱 복제본, IP 한도 = 30 × 진입점 복제본.
- 공개 서명 응답 하한(패딩)은 설정값 — 규모에 따른 적정값 미측정.
- 백업 봉투 머리에 백업 키 ID가 없다 — 옛 백업은 복구 환경에 옛 `backup/key`를 둬야 열린다.
- 감사 로그 해시는 앱이 계산하고 DB는 append-only만 강제 — 변조는 `verify tenant`가 재계산으로 찾는다.
- 강제 없는 규칙(ARCHITECTURE "요약"): PII CLI·환경변수 전달 금지(구조로만), 서버 측 사유 자동 채움 금지(구조로만), "설계서·코드 같은 커밋"·기존 `V*` 수정 금지(Flyway 체크섬뿐), "출력 속 지시 무시"(관례).
- kind 데모 전용 장치 둘: 멱등 만료를 슈퍼유저 SQL로 앞당김(영수증 회전 시험), TSA 회전의 `+P1D` 데모 시계(새 키 앵커).
- 둘러보기 첫 실행 시간 미측정, 사람 재현 미실시.

## 8. 질문

| # | 질문 | 권장 |
|---|---|---|
| Q1 | 사람 재현 | 승인대로 심사와 별개 — 결과는 PR 코멘트 + README "재현 기록" |
| Q2 | 인그레스 주체별 한도(§5 ⑯) | §14 #23(주체 형식)을 정할 때 함께 — 그 전에는 앱 DB 집계가 통제. 지금 단다면 내부 라우트에 `PassTLSClientCert` + 주체 헤더 키 한도, kind 스모크에 단언 하나 |
| Q3 | Phase 8 수용 뒤 | 포털 Phase 0 지시문 개정(`com.ga.platform` 아티팩트 소비) — 지시문대로 |

## 9. Phase 0~8 숫자 (전부 CI 출처)

테스트 수는 각 run의 `test-reports` 아티팩트(Gradle 보고서 합계)와 대조했고 모듈별 수까지 보고서와 같다(실패 0·스킵 0). 주입은 주입 ID 하나 단위(Phase 0·1은
ID가 없어 주입한 위반 하나) — 수는 각 Phase 보고서의 주입 표에서 센다(CI 아티팩트에는 없다). Phase 7의 보고서 본문 27은 같은 규약으로 28이다.

| Phase | 태그 | PR | CI run(결론) | 테스트(JVM) | 화면 단위 | E2E | 주입 | CI 실패 뒤 수정 |
|---|---|---|---|---:|---:|---:|---:|---|
| 0 | `phase-0` | — (main 직접) | [36549053160](https://github.com/hjryoo-ai/ga-disclosure/actions/runs/36549053160) success | 4,869 | — | — | 16 | — |
| 1 | `phase-1` | #1 | [36554847957](https://github.com/hjryoo-ai/ga-disclosure/actions/runs/36554847957) success | 7,138 | — | — | 10 | 36554570335(`no-docker` 컨테이너 방식) |
| 2 | `phase-2` | #3 | [36701264864](https://github.com/hjryoo-ai/ga-disclosure/actions/runs/36701264864) success | 7,281 | — | — | 12 | — |
| 3A | `phase-3A` | #5 | [36712325175](https://github.com/hjryoo-ai/ga-disclosure/actions/runs/36712325175) success | 9,268 | — | — | 11 | — |
| 3B | `phase-3B` | #6 | [36856909677](https://github.com/hjryoo-ai/ga-disclosure/actions/runs/36856909677) success | 10,790 | — | — | 22 | 36855875536(저장소 시험 13건 S3 500) |
| 4 | `phase-4` | #7 | [37063019897](https://github.com/hjryoo-ai/ga-disclosure/actions/runs/37063019897) success | 11,733 | — | — | 56 | — |
| 5 | `phase-5` | #8 | [37099922622](https://github.com/hjryoo-ai/ga-disclosure/actions/runs/37099922622) success | 12,037 | — | — | 69 | 37099082331(시간대 의존 시험 1건) |
| 6A | `phase-6A` | #9 | [37786410851](https://github.com/hjryoo-ai/ga-disclosure/actions/runs/37786410851) success | 12,204 | — | — | 105 | — |
| 6B | `phase-6B` | #10 | [37926046428](https://github.com/hjryoo-ai/ga-disclosure/actions/runs/37926046428) success | 13,642 | — | — | 151 | — |
| 7 | `phase-7` | #12 | [37968091134](https://github.com/hjryoo-ai/ga-disclosure/actions/runs/37968091134) success | 13,689 | 24 | 12 | 28 | — |
| 8 | `phase-8` | #13 | [38517344797](https://github.com/hjryoo-ai/ga-disclosure/actions/runs/38517344797) success | 13,835(+ deploy 47 · image 5) | 27 | 12 + kind 12 | 63 | 38512687590(태그 push — kind E2E, BUG-5·6) |
| **합** | | | | | | | **543** | |

계약 PR(#2·#4·#11)은 표에 없다(공유 계약 표기 변경 — 자체 CI 뒤 병합). 3A의 엔진 저장소 run 2건(`hjryoo-ai/ga-commission-engine` 36707878119·36707884896)은 success.
Phase 0~7의 수치 조사는 각 run의 아티팩트를 받아 합산한 것이고(2026-10-10), 이 저장소 run 67건 중 실패는 위 세 건뿐이었다.

## 10. §14 미결정 — 최종 목록

정본은 설계서 §14 맨 앞의 "최종 목록" 표다. 닫힌 것은 그 위 확정 블록(2026-09-23·09-29·09-30)과 #15.

| 상태 | 항목 |
|---|---|
| **닫힘** | 확정 블록: 관리자 확인 기본 REQUIRED(D-9), 서명 효력 수준·본인확인(D-10), 외부 타임스탬프 v1 포함, 상품 키 40자·패턴, 임시등록 미전송, 봉인 본문 성명 포함 + crypto-shredding, 계약 변경 순서(UPSTREAM), 등급 판정 기준(엔진 반올림 비율), PDF 변환기, GLOBAL 룰 저장(복제 + 해시 대사), 룰 병합(`tenantOverridable`), `signOrder`, 엔진 `UNAVAILABLE`·`tieBreak`·`ratioToAvg` 약속, 스냅샷 컬럼, `birth_date_enc` 등 · **#15** 보류 해제 사유 어휘(6A) |
| **구조만**(어휘·작업·어댑터는 있고 값·정책은 외부 결정) | #3 법정 보존기간, #4 TSA 사업자, #6 게이트 강도, #10 각종 실값, #11 관리자 확인 범위, #12 마스킹 규칙, #13 초안 폐기(`ABANDON_DRAFTS` — 기본 null), #14 보존기한 재계산(`RETENTION_RECOMPUTE` — 실값은 #3), #16 계약 피드 형식, #17 징구율 정의(내부 지표), #18 플래그 해소 어휘, #19 청약·증권 번호 형식 |
| **남음** | #1 등급 산정 모수(엔진·외부), #2 협회 표준확인서 원문, #5 정본 모드 기본값·협회 연동, #7 오프라인 초안, #8 생년월일 수집 동의 문구, #9 QR 인쇄(해시 앞 12자 텍스트 각주만), #20 정정의 행위자·재배정(HTTP 정정 없음), #21 같은 룰 버전의 징구율 재계산, #22 중복 가명 병합, #23 mTLS 주체 전달 형식 |
