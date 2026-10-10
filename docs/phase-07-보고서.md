# Phase 7 완료 보고 — 화면 (직원 화면·고객 공개 서명·미리보기 워터마크·E2E)

작성 2026-10-10 · 대상 지시문 `docs/phase-07-지시문.md`(v1.1 — 협회 서식 원문 확보 건너뜀) · 계획 `docs/phase-07-계획.md`(승인 2026-10-10, `docs/phase-07-계획승인.md` — 계획의 "승인 반영"이 본문보다 우선)
- 설계서 v1.17(변경 이력 ①~⑫) · 브랜치 `work/phase-7` · PR [#12](https://github.com/hjryoo-ai/ga-disclosure/pull/12)
- **병합은 수용 심사 회신 뒤**에 한다. 태그 `phase-7`은 이 보고서 커밋에 단다.

## 0. 6B에서 넘어온 표 (승인 지시 — 보고서 맨 앞)

### 6B 보고서 D 표 (전체) — Phase 7에서 한 것

| # | 분류 | 내용 | Phase 7 영향 | Phase 7 결과 |
|---|---|---|---|---|
| D-1 | 6A 결함, 6B 수정 | 멱등 요청 해시가 키 없는 SHA-256 — 정의역이 작은 본문을 사전 대입으로 되돌릴 수 있었다(`b167beb`) | 화면의 쓰기 요청도 같은 HMAC 해시를 지난다 — 변경 없음 | 같은 이유로 화면은 멱등 키를 **본문에서 만들지 않는다**(의도마다 무작위 — §5 일탈 ①) |
| D-2 | 미증명 가설 + 테스트 전용 수정 | 간헐 실패를 연결 재사용 탓으로 보고 테스트 HTTP 클라이언트를 요청마다 새로 만들었다(`7980fa6`), 원인 미증명, 이후 재발 없음 | E2E도 같은 서버를 친다 — 재발하면 포트·연결 로그로 원인을 증명한다 | E2E 실행(로컬 전체 실행 5회 이상·주입 5회)에서 재발 없음 — 증명할 사건이 없었다 |
| D-3 | 테스트 범위 결함 + 수정 | 거부 코드 표 대조가 일부 계열만 봤다 → `Categorized` enum 전수 양방향(`117707e`) | `Problem.code` 사전 커버리지 시험(G3)이 같은 전수 목록을 쓴다 | `messages.test.ts`가 설계서 `rejection-categories` 블록(76행, 75종)과 양방향 — 주입 W8·W9 |
| D-4 | 불변식 위반, 데이터 영향 없음 | 6A부터 HTTP 채널이 원 URI 접두로 정해졌다 | 화면 서빙 경로를 더하는 보안 체인도 라우팅 결과·`PathPatternRequestMatcher`로만 판정 | `DemoWebConfiguration` 체인 `@Order(5)`는 `PathPatternRequestMatcher`만, 인코딩된 `..%2f`는 방화벽 400을 명시 단언(`DemoWebIT`) |
| D-5 | 시험 공회전 + 수정 | 잠금 재적용 검사가 NULL만 보고 통과했다 | 브라우저 누출 스캔은 "센티널이 실제로 입력됐다"를 먼저 단언 | `after.spec.ts`가 프로젝트마다 센티널이 요청 본문에 실린 횟수 > 0을 먼저 단언(실행 값: 8회) |
| D-6 | 시험 출력 | 동적 센티널의 첫 실패가 허구 값을 로컬 보고서 XML에 실었다 | 실패 메시지·트레이스·스크린샷 이름에 센티널 0 | 실패 메시지는 "sentinel #n in {종류}"뿐, 트레이스·자동 스크린샷·비디오 끔, 파일 이름 스캔. **남은 공백**: Playwright 실패 시 `error-context.md`(접근성 트리 스냅샷)는 입력 칸 값을 실을 수 있다 — 로컬 `build/e2e/test-results/`에만 있고 CI 아티팩트에서 뺐다(§D P7-3) |
| D-7 | 계약 ↔ 구현 불일치, 수정 | 모르는 필드를 조용히 버렸다 → 앱 전체 400(R1) | 생성 클라이언트는 계약에 있는 필드만 보낸다 | 생성 타입 + `exactOptionalPropertyTypes`(tsc), 잠금 파일(`clientCheck`) |
| D-8 | 데모 잔존 상태 기록 누락 | HTTP 데모 첫 시도의 잔존 상태를 D 표에 적지 않았다 | E2E·`e2e-demo`의 실패 실행이 남긴 서버 상태는 D 항목으로 | 모든 E2E 실행(실패 포함)은 격리 컨테이너였고 `down`이 `-v`로 지웠다 — 남은 컨테이너 0(`docker ps -a --filter label=ga-e2e` 빈 결과). 실패 실행이 남긴 상태: 없음(컨테이너와 함께 사라짐). 사용자 compose 볼륨은 건드리지 않았다(§D P7-4) |

### 첫 시도에 놓친 것 — Phase 7에서

| # | 무엇 | 6B 교훈 | Phase 7 |
|---|---|---|---|
| T4 | 문자열 플래그 유형 주입을 스캔이 첫 실행에 못 잡음 | 스캔 범위 결함 → 스캔을 넓힘 | 화면 스캔은 TypeScript 구문 트리(문자열·템플릿 조각·JSX 텍스트·속성·정규식, 주석 제외)이고 자기 시험이 있다. 첫 시도에 못 잡은 주입 **0건** |
| P4 | 주입이 컴파일 실패 — 시험이 아니라 컴파일러가 막음 | 무효로 세지 않음 | 린트·`tsc`가 막은 주입과 시험이 막은 주입을 나눠 적었다(§3). 주입 W1·W2·W5는 **린트가** 잡은 것(의도한 방어선이 린트) — 무효 주입 0건. 단 E2E 주입(토큰 쿼리·sessionStorage·console)은 린트를 거치지 않는 빌드로 번들에 넣어 **E2E가** 잡는 것을 따로 증명했다 |
| E3.2 J2 | 계약만 바꾼 주입이 시험을 돌리지 못함(입력 미선언) | 입력 선언 | `disclosure-web` 태스크는 계약·락·원천·서식 번들·폰트·설계서를 입력으로 선언, 주입 W4(계약만 변경)가 `clientCheck`를 실패시킴 |
| (Phase 7 신규) | 시험이 스스로 진짜 토큰을 주소에 실었다 | — | 거부 화면 시험의 "쿼리 토큰" 사례가 쓰인 진짜 토큰을 `?token=`에 넣어 누출 스캔이 시험 자신을 잡았다(1회차). 형식만 맞는 가짜 토큰으로 바꿨다 — 화면은 조각만 읽으므로 같은 경로를 시험한다 |

## 요약

- **직원 화면(지시문 2절)**: React 19 + react-router, 데모 OIDC(Authorization Code + PKCE, 토큰은 메모리에만). 설계사(고객 등록 → 초안 → 비교·등급·사유 → 검증 → 봉인 → 서명 세션 → 설계사 서명), 관리자(플래그 체크 → 관리자 확인·예외 승인·무효·종이 스캔 검토), 준법(큐·배정·해소·검증 실행 증거, 법적 보존 4-eyes, 작업·보존 재계산 dry-run, 징구율). **버튼은 역할·상태로 숨기지 않는다** — 할 수 없는 동작은 서버가 거부하고 화면은 사전 문구 + 코드를 보인다. 화면에 상태 리터럴 0·`.status` 비교 0(시험).
- **라벨**: 확인서 라벨은 그 확인서가 고정한 서식에서만(`GET /api/v1/disclosures/{id}/template` — **[넓힘 Q3]**). 그 밖의 문구는 `messages.ko.json` 하나, 서식 라벨과 교집합 0, 화면 원천의 한글은 이 파일에만.
- **고객 공개 서명(3절)**: 프레임워크 없는 별도 번들(react·라우터 0 — 빌드 산출 검사), 토큰은 조각에서 한 번 읽고 주소에서 지움, `X-Sign-Token` 헤더로만, 저장소 0, 거부는 사유 무관 한 화면, pdf.js 워커까지 같은 출처 번들.
- **미리보기 워터마크(4절)**: `GET …/preview.pdf`만 워터마크, 저장 안 함, 산출물·공개 서명 PDF는 바이트 그대로(`PreviewWatermarkIT`).
- **플래그 가시성(Q1 — [넓힘])**: `FLAG_READ` 설계사 칸 OWN, 거르는 곳은 서버 저장소 조건 하나. 기본 룰에서 설계사는 200 빈 목록, E2E에서 관리자에게 보이는 `IDENTITY_FAILED`가 설계사에게 0.
- **E2E(5절)**: 격리 컨테이너 + 부트 jar + Playwright(Chromium 데스크톱·Pixel 7) 12건 — 역할별 흐름·공개 서명 전체·거부 화면·`CHAIN_BROKEN`, 키보드만(서명 패드 제외), 자동 감시(CSP 위반 0·외부 출처 0·누출 0·axe 0), 마지막에 화면이 보낸 61개 쓰기를 같은 키로 다시 보내 **테이블 37개 행 수 그대로**(재생 59 + 현장 기기 세션 발급 409 2).
- **테스트**: JVM 13,689건(실패 0·스킵 0) + 화면 단위·컴포넌트·스캔 24건(vitest) + E2E 12건. 위반 주입 **27건 전부** 의도한 방어선에서 잡혔다(§3).
- **CI**: PR #12 run `37968091134`(head `740f0ac`) — `build`·`e2e`(새 잡)·`pdfa-verify`·`no-docker` 전부 첫 실행에 success. CI 테스트 보고서는 로컬과 같은 13,689건(모듈별 수 같음, 실패 0·스킵 0), vitest 24, E2E 12(Linux Chromium — 로컬은 macOS).

## 1. 커밋

| 커밋 | 내용 |
|---|---|
| `3fc3c06` | 지시문 v1.1 |
| `1a5bf80` | 0단계 — 라벨만 바꾼 서식 새 버전에 코드 변경 0(`TemplateVersionsIT` 3·`LabelLiteralScanTest` 3), Phase 1 C5의 서식 쪽 시험 공백을 처음 시험 |
| `63578ab` · `4619604` | 계획 · 승인 반영 |
| `c2e5bf0` | 1단계 `disclosure-web` 모듈 — Node 24 LTS를 Gradle이 감쌈, 계약 → 생성 클라이언트(해시 잠금), 린트, 라이선스 표 양방향, 빌드 산출 검사 |
| `c38fab4` | Q1 **[넓힘]** `FLAG_READ` AGENT OWN — 서버 필터, 계약 2.8.0 |
| `7ab3d3a` | Q3 **[넓힘]** `getDisclosureTemplate` |
| `7505492` | 미리보기 워터마크 `getPreviewPdf`, 룰 키 `preview.watermark`(번들 4개 재해시) |
| `932f408` | 데모 프로파일 같은 출처 서빙(CSP)·데모 로그인(PKCE), 계약 `demo-oidc` 1.0.0 |
| `201b199` | 3단계 `messages.ko.json`·`Problem.code` 사전 양방향 |
| `f84e9dc` | 4단계 고객 공개 서명 번들 |
| `efc41fc` | 5~7단계 직원 화면(역할별), `screenLogic`·`reasons` 시험 |
| `740f0ac` | 8~9단계 E2E·CI 잡 `e2e`·`e2eDemo`·README 화면 절, E2E가 드러낸 화면 변경 |
| (이 커밋) | 보고서 |

## 2. 화면 ↔ 계약

화면의 서버 호출은 전부 생성 클라이언트(`openapi-fetch` + `openapi-typescript` 타입, `src/shared/api/`의 세 파일만 클라이언트를 만든다 — 린트). 계약 3개: `disclosure-api` 2.8.0, `disclosure-public`(변경 없음), `demo-oidc` 1.0.0(**[계약 추가]**).

| 화면 | 역할(서버 인가) | 부르는 operation |
|---|---|---|
| 로그인 | — | `GET /demo/oidc/authorize`(서버 폼, 새 창) → `POST /demo/oidc/token`(생성 클라이언트) |
| 확인서 목록 | 설계사 OWN·관리자 ORG·준법 TENANT | `listDisclosures` |
| 고객 등록 | 설계사 SELF | `registerCustomer` |
| 새 확인서 | 설계사 SELF | `createDisclosure` |
| 확인서 상세 | 읽기 범위 | `getDisclosure`·`getDisclosureTemplate`·`listDisclosureFlags` |
| └ 비교 상품 | 설계사 | `searchCatalogProducts`·`replaceItems` |
| └ 단계 진행 | 설계사(검증은 관리자도) | `compare`·`requestGrades`·`validate`·`seal`·`rebase`·`abandonDraft`, 관리자 `approveException`(검증 결과 행에서) |
| └ 추천 사유 | 설계사 | `setRecommendations` |
| └ 문서 | 읽기 범위 | `getPreviewPdf`·`getArtifact`(4종)·`getAnchorReceipt` |
| └ 서명 진행 | 설계사 | `issueSignSession`·`confirmFaceToFace`·`uploadPaperScan`·`agentSignature` |
| └ 확인·종결 | 관리자(무효·완료는 설계사도) | `managerConfirmation`·`paperScanReview`·`complete`·`voidDisclosure` |
| 준법 플래그 | 준법 TENANT·관리자 ORG(설계사는 칸 없음 → 404) | `listFlags`·`assignFlag`·`resolveFlag`·`submitJob(VERIFY_TENANT)`·`getJob`·`getJobReport` |
| 법적 보존 | 준법 | `listLegalHolds`·`placeLegalHold`·`releaseLegalHold` |
| 작업 | 준법 | `listJobs`·`submitJob`(종류 enum 전수)·`getJob`·`getJobReport` |
| 징구율 | 준법·관리자 | `listCollectionRates` |
| 고객 서명 `/s#…` | 서명 토큰 | `disclosure-public`: status → open → view → verify-identity → capture |

계약에 있으나 화면에 두지 않은 것: `getDisclosure`의 `ratioToAvg`(문서처럼 보이지 않음), 정정(사람 칸 없음 — 6B), 영수증 내보내기·룰 버전 승인·리포트(HTTP 경로 없음 — CLI).

## 3. 테스트와 완료 기준

### 테스트 수

| 묶음 | 건수 | 실패 | 스킵 |
|---|---|---|---|
| platform-core | 3,067 | 0 | 0 |
| platform-canonical | 1,054 | 0 | 0 |
| platform-spring | 12 | 0 | 0 |
| disclosure-domain | 1,094 | 0 | 0 |
| disclosure-rules | 2,467 | 0 | 0 |
| disclosure-workflow | 1,372 | 0 | 0 |
| disclosure-seal | 65 | 0 | 0 |
| disclosure-sign | 515 | 0 | 0 |
| disclosure-audit | 141 | 0 | 0 |
| disclosure-infra integrationTest | 3,734 | 0 | 0 |
| disclosure-app archTest | 69 | 0 | 0 |
| disclosure-app integrationTest | 99 | 0 | 0 |
| **JVM 합계**(`./gradlew build`, 로컬) | **13,689** | 0 | 0 |
| disclosure-web vitest(단위·컴포넌트·스캔·사전) | 24 | 0 | 0 |
| disclosure-web E2E(`./gradlew :disclosure-web:e2e`) | 12 | 0 | 0 |

6B 13,642 → 0단계 13,670 → Q1·Q3·미리보기·데모 웹 13,689.

### 완료 기준

| # | 기준 | 증거 |
|---|---|---|
| G0 | 서식 교체 가능성 | `TemplateVersionsIT`(3) — 0단계 `1a5bf80` |
| G1 | 라벨은 서식 데이터에서, 서버·화면 코드에 한국어 라벨 0 | 서버 `LabelLiteralScanTest`(3), 화면 `literalScan.test.ts`(4: 한글은 `messages.ko.json`만·서식 라벨 리터럴 0·메시지 ∩ 라벨 = ∅·판독기 자기 시험), E2E DOM 대조(`flows.spec.ts` 1번 — `[data-field-code]` 노드 = `getDisclosureTemplate` 라벨, 비교표 열 순서 = 서식 순서) |
| G2 | 생성 클라이언트만, 수기 fetch 0, 계약 drift 시 빌드 실패 | ESLint(`fetch`·XHR·WebSocket·EventSource·`sendBeacon` 금지, `openapi-fetch`는 `src/shared/api/`만), `clientCheck`(계약·생성기·생성물 해시 잠금) |
| G3 | 화면에 업무 판단 0, 사전 외 코드는 그대로 | `screenLogic.test.ts`(4: 상태 enum 리터럴 0·`.status` 비교 0(닫힌 허용 1 = HTTP 코드 판독 `src/shared/http.ts`, 폐기 항목 검사)·표기 사전 ↔ 계약 enum 8종 양방향·판독기 자기 시험), `messages.test.ts`(4: 16·75·2 양방향, 모르는 코드는 코드 그대로, 치환자 0) |
| G4 | 공개 서명: URL에서 토큰 제거·저장소 0·헤더로만·한 거부 화면 | `sign.test.ts`(8), E2E `flows.spec.ts` 1~3번(주소 `/s`로 바뀜, 쓰인·폐기된·틀린·쿼리 토큰·토큰 없음 → 같은 한 화면, `/s`·`/staff`·`/oidc-callback` `no-store`), 감시의 저장소·헤더 검사 |
| G5 | 브라우저 측 누출 0(동적 센티널) | `WebLeakScanE2E` = `e2e/support/fixtures.ts`(시험마다) + `after.spec.ts`(합계): 콘솔 11·URL 792·헤더 14,348·저장소 19항목(e2eDemo 실행) 스캔, 적중 0, 센티널 입력 8회, 파일 이름·`server.log`에 센티널 0(`leak-scan-summary.json`) |
| G6 | 추천사유 자동완성·예시·템플릿 문구 0, 시스템이 채우는 경로 0 | `reasons.test.tsx`(4: 저장된 사유가 있어도 칸이 비어 있음·autocomplete off·placeholder/datalist/list/기본값 0·아무것도 안 쓰면 빈 목록·예시 표기 0), `literalScan`(서식 문구 리터럴 0) |
| G7 | 미리보기만 워터마크 | `PreviewWatermarkIT`, `PreviewWatermarkerTest`·`PreviewWatermarkRuleTest`, E2E 미리보기 응답 `application/pdf` |
| G8 | AGENT에 비가시 플래그 0, MANAGER 확인이 `acknowledgedFlags`로 | `FlagVisibilityIT`(룰 기본 200 빈 목록·룰 변형 한 유형만·다른 설계사 404), E2E `flows.spec.ts` 2번(설계사 "보이는 플래그가 없습니다", 관리자 `IDENTITY_FAILED` → 요청 본문 `acknowledgedFlags` = 화면에 보인 ID 전부) |
| G9 | 인라인 스크립트 0·외부 출처 0·공개 `no-store` | `scan-dist.mjs`(인라인 스크립트·핸들러·외부 URL 0), `DemoWebIT`(CSP 지시어 단언), E2E 감시(CSP 위반 0·외부 출처 요청 0), `flows.spec.ts` 3번 헤더 |
| G10 | axe 0(예외 사유와 함께), 키보드만 완주 | E2E 화면 23개(데스크톱·모바일) axe `wcag2a`·`wcag2aa`·`wcag21a`·`wcag21aa` 위반 0, 예외 목록 비어 있음(안 쓰인 예외 검사 포함). 흐름 전체 키보드(`e2e/support/keyboard.ts` — Tab으로 닿지 못하면 실패, 서명 패드만 마우스) |
| G11 | 역할별 E2E·공개 서명 전체·2회 실행 NOOP | `flows.spec.ts`(설계사·고객·관리자 / 원격 링크 / 거부 / 준법), `chain.spec.ts`, `after.spec.ts` 재전송(61 쓰기: 재생 59 + 409 `IDEMPOTENCY_NOT_REPLAYABLE` 2, 테이블 37개 행 수 동일 — `replay-summary.json`) |
| G12 | 0~6B 무손상·평문·jqwik·BOM·OSV·라이선스·위반 주입 | `./gradlew build` 13,689 실패 0, CI jqwik 단계, `webLicenses`(양방향 표), OSV(`scripts/osv.mjs` — 296 항목 0건, 1단계), 주입 아래 |

### 위반 주입 기록 (주입 → 실패 확인 → 제거)

| # | 주입 | 잡은 것 | 방어선 |
|---|---|---|---|
| 0단계 ×5 | 해석기가 최신 서식 반환 / 같은 버전 조용히 수용 / 렌더러에 "고객명" 상수 / 새 파일에 한글 / 허용 목록 항목 삭제 | `TemplateVersionsIT` 2·1, `LabelLiteralScanTest` 2·1·1 | 시험 |
| W1 | 직원 화면 수기 `fetch` | ESLint 1 | 린트 |
| W2 | 서명 화면 `sessionStorage.setItem` | ESLint 1 | 린트 |
| W3a·b | 라벨 아닌 한글 상수 / 진짜 라벨 "고객명" 상수 | `literalScan` 1 / 2 | 시험 (G12 "라벨을 코드 상수로") |
| W4 | 계약만 변경(공백) | `clientCheck` | 빌드 |
| W5 | 서명 번들이 react import | `scan-dist` + ESLint | 빌드·린트 |
| W6 | THIRD-PARTY 버전 어긋남 | `webLicenses` | 빌드 |
| B1a | 서버 플래그 필터 제거(화면이 거르는 구조) | `FlagVisibilityIT` 2 | 시험 |
| B1b | 서식 응답에 `status`·`sealable` | `DisclosureTemplateApiIT` 3(계약 검증) | 시험 |
| G12-6 | 산출물 엔드포인트에 워터마크 | `PreviewWatermarkIT` 1 | 시험 |
| W7 | 서버 CSP에 `unsafe-eval` | `DemoWebIT` 2 | 시험 |
| W8·W9·W10 | 거부 코드 문구 삭제 / 없는 코드 추가 / 서식 라벨을 메시지 값으로 | `messages.test` 1·1, `literalScan` 1 | 시험 |
| G12-5 | 네트워크 실패에 404와 다른 화면 | `sign.test` 1 | 시험 |
| W11 | 사유 칸에 placeholder(예시 문구) | `reasons.test` 1 | 시험 (G12 "추천사유 예시") |
| W12 | 사유 칸에 서버 사유를 기본값으로 | `reasons.test` 3 | 시험 |
| W13 | 상세 화면 `d.status === 'REASONED'` | `screenLogic` 2 | 시험 |
| B1c | 서명 번들에 `eval` | E2E CSP 감시 `CSP-VIOLATION script-src eval` | E2E |
| G12 토큰 쿼리 | 공개 클라이언트가 `?token=` | E2E 흐름 실패 + 누출 `sentinel #5 in url`·`in console` | E2E |
| G12 sessionStorage | 토큰을 sessionStorage에(린트 우회 빌드) | E2E 누출 `sentinel #5 in storage` | E2E |
| G12 비가시 플래그 | 서버 설계사 조건 제거 + jar 재빌드 | E2E `flows` 2번(설계사 "플래그 없음" 단언) | E2E |
| W14 | 고객 등록에서 `console.debug(이름)`(린트 우회 빌드) | E2E 누출 `sentinel #0 in console` | E2E |

합계 27건, 첫 시도에 놓친 것 0, 무효 0. 의도치 않은 확인 1건(계약 2.8.0 편집 중 잠금 미갱신 → `clientCheck` 실패 — 같은 방어선). 규칙 거짓 양성 2건은 규칙을 좁히지 않고 코드를 옮겼다: API 계층 규칙이 데모 `@Controller`를 잡음 → 함수형 라우터·자원 처리기, `.status` 비교 규칙이 HTTP 코드 판독을 잡음 → 닫힌 허용 파일 `src/shared/http.ts`.

### CI (1차 증거)

PR #12 run [`37968091134`](https://github.com/hjryoo-ai/ga-disclosure/actions/runs/37968091134)(head `740f0ac`, `gh pr checks 12`·아티팩트 내려받아 확인):

| 잡 | 결과 | 시간 | 내용(아티팩트에서 셈) |
|---|---|---|---|
| build | success | 9m41s | JVM 13,689건(모듈별 위 표와 같다) 실패 0·스킵 0, vitest 24건 실패 0 |
| e2e | success | 5m4s | Playwright 12건 실패 0·스킵 0, 누출 스캔 9개 시험 적중 0(콘솔 11·URL 796·헤더 14,325·저장소 19, 센티널 입력 8), 재전송 61(재생 59·재생 불가 2, 테이블 37 행 수 동일), axe 위반 0 |
| pdfa-verify | success | 47s | |
| no-docker | success | 1m33s | Docker 없이 통합 테스트 실패(스킵 아님) |

E2E는 CI 첫 실행에 통과했다(로컬 macOS에서 다듬은 키보드 조작 — 라디오·텍스트 날짜 — 이 Linux Chromium에서도 같다). 보고서 커밋·태그 푸시 뒤의 run은 PR 대화에 적는다.

### CLAUDE.md 규칙 9 기록

도구 출력에 지시문 형태의 문장은 없었다. pdf.js의 Node 경고("Please use the `legacy` build in Node.js environments")는 vitest(jsdom) 실행의 경고이고 지시가 아니다 — 브라우저 번들은 표준 빌드 그대로.

## 4. 데모

- `./gradlew :disclosure-web:e2eDemo` — E2E와 같은 흐름 12건 통과, 스크린샷 27장 `disclosure-web/build/demo/phase7/`(git 무시, 이름은 고정 문자열 — 파일 이름 센티널 0). 스크린샷의 봉인 PDF에는 실행마다 만든 **허구** 고객 이름이 보인다(문서 본문 — 로컬 파일, 업로드하지 않음).
- README "화면 (Phase 7)" 절: 로그인 계정(DEMO1 설계사·관리자·준법 2, DEMO2 준법)과 역할별 둘러보기 순서, 손으로 띄우는 하네스 명령.
- `seed.sh`·`http-demo.sh`는 바꾸지 않았다(6B 그대로).

## 5. 설계서·계획과 달리 구현했거나 해석한 지점

| # | 계획·승인 | 구현 | 이유 |
|---|---|---|---|
| ① | 멱등 키 "결정적"(본문에서 유도) | **의도(버튼 한 번)마다 무작위**, 응답을 못 받은 재시도만 같은 키 | 같은 빈 본문 명령(비교 등)의 두 번째 의도가 낡은 응답 재생이 되고, 고객 등록 본문(개인정보)의 해시는 되돌릴 수 있다(6B D-1). G11 "2회 실행 NOOP"은 같은 키 재전송으로 증명(계획의 "같은 실행 ID로 다시 돌기" 대체) |
| ② | Q3 "고정 전 초안은 ACTIVE 서식 + `pinned:false`" | `pinned`는 언제나 `true`(계약 `const`) | 이 시스템은 초안 생성 때 서식을 고정한다 — 고정 전 초안이 없다(승인 문언의 전제 정정) |
| ③ | Q3 감사 | 서식 조회는 감사 행 없음 | 승인 "감사는 DISCLOSURE_VIEW에 포함(별도 행 없음)" 그대로 — 상세 조회의 감사 규칙이 덮는다 |
| ④ | 승인 조건 ① pdf.js `isEvalSupported:false` | 옵션을 쓰지 않음 | pdfjs-dist 6.4.299에 그 옵션이 없고 빌드에 `eval`·`new Function`이 0 — CSP `unsafe-eval` 없이 돈다. B1c는 서명 번들의 `eval` 주입으로 바꿔 E2E CSP 감시가 잡음 |
| ⑤ | Q6 로그인 "폼" | 폼을 **새 창**으로 열고 콜백 창이 `postMessage` | PKCE 검증자가 직원 화면 메모리에만 있어야 한다(브라우저 저장소 금지) — 화면을 떠나면 잃는다 |
| ⑥ | 설계서 §8 "끝까지 스크롤해야 서명 활성" | 버튼을 막지 않는다 | 판단은 서버(`SCROLL_COMPLETE`), 화면은 거부 코드를 보인다(G3) |
| ⑦ | 설계서 §8 추천사유 체크(보장수준/보험료/가입목적/기타) | **사유 코드 자유 입력**(쉼표) + 설명 | 룰의 사유 코드 목록을 화면에 줄 읽기 경로가 계약에 없다 — 화면 상수로 두면 절대 규칙 4 위반. 무효·폐기·해소·보존 사유 코드도 같다(Phase 8 질문 1) |
| ⑧ | 계획 ④ 와이어의 "자동 회색화"·"관리자 확인 필요" 배지·"반려" | 두지 않음 | 화면 판단이다. "반려" 경로는 계약에 없다 — 무효가 그 자리 |
| ⑨ | 계획의 select 선택 | 작은 enum은 **라디오 묶음** | E2E에서: 닫힌 select는 한글 입력 탐색이 안 되고 macOS Chromium은 화살표가 팝업을 연다 — 키보드 조작이 플랫폼마다 다르다 |
| ⑩ | 날짜 입력 `type=date`·`month` | 텍스트(숫자 자판) | 모바일 날짜 선택기는 키 입력을 받지 않고(모바일 E2E에서 생년월일이 빈 값으로 등록됨), `month`는 세그먼트 입력이 어긋남. 공개 서명은 8자리를 `yyyy-MM-dd`로 옮겨 적는다(표기 변환 — 판정은 서버) |
| ⑪ | 계획 ⑦ DOM 스냅샷에 "공개 서명 머리의 라벨" | 공개 서명 화면에는 서식 라벨 노드가 없다 | 라벨은 PDF 본문에만 있다(화면 문구는 메시지 파일) — 대조할 노드가 없어 직원 상세·비교표만 대조 |
| ⑫ | 직원 화면 문구 "회수율"(작성 중 오기) | "징구율" | 설계서 §6.8 용어 — 커밋 전에 바로잡음 |

## 6. 넓힘·계약 추가 표시 (승인 단서)

| 항목 | 종류 | 내용 |
|---|---|---|
| Q1 | **[넓힘] 인가 표** | `FLAG_READ` AGENT OWN — `visible_to_agent` 행만(저장소 조건). 보안 검토 알림(인가 범위 넓힘)이 이 변경을 짚었다 — 승인된 Q1이다 |
| Q3 | **[넓힘] 계약·경로** | `GET /api/v1/disclosures/{id}/template`, 범위 `DISCLOSURE_READ`, 업무 판단 0(`additionalProperties:false`), `ETag` |
| 미리보기 | 계약 추가(계획 ⑥) | `GET /api/v1/disclosures/{id}/preview.pdf` — 인가·거부는 `getArtifact`와 같다(새 행위 없음) |
| Q5 | **[계약 추가]** 룰 스키마 | GLOBAL 키 `preview.watermark {text, roleLabels}` — 번들 4개 재해시 |
| Q6 | **[계약 추가]** 데모 전용 | `demo-oidc.openapi.yaml` 1.0.0(데모 프로파일만, 운영 인가 표 변화 0) |
| 불변식 | 변화 없음 | 넓힘은 Q1·Q3뿐 |

## D. 결함·공백 기록 (Phase 7)

| # | 분류 | 내용 | 처리 |
|---|---|---|---|
| P7-1 | 시험이 시험을 잡음 | 거부 화면 시험이 진짜 토큰을 `?token=`에 실어 누출 스캔에 걸림 | 가짜 토큰으로(§0 표) |
| P7-2 | 화면 결함, E2E가 찾음 | 모바일에서 생년월일이 빈 값으로 등록(날짜 선택기) → 원격 본인확인 항상 실패 | 텍스트 입력(§5 ⑩) — 단위 시험만으로는 못 찾았다 |
| P7-3 | 남은 공백 | Playwright 실패 시 `error-context.md`(접근성 트리)가 입력 칸 값을 실을 수 있다 | 로컬 `build/`에만, CI 아티팩트 목록에서 뺐다(`test-results/` 미업로드). 끄는 설정은 찾지 못했다 — Phase 8 질문 4 |
| P7-4 | 환경 | 로컬 Docker에 주인 모를 dangling 볼륨 2개 — E2E는 `docker rm -v`로 익명 볼륨을 지우므로 E2E 것이 아닐 가능성이 크지만 확인하지 못했다 | 지우지 않았다(사용자 볼륨 규칙) |
| P7-5 | 표시 | 봉인 PDF의 해약환급예시 칸이 하위 표(`refundWon`·`year` 키 그대로)로 그려진다(3B 렌더러) — 스크린샷에서 봄 | Phase 7 범위 밖, 기록만 — Phase 8 질문 5 |

## 8. Phase 8 질문

1. 룰 데이터의 사유 코드 목록(추천·무효·폐기·해소·보존·해제)을 화면이 읽는 경로(읽기 전용 룰 뷰 — 코드와 룰의 표기)를 둘까 — 지금은 자유 코드 입력(§5 ⑦).
2. 운영 화면 배포: 같은 출처 서빙은 데모 프로파일만 — 운영 분리(도메인·CDN 없는 정적 서빙·CSP 보고 경로)와 IdP 연동.
3. 폰트 번들 4.1MB(Q8) — 저사양 기기 상한 수치를 정할지.
4. Playwright 실패 산출물(`error-context.md`)의 입력값 — 실패 시 입력 칸 값을 지우는 훅을 둘지(P7-3).
5. 해약환급예시 렌더링(P7-5).
6. 6B 이월: 룰 없는 테넌트의 쓰기 500, 게이트 분당 한도가 인스턴스 메모리, 정정 행위자(§14 #20) — 그대로 Phase 8.
