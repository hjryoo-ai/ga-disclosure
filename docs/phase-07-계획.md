# Phase 7 계획 — 화면 (지시문 v1.1)

> 지시문 `docs/phase-07-지시문.md`(v1.1, 2026-10-09 — 협회 서식 원문 확보 건너뜀). 브랜치 `work/phase-7`(main `688cc46`, 첫 커밋 `4620e15` = 6B 수용심사 보관·6B 보고서 D-8·CLAUDE.md 예외 줄). 0단계는 지시문대로 계획 전에 끝냈다(②). **승인 2026-10-10(`docs/phase-07-계획승인.md`) — 아래 "승인 반영"이 본문보다 우선한다.**
>
> 6B 수용 심사의 요구대로 계획 맨 앞에 6B D 표 전체와 "첫 시도에 놓친 것" 표, 6B 보고서 §8의 Phase 7 질문을 붙인다. 넓히는 항목은 **[넓힘]**으로 표시한다.


## 승인 반영 (2026-10-10)

| # | 결정 | 이 계획에서 바뀌는 것 |
|---|---|---|
| Q1 [넓힘] | `FLAG_READ` AGENT OWN 승인 | 필터는 **서버 유스케이스**(`visible_to_agent` 행만) — 화면 필터 0. 시험: ① 룰 기본(11유형 전부 `false`) → 자기 확인서 **200 빈 배열**(404 아님) ② 한 유형만 `visibleToAgent=true`인 룰 변형 → 그 유형만, 나머지 0 ③ 다른 설계사의 확인서 → 404. 설계사는 `GET /api/v1/disclosures/{id}/flags`만(테넌트 큐 `GET /api/v1/flags`는 칸 없음 그대로) |
| Q3 [넓힘] | `GET /api/v1/disclosures/{id}/template` 승인 | 범위 = `DISCLOSURE_READ`(OWN/ORG/TENANT). 응답 = 고정 서식 버전의 라벨 집합(`fields{code,label,required,order,section}`·`layout{title,sections}`) + `templateId`·`version`·`bundleHash`·`pinned`뿐 — 상태·검증·업무 판단 0(계약 `additionalProperties:false`). 초안(고정 전)은 상담일 기준 ACTIVE 서식 + `pinned:false`. `ETag` = 번들 해시. 감사는 `getDisclosure`와 같은 규칙(준법 조회만 `DISCLOSURE_VIEW` 1행, 새 감사 행위 없음) |
| Q4 | `messages.ko.json` 하나 | 화면 문구 + `Problem.code` 문구. **서식 라벨은 넣지 않는다** — 시험: 메시지 값 집합 ∩ 서식 라벨 집합 = ∅. 사전에 없는 코드는 코드 그대로. 한글 리터럴 스캔을 `disclosure-web` 원천까지 — 이 파일 외 0 |
| 스택 | 승인(계획 ③ 그대로) | 조건 ① pdf.js **워커까지 번들**(같은 출처, CDN 0), `isEvalSupported:false` — E2E가 CSP 위반 0(`securitypolicyviolation`·콘솔) 단언, 라이선스 목록에 Apache-2.0 ② **스크롤 완료 = "마지막 페이지가 렌더되어 뷰포트에 들어온 시점", 열람 초 = 단조 시계(`performance.now`)** — 둘 다 `recordView`로 보고(⑤의 `visibilitychange` 정지는 그 위에서 화면이 가려진 동안을 빼는 것) |
| 그 밖 Q2·Q5~Q14 | 권장안 채택 | Q5 GLOBAL 룰 키 `preview.watermark`, Q6 데모 OIDC Authorization Code + PKCE, Q7 TS 5.9.3·openapi-fetch 0.17 등 ⑧ 권장 그대로 |

**넓힘 여부 표시(승인 단서)**: Q1·Q3 외에 계약을 **더하는** 것이 둘 있어 보고서에 따로 표시한다 — Q5 룰 스키마(`contracts/rules/v1`)의 GLOBAL 키 하나 추가(선택 키, 기존 번들 유효), Q6 데모 전용 계약(`demo-oidc`, `prod` 기동 가드 — 운영 인가 표 변화 0). 인가 표·불변식을 넓히는 것은 Q1·Q3뿐.

**B1 주입 추가**: ⓐ 플래그 가시성 필터를 화면으로(서버는 전부 반환) → 서버 시험 실패 ⓑ `template` 응답에 상태·검증 결과 끼워 넣기 → 계약 검증 실패 ⓒ pdf.js를 `isEvalSupported:true`·CSP `unsafe-eval` 없이 → CSP 단언 실패(잡히지 않으면 그대로 보고). G12 최소 8건과 함께 10단계 주입 기록에.

**B2 설계서 v1.17**: Q1·Q3 인가 표·§7 경로, Q4 메시지 파일 규약, 스택 선택 사유 한 문단(§3.2), 0단계 결과와 Phase 1 C5 시험 공백 기록 — 해당 코드와 같은 커밋으로 단계마다.

**모듈 위치**: 설계서 §3.3의 `web/`(자리표시 README 하나, "포털 PWA 저장소에 라우트 추가")를 Gradle 하위 프로젝트 `disclosure-web/`으로 바꾼다 — 지시문 1항(Gradle로 Node 빌드를 감싸 CI 한 번에)과 같은 출처 서빙 때문. 포털 저장소 통합은 Phase 8 이후.

## ① 6B에서 넘어온 것

### 6B 보고서 D 표 (전체, D-8은 수용 뒤 추가)

| # | 분류 | 내용 | Phase 7 영향 |
|---|---|---|---|
| D-1 | 6A 결함, 6B 수정 | 멱등 요청 해시가 키 없는 SHA-256 — 정의역이 작은 본문을 사전 대입으로 되돌릴 수 있었다(`b167beb`) | 화면의 쓰기 요청도 같은 HMAC 해시를 지난다 — 변경 없음 |
| D-2 | 미증명 가설 + 테스트 전용 수정 | 간헐 실패를 연결 재사용 탓으로 보고 테스트 HTTP 클라이언트를 요청마다 새로 만들었다(`7980fa6`), 원인 미증명, 이후 재발 없음 | E2E도 같은 서버를 친다 — 재발하면 포트·연결 로그로 원인을 증명한다 |
| D-3 | 테스트 범위 결함 + 수정 | 거부 코드 표 대조가 일부 계열만 봤다 → `Categorized` enum 전수 양방향(`117707e`) | `Problem.code` 사전 커버리지 시험(G3)이 **같은 전수 목록**을 쓴다 |
| D-4 | 불변식 위반, 데이터 영향 없음 | 6A부터 HTTP 채널이 원 URI 접두로 정해졌다 — 6A 심사 누락 | 화면 서빙 경로(`/`·`/s`·`/assets`)를 더하는 보안 체인도 라우팅 결과·`PathPatternRequestMatcher`로만 판정한다(파서 하나) |
| D-5 | 시험 공회전 + 수정 | 잠금 재적용 검사가 NULL만 보고 통과했다 | 브라우저 누출 스캔은 "센티널이 실제로 입력됐다"를 먼저 단언한다(공회전 방지) |
| D-6 | 시험 출력 | 동적 센티널의 첫 실패가 허구 값을 로컬 보고서 XML에 실었다 | Playwright 실패 메시지·트레이스·스크린샷 이름에 센티널을 싣지 않는다(번호·불리언만) — G5가 스크린샷 파일명까지 본다 |
| D-7 | 계약 ↔ 구현 불일치, 수정 | 모르는 필드를 조용히 버렸다 → 앱 전체 400(R1) | 생성 클라이언트는 계약에 있는 필드만 보낸다 — 계약 시험이 대조 |
| D-8 | 데모 잔존 상태 기록 누락(수용 뒤 추가) | HTTP 데모 첫 시도의 잔존 상태(봉인·고객 서명·관리자 확인 거부·OPEN 플래그·사용된 토큰)를 D 표에 적지 않았다 | E2E·`e2e-demo`의 실패 실행이 남긴 서버 상태는 보고서 D 항목으로 적는다(격리 컨테이너 제거 여부 포함) |

### 첫 시도에 놓친 것

| # | 무엇 | 왜 놓쳤나 | 고친 것 | Phase 7에서 같은 부류 |
|---|---|---|---|---|
| T4 | 배치 코드의 문자열 플래그 유형 — 주입했는데 `FlagTypeTableTest`가 첫 실행에 못 잡음 | 문자열 호출 스캔 범위가 좁았다(스캔 결함) | 스캔을 넓혀 재주입 → 검출 | 0단계 `LabelLiteralScanTest`는 **텍스트 블록·주석·문자 리터럴**을 구분하는 자기 시험을 둔다. 화면 스캔(TS/TSX)도 같은 방식으로 템플릿 리터럴·JSX 텍스트까지 본다 |
| P4 | 보존 재계산 주입 하나가 컴파일 실패 — 시험이 아니라 컴파일러가 막았다 | 주입 자체가 무효 | 세지 않고 P4b로 재실행 | 주입 기록에 "컴파일 실패 = 무효"를 그대로 적는다. TS 주입은 `tsc`·ESLint가 막는 것과 시험이 막는 것을 구분해 적는다 |
| (엔진 E3.2 J2) | 계약만 바꾼 주입이 시험을 돌리지 못했다 — Gradle 입력 미선언 | 빌드 입력 결함 | 입력 선언 | `disclosure-web`의 Gradle 태스크는 계약 디렉터리·`package-lock.json`·원천 전체를 **입력으로 선언**한다(계약만 바뀌어도 생성·시험이 다시 돈다 — 주입으로 확인) |

### 6B 보고서 §8 Phase 7 질문 — 지금 상태

| # | 질문 | 상태 |
|---|---|---|
| 1 | 협회 표준서식 라벨(§14 #2) | **지시문 v1.1로 답함** — 건너뜀, 예시 라벨 유지, 새 서식 버전으로 교체 가능함을 0단계가 증명 |
| 2 | 중복 가명 병합(§14 #22) | 미결정 유지. 화면은 등록 응답의 가명만 보여 주고 이름을 다시 보이지 않는다(지시문) — 병합 경로 없음 |
| 3 | 정정의 행위자(§14 #20) | 화면에 정정 경로를 두지 않는다(운영자 CLI만) — 권장, 질문 Q14 |
| 4 | 관리자의 플래그 확인 흐름 | 관리자 확인 화면이 그 확인서의 플래그 목록(`listDisclosureFlags`)을 보여 주고 확인한 ID를 `acknowledgedFlags`로 보낸다 — Q2 |
| 5 | 룰 없는 테넌트의 쓰기 POST 500 | Phase 8(테넌트 온보딩 순서) — Q14 |
| 6 | 숫자 → 문자열 강제 변환 | 그대로 둔다. 생성 클라이언트는 계약 타입(문자열)으로만 보낸다 |
| 7 | 게이트 분당 한도가 인스턴스 메모리 | Phase 8 — Q14 |
| 8 | 계약 피드의 `JOB_READ` | 화면 범위 밖 — 그대로 |
| 9 | 징구율 정의(§14 #17) | 화면 표기는 응답의 정의 표기 그대로("내부 지표 — 규제 정의 없음") |
| 10 | 산식 불변 재계산(§14 #21) | 화면 범위 밖 — 그대로 |

## ② 0단계 결과 (끝냄, 승인 전 커밋 예정)

| 산출물 | 내용 | 증명 |
|---|---|---|
| 가상 새 버전 `disclosure-infra/src/integrationTest/resources/rule-as-data/templates/STANDARD-v2-alt-labels.bundle.json` | v1과 **식별부 라벨 4개와 문서 제목만** 다르다("…(시험 변형)"), `supersedes STANDARD v1`, `applyFrom 2026-09-01`, `TODO(confirm#2)`·`pendingConfirmation` 그대로, 번들 ID = 새 본문 해시 | 본문 diff 5줄(라벨 4·제목 1) |
| `TemplateVersionsIT`(infra, 3) | ① 배포 전 봉인한 문서 A: 배포 뒤에도 저장 PDF 바이트 불변, DB의 v1로 다시 렌더하면 저장본과 바이트 동일, 새 라벨 없음, 배포 **뒤에** 완료한 서명본도 봉인 PDF가 바이트 접두·새 라벨 없음 ② 배포 뒤 문서 B: v2에 고정, PDF 본문(PDFBox 텍스트)에 새 라벨 5개, v2로 다시 렌더하면 바이트 동일 ③ 공존: 상담일 2026-08-31 → v1, 2026-09-01 → v2 ④ **골든 PDF 3건 + 서명본 골든을 DB에서 읽은 v1로 렌더해도 커밋된 SHA-256과 같다** ⑤ 같은 버전 다른 본문(v1·v2 각각, 번들 ID는 유효하게 재계산)은 `a changed template needs a new version`으로 거부되고 `form_template` 행 불변, 같은 번들 재배포는 NOOP | 3/3 통과 |
| `LabelLiteralScanTest`(archTest, 3) | ① 모든 서식 번들(운영·시험 3개)의 문구 — 항목 라벨·산출불가 표기·제목·섹션 라벨·서명 페이지 문자열(라벨 참조 표식 제외) — 와 같은 문자열 리터럴이 운영 코드에 0건 ② 한글 문자열 리터럴이 있는 운영 파일 = 닫힌 목록 18개(사유 — 검증 메시지 13, 검증 보고서 문장, 징구율 정의 표기, 임시등록 표기, 단위 '원', 영문 예외 속 규칙 이름 2) — 새 파일·빠진 항목 모두 실패 ③ 리터럴 판독기 자기 시험(주석·문자 리터럴 제외, 텍스트 블록 포함) | 3/3 통과 |
| 설계서 v1.16 | §6.4 4항(라벨은 새 서식 버전으로만 바뀐다), **§14 #2 미결정 유지 + 원문 확보 시 절차**(원문 미커밋·출처 기록, 전사·대응표, 공백 목록, 새 번들 배포 = 코드 0, 이전 골든 유지·새 골든 추가, 새 바인딩만 별도 승인) | — |
| 빌드 | infra 통합 시험에 PDFBox(렌더러와 같은 좌표 `openhtmltopdf-pdfbox`), 락 파일은 그 좌표들을 컴파일 클래스패스에 더한 것뿐(버전 변화 0) | — |

**위반 주입(→ 실패 → 제거)**

| # | 주입 | 결과 |
|---|---|---|
| G0a | `TemplateResolver.load`가 고정 버전 대신 같은 서식의 최신 버전을 돌려줌 | `TemplateVersionsIT` 2건 실패(문서 A 재렌더, 골든) |
| G0b | 같은 버전 다른 본문을 조용히 NOOP로 받음 | 1건 실패(제자리 수정 거부) |
| L1 | 렌더러에 `"고객명"` 상수 | `LabelLiteralScanTest` 2건 실패(라벨 리터럴 — 파일·값 표시, 한글 목록) |
| L2 | 워크플로에 라벨 아닌 한글 문구 상수 | 1건 실패(목록 밖 파일) |
| L3 | 허용 목록에서 한 항목 삭제(파일은 그대로) | 1건 실패 |

**지시문과 다르게 한 것·전제 정정**
- 가상 서식 파일 이름·위치: 지시문 `forms/test-alt-labels.json` → 저장소 규약(서식은 `*.bundle.json` 번들, 시험 픽스처는 `rule-as-data/templates/`, `BundleFiles.fixture`로 읽는다). `forms/` 디렉터리는 없다.
- "제자리 수정은 Phase 1 C5가 거부"의 재확인: Phase 1 C5 시험(`RuleDistributionIT`)은 **룰 번들만** 다뤘고 서식의 "같은 버전 다른 해시" 거부는 시험이 없었다. 이번에 `TemplateVersionsIT`가 서식 판을 처음 시험한다(DB 가드 GD050은 `FormTemplateGuardIT`에 이미 있었다).
- "서식 데이터(출처 기록 포함)": 서식 스키마에 라벨 출처 필드가 없다 — 출처는 번들 단위(`source_bundle_id`·`bundle_hash`)와 `pendingConfirmation.note`뿐이다. Q10.
- 운영 코드의 한글 리터럴 76개(18파일)는 라벨이 아니라 문장·단위다(서식 문구와 같은 리터럴 0). Phase 7은 이 목록을 닫아 두고 늘리지 않는다 — 옮길지는 Q9.

## ③ 프런트엔드 스택

**왜 이것인가.** 직원 화면(설계사·관리자·준법)은 **React 19 + Vite + TypeScript(strict)**, 고객 서명 화면은 **프레임워크 없는 TypeScript**(같은 Vite 빌드의 두 번째 진입점)로 나눈다. 직원 화면은 표·폼·목록이 많고 상태 전이 결과를 서버 응답 그대로 다시 그리는 화면이라, 컴포넌트 시험(Testing Library)·접근성 도구(axe)·타입 생성 클라이언트와의 결합이 가장 검증된 React가 맞다 — 서버 상태를 화면이 계산하지 않으므로 상태 관리 라이브러리는 두지 않는다(요청 → 응답 → 그리기). 고객 서명 화면은 지시문대로 라우터·상태 관리·인증 라이브러리 0이고 단계가 5개뿐이라 프레임워크 없이 DOM API로 쓴다(§8 "JS 최소"). PDF를 직접 그려야 스크롤 완료를 잴 수 있으므로(브라우저 내장 뷰어는 스크롤을 알려 주지 않는다) 그 화면만 **pdf.js를 번들**한다(CDN 아님, 같은 출처의 워커, `isEvalSupported:false`로 CSP의 `unsafe-eval` 없이).

| 구분 | 도구 | 안정판(2026-10-09 조회, 착수 때 다시 확인·고정) | 라이선스 |
|---|---|---|---|
| 런타임 | Node.js | 24.21.0 LTS(Krypton) — Gradle Node 플러그인이 내려받아 고정 | MIT |
| 빌드 감싸기 | `com.github.node-gradle.node` | 7.1.0 — Gradle 9.8 호환을 1단계에서 먼저 확인, 안 되면 Gradle `Exec` + 고정 Node(같은 버전) | Apache-2.0 |
| 패키지 | npm(Node 동봉) + `package-lock.json`, `npm ci` | 11.x | Artistic-2.0 |
| 언어 | TypeScript | **5.9.3** — 7.0.2가 최신이지만 typescript-eslint(`<6.1`)·openapi-typescript(`^5`)의 피어 범위 밖. 피어를 덮어쓰지 않는다 | Apache-2.0 |
| UI | react, react-dom | 19.3.0 | MIT |
| 라우팅(직원 화면만) | react-router | 8.4.0 | MIT |
| 번들러 | vite, @vitejs/plugin-react | 8.3.x, 6.1.x | MIT |
| 계약 클라이언트 | openapi-typescript(생성) + openapi-fetch(타입 실행기) | 7.13.0, 0.17.0(1.0 전 — Q7) | MIT |
| PDF 표시 | pdfjs-dist | 6.4.299 | Apache-2.0 |
| 단위·컴포넌트 시험 | vitest, @testing-library/react·user-event, jsdom | 5.0.x, 16.3.x·14.6.x, 30.1.x | MIT |
| E2E | @playwright/test(Chromium) | 1.64.0 | Apache-2.0 |
| 접근성 | @axe-core/playwright, axe-core | 4.13.0, 4.14.0 | MPL-2.0(시험 전용 dev 의존 — 배포 산출물에 없다) |
| 린트 | eslint, typescript-eslint | 10.12.0, 8.71.x | MIT |
| 폰트 | NanumGothic Regular·Bold(렌더러와 **같은 파일**, `disclosure-seal` 자원) | — | OFL-1.1 |

- `tsconfig`: `strict`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`, `noImplicitOverride`. ESLint: `@typescript-eslint/no-explicit-any`·`no-unsafe-*` 오류, `any` 0을 `tsc` 산출과 린트로 이중 확인.
- **수기 fetch 금지**: ESLint `no-restricted-globals`(`fetch`·`XMLHttpRequest`·`WebSocket`·`EventSource`)와 `no-restricted-syntax`(`navigator.sendBeacon`, `import()`의 외부 URL) — 예외는 생성 클라이언트를 만드는 모듈 파일 하나(FQN 열거). 저장소 API(`localStorage`·`sessionStorage`·`indexedDB`·`document.cookie`·`caches`)도 같은 규칙으로 금지(예외 없음).
- **생성 결정론**: 계약 4개(`disclosure-api`·`disclosure-public`·데모 인증 — Q6)에서 `src/gen/`(git 무시)로 생성. 커밋하는 것은 `contracts-client.lock.json` 하나 — `{계약 파일 SHA-256, 생성기 버전, 생성물 SHA-256}`. 빌드가 다시 생성해 ① 계약·생성기가 같은데 생성물이 다르면 실패 ② 계약이 바뀌었는데 잠금 파일이 그대로면 실패(갱신은 `./gradlew :disclosure-web:clientLock`, 커밋에 드러난다). 계약에서 경로·필드가 빠지면 그것을 쓰는 화면 코드가 `tsc`에서 실패(drift = 빌드 실패).
- 의존성: 정확한 버전 고정(`^`·`~` 없음), `npm ci`, `npm audit` 대신 **OSV 조회**(락 파일 전체 — 엔진 E3.2와 같은 방식)와 라이선스 목록을 `disclosure-web/THIRD-PARTY.md`로. 허용 라이선스는 MIT·Apache-2.0·BSD·ISC·OFL·0BSD·BlueOak, 그 밖은 시험이 실패시키고 사유와 함께 열거(MPL-2.0은 dev 전용만).
- 런타임 CDN·서드파티 스크립트·분석 도구 0: 산출 HTML에 외부 출처 URL 0(빌드 산출 스캔) + E2E 네트워크 로그의 외부 출처 요청 0(G9).

## ④ 화면 목록과 계약 경로

모든 호출은 생성 클라이언트. `*` = 멱등 키(화면이 단계마다 결정적으로 만든다 — 같은 화면 동작의 재시도는 재생, 6A 규약).

**공통**: 로그인(데모 OIDC, Q6), 확인서 목록 `listDisclosures`(역할 범위는 서버가 건다), 확인서 상세 `getDisclosure` + **서식 라벨 `getDisclosureTemplate`(신설 — Q3)**, PDF 미리보기 `getPreviewPdf`(신설, ⑥), 오류 = `Problem.code` 사전(⑤ 아래).

| 역할 | 화면 | 계약 경로(operationId) |
|---|---|---|
| AGENT | 고객 등록(응답은 가명·영수증뿐, 제출 뒤 입력값을 지운다) | `registerCustomer*` |
| AGENT | 초안 작성 5단계(§8): 고객·상품군 → 비교표 → 등급·순위(읽기 전용) → 추천사유(**설계사 입력만**, 자동완성 `autocomplete="off"`·예시·placeholder 문구 0) → 검증·봉인 | `createDisclosure*`, `searchCatalogProducts`, `replaceItems*`, `compare*`, `requestGrades*`, `setRecommendations*`, `validate*`, `seal*`, `abandonDraft*`, `rebase*` |
| AGENT | 검증 결과(서버의 결과 목록 그대로) + 플래그(`visibleToAgent`만 — Q1) | `validate*` 응답, `listDisclosureFlags` |
| AGENT | 서명: 세션 발급 → TOUCH_PAD는 현장 서명(고객 서명 화면을 같은 기기의 새 창에서 — 토큰은 프래그먼트, Q13)·대면 확인, REMOTE_LINK는 "발송 대기" 상태만, 설계사 서명 | `issueSignSession*`, `confirmFaceToFace*`, `agentSignature*`, `uploadPaperScan*` |
| AGENT | 자기 확인서 무효 | `voidDisclosure*` |
| MANAGER | 조직 확인서 목록·상세, **플래그 목록·확인** → 관리자 확인(서명 또는 승인 — 룰 그대로, 확인한 플래그 ID = `acknowledgedFlags`) | `listDisclosures`, `listDisclosureFlags`, `listFlags`, `assignFlag*`, `managerConfirmation*` |
| MANAGER | 종이 스캔 검토, 예외 승인, 무효, 완료, 징구율(조직) | `paperScanReview*`, `approveException*`, `voidDisclosure*`, `complete*`, `listCollectionRates` |
| COMPLIANCE | 준법 큐(유형·SLA·담당·해소). `CHAIN_BROKEN`은 해소 버튼 없이 "검증 실행 → MATCH 확인 → 해소"(검증 작업 ID를 근거로) | `listFlags`, `assignFlag*`, `resolveFlag*`, `submitJob*`(VERIFY_TENANT), `getJob`, `getJobReport` |
| COMPLIANCE | 법적 보류 설정·해제(4-eyes 안내 — 설정자와 다른 준법만 해제, 서버 거부 코드 그대로) | `listLegalHolds`, `placeLegalHold*`, `releaseLegalHold*` |
| COMPLIANCE | 검증 작업·보고서, 영수증 내보내기, 파기 dry-run 보고서 열람(스케줄러가 만든 작업의 보고서), 보존 재계산(dry-run 기본, 적용은 확인 단계 뒤) | `listJobs`, `submitJob*`, `getJob`, `getJobReport`, `getAnchorReceipt` |
| COMPLIANCE | 징구율(전체, 정의 표기 그대로), 확인서 검색·열람·증거 패키지 | `listCollectionRates`, `listDisclosures`, `getArtifact`(EVIDENCE_ZIP) |
| CUSTOMER(`/s`) | 상태 → 열람(PDF, 스크롤·초) → 본인확인 → 서명 → 완료 / 거부 한 화면 | `sessionStatus`, `openSigningDocument`, `recordView`, `verifyIdentity`, `captureSignature`(전부 `X-Sign-Token`) |

- **화면에 업무 판단 없음(G3)**: 버튼은 상태로 숨기지 않는다 — 누르면 서버가 거부하고 화면은 코드를 보여 준다. 예외는 **서버가 준 사실을 그대로 보이는 것**뿐(예: 응답의 `status` 표시). 린트: 상태 enum 값과의 비교(`=== 'SEALED'` 등)·`rejections` 계산을 컴포넌트에서 금지하는 `no-restricted-syntax` 규칙 + 화면 코드 스캔 시험(상태·검증 코드 리터럴 목록은 계약에서 읽는다).
- **문구**: 화면 문구(버튼·제목)와 `Problem.code` 문구는 **단일 카탈로그 파일** `messages.ko.json`(키 → 문구) 하나에만 — Q4. 사전에 없는 코드는 코드 그대로 표시. 거부 문구에 고객 정보를 넣는 자리(치환자)가 없다.
- **`Problem.code` 사전 커버리지(G3)**: 최상위 16(`Problem` enum)·공개 2(`SIGN_LINK_UNAVAILABLE`·`REJECTED`)·거부 76(설계서 `rejection-categories` 블록 = `Categorized` enum 전수) ↔ 카탈로그 `problem.*` 키 양방향(빠진 코드·남은 키 모두 실패).

## ⑤ 공개 서명 화면 분리

- **별도 번들**: Vite 다중 진입점 — `staff/index.html`(React)과 `sign/index.html`(프레임워크 없음). 공개 번들의 의존 그래프에 react·react-router·인증 코드가 없음을 빌드 산출 매니페스트로 단언(모듈 목록 스캔). 공유 코드는 `shared/`의 순수 모듈(생성 클라이언트 중 공개 계약분, 카탈로그 중 `sign.*`·`problem.SIGN_LINK_UNAVAILABLE`·`problem.REJECTED`)만.
- **토큰**: `location.hash`에서 읽어 모듈 지역 변수에만 두고 즉시 `history.replaceState(null, '', '/s')`. 모든 요청은 `X-Sign-Token` 헤더(계약대로 — 본문 `token`은 쓰지 않는다; 지시문의 "본문으로"는 계약상 헤더가 우선이고 쿼리는 거부되므로 헤더로 통일, G4). 저장소 API 0(린트 + E2E에서 `localStorage`·`sessionStorage`·쿠키·IndexedDB 목록 단언).
- **열람**: pdf.js로 쪽마다 캔버스(스크롤 컨테이너), **스크롤 완료 = 마지막 페이지가 렌더되어 뷰포트에 들어온 시점**(승인 조건 ②), 열람 초 = 단조 시계 `performance.now` 경과(화면이 가려진 동안은 `visibilitychange`로 멈춤) → `recordView{scrollComplete, viewSeconds}`. PDF는 `openSigningDocument`의 바이트 그대로(워터마크 없음 — G7이 바이트 해시 = 봉인 PDF 단언).
- **본인확인**: 입력값은 제출 직후 입력 요소와 변수 모두 비운다(`value=''`, 참조 해제). 콘솔·오류 보고 경로 없음(전역 `console` 사용을 린트로 금지, 공개 번들).
- **서명**: Pointer Events로 스트로크 `[[{x,y,t}]]`(t = 첫 점부터 ms, Phase 4 형식), 캔버스 PNG `imagePngBase64`, 기기 정보는 계약의 `deviceFingerprint` 문자열 하나만(지시문의 "Phase 4 `device` 형식"은 계약에 없다 — 서버가 User-Agent를 헤더에서 직접 읽는다).
- **거부**: 404 `SIGN_LINK_UNAVAILABLE`과 그 밖의 실패(네트워크 포함)는 사유를 나누지 않는 **한 화면**. 422 `REJECTED`(예: 본인확인 불일치)는 단계 안 안내 — 코드 → 카탈로그 문구.
- 헤더: `/s`·`/s/**` `Cache-Control: no-store`, `Referrer-Policy: no-referrer`, CSP(아래).

## ⑥ 미리보기 워터마크

- **계약**: `GET /api/v1/disclosures/{disclosureId}/preview.pdf` → 200 `application/pdf`(`Cache-Control: no-store`, `Content-Disposition: inline`), 거부는 산출물 열람과 같다(409 `REJECTED` + `NO_ARTIFACT` 등, 404 인가). `disclosure-api` 2.8.0.
- **유스케이스**: `ArtifactService.preview(caller, id)` — `@UseCaseEntry(ARTIFACT_VIEW)`, 원본은 **봉인 PDF**(`PDF` 종류 — 서명본이 있어도 봉인본에 표시, 지시문 "봉인 PDF 위에"), 기존 열람과 같은 복호·해시 검증 뒤 워터마크. 감사 `ARTIFACT_VIEW {disclosureId, kind: PDF, sha256(원본), reason: PREVIEW}` — 지금 `reason`은 서명 열람의 문자열 `"SIGN"` 하나뿐이라 **목적 enum `ArtifactViewPurpose {SIGN, PREVIEW}`**을 두고 기존 `"SIGN"`도 그 enum으로(감사 값 불변).
- **렌더**: `seal.renderer.PreviewWatermarker`(PDFBox 직접 편집 — `SignedPdfAppender`와 같은 허용 패키지) — 쪽마다 대각선 반투명 텍스트, 폰트는 렌더러의 NanumGothic(임베드), 문구 = `{열람용 표기} · {역할 표기} · {시각 KST yyyy-MM-dd HH:mm}`. PDF/A 요구 없음, **저장 안 함**(응답 스트림으로만), 결과 바이트는 결정론이 아니어도 된다(시각).
- **문구 출처**: Q5(권장 — GLOBAL 룰 키 `preview.watermark {text, roleLabels}`, 치환자는 닫힌 집합 `{role}`·`{at}`).
- **시험 `PreviewWatermarkIT`(G7)**: 미리보기 텍스트에 워터마크 문구 + 원본 쪽 수 같음 / 미리보기 전후로 `getArtifact` PDF·SIGNED_PDF·EVIDENCE_ZIP 바이트 해시 불변 / 저장소 객체 수·`document_record` 불변(저장 안 함) / 공개 `openSigningDocument` 바이트 = 봉인 PDF(워터마크 없음) / 감사 `reason=PREVIEW` / 인가 표 그대로(AGENT OWN·MANAGER ORG·COMPLIANCE TENANT, 그 밖 404). 주입: 산출물 엔드포인트에 워터마크(G12).

## ⑦ E2E 전략 (데모 프로파일 위)

- **환경**: Gradle 태스크 `:disclosure-web:e2e`가 격리 컨테이너(PostgreSQL 18.6·SeaweedFS digest 고정, 임의 포트 — 사용자 compose 볼륨과 무관)를 띄우고 `seed.sh`로 데모 데이터를 넣은 뒤 부트 jar(`demo` 프로파일, 엔진·TSA·버킷 스텁 — `http-demo.sh`와 같은 구성)를 같은 출처로 서빙, Playwright(Chromium 데스크톱 + 모바일 뷰포트) 실행, 끝나면 컨테이너 제거. Docker가 없으면 **실패**(스킵 아님). 개인정보 입력값은 실행마다 무작위 허구 값을 `build/e2e/sentinels.json`(git 무시)에 쓰고 시험이 그 파일을 읽어 화면에 입력한다(CLI·환경변수에 없음).
- **흐름(G11)**: 설계사(고객 등록 → 초안 5단계 → 봉인 → TOUCH_PAD 발급 → 현장 고객 서명(공개 화면) → 설계사 서명) / 관리자(플래그 확인 → 관리자 확인 → 완료) / 준법(큐 → 검증 작업 → 보고서 → `CHAIN_BROKEN` 흐름은 seed의 DEMO2 사례, 보류 설정·해제 4-eyes, 재계산 dry-run) / 공개 서명 전체 흐름 1(REMOTE_LINK 토큰 — 데모 통지 포트의 링크) / 거부 화면 1(쓰인 토큰·틀린 토큰·쿼리 토큰 → 같은 화면). **2회 실행**: 같은 실행 ID로 다시 돌면 서버 상태 NOOP(쓰기 응답 `Idempotent-Replayed` 헤더 + 행 수 대조 — psql로 실행 전후 테이블별 행 수).
- **누출 스캔 `WebLeakScanE2E`(G5)**: 동적 센티널(실제로 입력한 이름·전화(하이픈 유무)·생년월일(두 형식)·서명 토큰) — 콘솔 메시지 전부, 페이지 오류, 네트워크 요청 URL·요청/응답 헤더, `localStorage`·`sessionStorage`·쿠키·IndexedDB 데이터베이스 목록, 스크린샷 파일명, Playwright 트레이스 끔. 먼저 "센티널이 실제로 입력됐다"(입력 이벤트 수)를 단언(D-5 교훈). 실패 메시지는 센티널 번호만(D-6 교훈).
- **CSP·외부 요청(G9)**: 모든 응답 헤더 단언 + 네트워크 로그의 출처가 전부 앱 출처. **접근성(G10)**: 화면마다 axe(`wcag2a`·`wcag2aa`·`wcag21a`·`wcag21aa`) 위반 0, 예외는 `e2e/axe-exceptions.json`(규칙·대상·사유) — 시험이 목록 밖 위반·안 쓰인 예외 둘 다 실패. 키보드 흐름: 서명 패드 외 전 흐름을 `Tab`/`Enter`/`Space`만으로.
- **DOM 스냅샷**: 확인서 상세·초안 비교표·공개 서명 머리의 라벨 노드를 서식 데이터(`getDisclosureTemplate` 응답)와 대조 — 시각 회귀 없음.
- **CI**: 새 잡 `e2e`(Docker + Chromium 내려받기) — PR과 main에서(Q12). 보고서는 Playwright HTML 리포트·axe 결과·누출 스캔 요약을 아티팩트로.

## 서버 쪽 추가 (작게)

1. **화면 서빙 체인**(데모 프로파일만): `@Order` 새 체인 — `GET /`, `/staff/**`, `/s`, `/assets/**`(해시 이름, `Cache-Control: public, max-age=31536000, immutable`), HTML은 `no-store`. 헤더: `Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; font-src 'self'; connect-src 'self'; worker-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'`, `Referrer-Policy: no-referrer`, `X-Content-Type-Options: nosniff`. 운영 분리는 Phase 8. 매칭은 `PathPatternRequestMatcher`(파서 하나 — D-4).
2. `GET /api/v1/disclosures/{id}/preview.pdf`(⑥), `GET /api/v1/disclosures/{id}/template`(Q3), FLAG_READ 설계사 칸(Q1) — 계약 `disclosure-api` 2.8.0, 인가 표·`AuthzMatrixTest`.
3. 데모 OIDC 브라우저 로그인(Q6, 데모 프로파일만, `prod` 기동 가드).
4. 서식 응답·워터마크 문구가 서버 코드 리터럴이 아님을 `LabelLiteralScanTest`가 계속 본다.

## 단계(§11 순서) — 단계마다 시험·주입·설계서 같은 커밋

| 단계 | 내용 | 완료 기준 |
|---|---|---|
| 0 | 서식 교체 가능성(끝남) | G0, G1(서버) |
| 1 | `disclosure-web` 모듈·Node 고정·락·린트·생성 클라이언트·잠금 파일·라이선스·OSV, `LabelLiteralScanTest`를 TS/TSX로 확장 | G1(화면)·G2·G12 일부 |
| 2 | 서버: 서빙 체인·헤더, 계약 2.8.0(미리보기·서식 라벨·설계사 플래그), 데모 로그인 | G7·G9(서버 헤더) |
| 3 | 카탈로그 `messages.ko.json` + 커버리지 시험 | G3(사전) |
| 4 | 공개 서명 번들 | G4·G5(일부) |
| 5 | 설계사 화면 | G6·G8(설계사) |
| 6 | 관리자 화면 | G8(관리자) |
| 7 | 준법 화면 | — |
| 8 | E2E·누출 스캔·axe·키보드·DOM 스냅샷·2회 실행, CI 잡 | G4·G5·G9·G10·G11 |
| 9 | `e2e-demo`·README(로그인 계정·역할별 둘러보기) | G11(데모) |
| 10 | 전체 check·주입 기록·보고서·태그 `phase-7` | G12 |

## ⑧ Phase 7 질문

| # | 질문 | 권장 |
|---|---|---|
| Q1 **[넓힘]** | 설계사 화면의 "플래그 중 `visibleToAgent`만": 지금 설계사는 플래그를 읽을 길이 없다(FLAG_READ에 설계사 칸 없음, `Flag` 스키마에 `visibleToAgent` 없음). 6B 계획 그대로 **FLAG_READ에 AGENT OWN을 열고 저장소가 `visible_to_agent = true`만** 돌려주게 할지 | 연다(인가 표 한 칸 — 넓힘). 지금 룰 데이터는 11유형 전부 `false`라 설계사에게 보이는 플래그는 0 — E2E는 한 유형만 `true`인 시험 룰 변형으로 "보이는 것만" 증명 |
| Q2 | 관리자의 플래그 "확인(acknowledge)": 별도 엔드포인트가 없고 `managerConfirmation.acknowledgedFlags`(그 확인서 플래그 전부 필수)가 확인이다 | 새 엔드포인트 없이 확인 화면의 체크 목록 → `acknowledgedFlags` |
| Q3 **[넓힘]** | 화면이 서식 라벨을 받을 길이 없다(어느 응답에도 라벨 없음). `GET /api/v1/disclosures/{id}/template`(고정 버전의 `fields{code,label,required,order,section}`·`layout{title,sections}`) 신설 | 신설(DISCLOSURE_READ 범위, 계약 2.8.0). 라벨은 그 확인서의 **고정 버전**에서 — G0과 같은 원칙 |
| Q4 | 화면 문구(버튼·제목)의 출처: 지시문은 "서식 데이터 또는 `Problem.code` 사전"뿐 | `Problem.code` 사전을 **단일 카탈로그 파일 하나**(`problem.*`·`ui.*`·`sign.*` 키)로 — 한글은 그 파일에만, 코드에는 키만. G1 스캔의 예외는 그 파일 하나 |
| Q5 | 워터마크 문구("열람용 · {역할} · {시각 KST}")와 역할 표기의 출처 | GLOBAL 룰 키 `preview.watermark`(번들 재해시 — 6B 10단계와 같은 방식). 대안: 서버 상수(한글 허용 목록에 사유와 함께) |
| Q6 | 직원 로그인: 데모 OIDC는 지금 CLI 발급뿐 | 데모 프로파일에 Authorization Code + PKCE(`/demo/oidc/authorize` — JS 없는 주체 선택 폼, `/demo/oidc/token`), 그 두 경로를 **데모 전용 계약**으로 두어 화면이 생성 클라이언트로 부른다(수기 fetch 0). 토큰은 메모리에만(새로 고침 = 다시 로그인 — 브라우저 저장 금지). PKCE는 WebCrypto 수십 줄(인증 라이브러리 0). 운영 IdP 연동은 Phase 8 |
| Q7 | TypeScript 7.0.2(최신)가 린트·생성기 피어 범위 밖 → 5.9.3. openapi-fetch는 0.x | 5.9.3으로 고정, 피어 덮어쓰기 없음. openapi-fetch 0.17 수용(생성 타입이 정본이고 실행기는 얇다) — 대안은 생성기가 실행 코드까지 만드는 도구 |
| Q8 | 폰트 번들 = PDF와 같은 NanumGothic TTF 2개(약 4.1MB) — §8 "저사양 기기 번들 상한" | 바이트 그대로 서빙(같은 폰트 보장, 해시 이름·장기 캐시). 서브셋은 하지 않는다(다른 파일이 된다) — 상한 수치가 필요하면 지금 정한다 |
| Q9 | 운영 코드의 한글 리터럴(검증 메시지 13파일 등, 76개)은 라벨이 아니라 문장이다 | Phase 7은 닫힌 목록으로 동결. 검증 메시지를 데이터로 옮기는 일은 Phase 8 이후 별도 |
| Q10 | "서식 데이터(출처 기록 포함)" — 서식 스키마에 라벨 출처 필드가 없다 | 지금은 번들 출처(`source_bundle_id`·`bundle_hash`)와 §14 #2 절차의 출처 기록(저장소 밖 원문의 서지 정보)으로 둔다. 필드가 필요하면 원문 확보 때 스키마에 선택 필드로(새 버전부터) |
| Q11 | 가상 서식 파일 이름·위치(②) | 저장소 규약 그대로 |
| Q12 | E2E CI 잡(Docker + Chromium) 실행 범위 | PR과 main(풀 빌드와 같은 조건) |
| Q13 | TOUCH_PAD 현장 서명: 직원 화면이 발급 응답의 토큰으로 고객 서명 화면(`/s#…`)을 같은 기기의 새 창에서 연다 | 그렇게(공개 번들 재사용, 토큰은 프래그먼트로만 넘김 — 직원 화면 저장소·URL에 남지 않음) |
| Q14 | 6B §8 이월: 정정 화면 경로(#3) 없음, 룰 없는 테넌트 500(#5)·게이트 한도 메모리(#7)는 Phase 8 | 그대로 |
