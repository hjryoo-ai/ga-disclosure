# Phase 8 계획 — 배포·운영·정비·포트폴리오 문서 (지시문 v1.0)

> 지시문 `docs/phase-08-지시문.md`(v1.0, 2026-10-10). 브랜치 `work/phase-8`(main `9b7f2f1` = PR #12 병합, `phase-7` 태그 도달 확인). 첫 커밋 = Phase 7 수용심사 보관 + 이 지시문 + 이 계획. **승인 전에는 구현하지 않는다.**
>
> 7 수용심사 §0 요구대로 맨 앞에 Phase 7 보고서 §5 전체와 넓힘·계약 추가 표를 붙인다. 이 계획의 넓힘·계약 추가·규칙 해석은 **[넓힘]**·**[계약 추가]**·**[규칙 충돌]**로 표시한다.

## 승인 반영 (2026-10-10, `docs/phase-08-계획승인.md`) — 아래 본문보다 우선한다

| # | 결정 | 이 계획에서 바뀌는 것 |
|---|---|---|
| Q1 | 전용 롤 `disclosure_health` | 본문 그대로 |
| Q2 | 테넌트 KEK 지금 | **`tenant_kek` 레지스트리**(V21 — 테넌트 → 키 ID·상태 CURRENT/RETIRED, 키 바이트는 `SecretSource`). `KEK_REWRAP`은 **행마다 감사**(본문의 "작업당 1행"을 바꿈)·멱등·재개 가능·dry-run 기본. 두 커밋으로 나눈다: ⓐ 이중 읽기(감싼 행의 KEK ID가 레지스트리에 있으면 테넌트 키, 아니면 옛 전역 키) + 전역 → 테넌트 재래핑 ⓑ 이행 증명 뒤 **전역 키 읽기 경로 제거**(코드 스캔: 옛 설정 키·클래스 참조 0, `verify tenant`가 레지스트리에 없는 KEK ID로 감싼 행을 발견으로 보고 — 전역 키 ID 참조 0). 운영 배포는 ⓐ로 올려 재래핑을 끝낸 뒤 ⓑ로 올리는 **2단 업그레이드**(런북). 완료 = 전 테넌트 `verify tenant` MATCH + 서명 증거(ZIP) 복호화 + E2E. G5 KEK 회전 = 이 배치의 두 번째 실행(테넌트 키 v1 → v2) |
| Q3 | `disclosure_backup`(REPLICATION·LOGIN·NOSUPERUSER·테이블 권한 0), 백업 Job에만 | 백업은 별도 백업 키로 암호화(본문 그대로), 백업 저장소 Object Lock·보존 ≥ 룰 보존. **시험**: 그 롤로 앱 DB 일반 세션 SELECT 전수 거부 |
| Q9 | 네 가지 변경 | 렌더러 1 코드 **동결**(바꾸면 기존 골든 실패 = 검사), `renderer_version` 봉인 시 고정·**write-once**(트리거), 매니페스트 2판 추가형 + `verify package`가 1판·2판 둘 다 검증, `TemplateVersionsIT`를 (서식 v1·v2 × 렌더러 1·2) 조합으로 — 허용 조합(v1×1, v1×2, v2×2)과 거부 조합(v2×1) |
| Q8 | 추천사유 코드 포함 | 코드·라벨만, `auto` 제외, **룰 순서 그대로**(정렬 0), 화면 기본 선택 0·사전 체크 0(시험) |
| 이월 ① | 503 승인 | **`/api`·`/internal`만**, `/public`은 없는 테넌트와 같은 404 유지(시험) |
| 이월 ② | 감사 행 집계 | 고객 등록 한도 방식, V20 인덱스 계열이 게이트 판정 감사 행위를 덮는지 실측 확인 |
| 이월 ③ | §14 #20 열어 둠 | — |
| 마이그레이션 | 앱 기동 Flyway 제거 | 버전 불일치 기동 실패 시험 |
| Q5 | `/internal` 별도 포트 | 인그레스 미노출 + NetworkPolicy + 포트 — 3중 |
| Q4 | Traefik | 아래 "확인한 사실". 라이선스 MIT를 `deploy/THIRD-PARTY.md`에 |
| 폰트 | 서브셋 허용 | Phase 7 검사를 "바이트 동일" → **패밀리·버전·라이선스 동일**(name 표 대조)로. PDF 폰트 불변. 이득 없으면 그대로 두고 측정값만 |
| Q14 | 맥락 없는 에이전트 재현 **보고서 전 필수** | 사람 재현은 심사를 막지 않는다 — 사용자가 편할 때 PR 코멘트 + README "재현 기록" |
| B1 주입 | 전역 키 읽기 경로 잔존 → 스캔 실패 / 재래핑 감사 생략 → 실패 / 이행 중 이중 읽기 제거 → 미이행 문서 복호화 실패 | 주입 표에 |
| B2 주입 | 백업 롤에 테이블 SELECT → 시험 실패 / 백업 암호화 생략 → 백업 파일 평문 스캔 실패 | 주입 표에 |
| B3 | 설계서 최종판 | §9 키 계층(“테넌트 KEK”가 Phase 8에서야 참이 된 이력), §11 백업, §6.4 렌더러 버전, 롤 표 9종 정본, §14 최종 목록 |
| 보고 | 보고서 끝에 Phase 0~8 전체 숫자 표(CI 출처)·§14 최종 목록 | — |

**확인한 사실 (2026-10-10, 1차 출처)**
- ingress-nginx: 쿠버네티스 SIG Network·보안 대응 위원회가 2025-11에 2026-03 은퇴를 발표했고([Kubernetes 블로그 2025-11-11](https://kubernetes.io/blog/2025/11/11/ingress-nginx-retirement/)), 운영·보안 위원회가 2026-01-29에 다시 확인했다([Kubernetes 블로그 2026-01-29](https://www.kubernetes.io/blog/2026/01/29/ingress-nginx-statement/)). GitHub API로 직접 본 저장소 상태: `kubernetes/ingress-nginx` **archived = true**, 마지막 릴리스 `controller-v1.15.1`(2026-03-19), 마지막 push 2026-03-23.
- 최신 릴리스(GitHub API `releases/latest`, 2026-10-10 조회): Traefik `v3.7.14`(2026-10-06), kind `v0.33.0`(2026-08-26), kubeconform `v0.8.0`(2026-06-04). 구현 때 각 릴리스의 체크섬 파일로 `tools.lock`·이미지 digest를 고정한다.

**단계 순서 변경**: 1단계를 1a(레지스트리·이중 읽기·`KEK_REWRAP` 전역 → 테넌트 — B1 주입 "감사 생략"·"이중 읽기 제거")와 1b(전역 경로 제거·스캔 — B1 주입 "전역 경로 잔존")로 나눈다. `KEK_REWRAP`은 4단계에서 1a로 앞당긴다.

## 0. 먼저 결정이 필요한 것 (요약)

| # | 무엇 | 권장 | 표시 |
|---|---|---|---|
| Q1 | `DatabaseHealthIndicator`의 롤 — 지시문은 "전용 롤 불요(앱 롤)", CLAUDE.md 규칙 5·설계서 §9 표·§12는 "전용 롤" | **전용 롤 `disclosure_health`**(규칙 5가 Phase보다 우선) | **[규칙 충돌]** |
| Q2 | KEK가 지금 **테넌트별이 아니다**(전역 KEK 하나 + AAD에 테넌트) — 지시문은 "KEK(테넌트별)" | 포트를 `currentKekId(TenantId)`로, 비밀 배치를 테넌트별로 | 설계 변경 |
| Q3 | 백업 방식 — 논리 백업(`pg_dump`)은 RLS 때문에 `BYPASSRLS` 롤이 필요하다(지금 모든 롤 NOBYPASSRLS) | **물리 백업**(`pg_basebackup`, `REPLICATION` 전용 롤) — BYPASSRLS 롤을 만들지 않는다 | **[넓힘] 롤** |
| Q9 | 환급금 표 — 원인은 **렌더러 코드**(서식 데이터로는 못 고침) | 서식 스키마 선택 키 `render.columns` + 렌더러 2판 + `renderer_version` 고정 | **[계약 추가]** 둘 |
| Q8 | 룰 어휘 API에 **추천사유 코드**를 넣을지(규칙 7·Phase 7 "추천사유 자동완성·예시 금지") | 넣는다 — 코드·표기만, `auto` 코드 제외, 설명 텍스트는 여전히 설계사 입력 | **[넓힘] 인가 표** 한 행 |

나머지 질문은 ⑧.

---

## ① Phase 7에서 넘어온 것

### Phase 7 보고서 §5 (전체) — 7 수용심사 판정: 전부 수용

| # | 계획·승인 | 구현 | 이유 | Phase 8 |
|---|---|---|---|---|
| ① | 멱등 키 "결정적"(본문에서 유도) | **의도(버튼 한 번)마다 무작위**, 응답을 못 받은 재시도만 같은 키 | 같은 빈 본문 명령(비교 등)의 두 번째 의도가 낡은 응답 재생이 되고, 고객 등록 본문(개인정보)의 해시는 되돌릴 수 있다(6B D-1). G11 "2회 실행 NOOP"은 같은 키 재전송으로 증명 | 그대로. 키 회전 뒤 E2E(G5)도 같은 재전송 검사를 지난다 |
| ② | Q3 "고정 전 초안은 ACTIVE 서식 + `pinned:false`" | `pinned`는 언제나 `true`(계약 `const`) | 이 시스템은 초안 생성 때 서식을 고정한다 | 그대로 |
| ③ | Q3 감사 | 서식 조회는 감사 행 없음 | 승인 "감사는 DISCLOSURE_VIEW에 포함" | 그대로 |
| ④ | 승인 조건 ① pdf.js `isEvalSupported:false` | 옵션을 쓰지 않음 | pdfjs-dist 6.4.299에 그 옵션이 없고 빌드에 `eval`·`new Function` 0 | 운영 정적 서빙(nginx)의 CSP도 `unsafe-eval` 없이 같은 문자열 — 데모 서빙과 **헤더 동일 시험** |
| ⑤ | Q6 로그인 "폼" | 폼을 **새 창**으로 열고 콜백 창이 `postMessage` | PKCE 검증자는 직원 화면 메모리에만 | 7 심사 §2: 운영 문서에 **팝업 허용 전제**, 설계서에 "같은 창 리다이렉트 + `sessionStorage` 검증자는 **채택하지 않음**(토큰 교환 전의 짧은 값이라도 저장소에 두지 않는다)" — 4단계 |
| ⑥ | 설계서 §8 "끝까지 스크롤해야 서명 활성" | 버튼을 막지 않는다 | 판단은 서버(`SCROLL_COMPLETE`) | 그대로 |
| ⑦ | 설계서 §8 추천사유 체크 | **사유 코드 자유 입력** + 설명 | 룰 코드 목록 읽기 경로 없음 | **룰 어휘 API로 닫는다**(③-1, G9) |
| ⑧ | 와이어의 "자동 회색화"·배지·"반려" | 두지 않음 | 화면 판단 | 그대로 |
| ⑨ | select | 작은 enum은 **라디오 묶음** | 키보드 조작이 플랫폼마다 다름 | 어휘 선택 입력도 같은 `Choice` 부품(긴 목록은 ASCII 코드 `chooseCode` select) |
| ⑩ | 날짜 `type=date`·`month` | 텍스트(숫자 자판) | 모바일 날짜 선택기가 타이핑 무시 → 생년월일 빈 값 등록 | 그대로 |
| ⑪ | DOM 스냅샷의 "공개 서명 머리 라벨" | 공개 서명 화면에 라벨 노드 없음 | 라벨은 PDF 본문에만 | 그대로 |
| ⑫ | "회수율"(오기) | "징구율" | 설계서 §6.8 용어 | 그대로 |

### Phase 7 넓힘·계약 추가 표 (전체)

| 항목 | 종류 | 내용 | Phase 8 |
|---|---|---|---|
| Q1 | **[넓힘] 인가 표** | `FLAG_READ` AGENT OWN — `visible_to_agent` 행만(저장소 조건) | 변화 없음 |
| Q3 | **[넓힘] 계약·경로** | `GET /api/v1/disclosures/{id}/template`, `DISCLOSURE_READ`, `additionalProperties:false`, `ETag` | 환급금 표의 `render.columns`가 서식 응답에 **나가지 않는다**(라벨 집합만 — 렌더 속성은 화면 몫이 아니다) |
| 미리보기 | 계약 추가 | `GET /api/v1/disclosures/{id}/preview.pdf` | 렌더러 2판 문서도 같은 경로 |
| Q5 | **[계약 추가]** 룰 스키마 | GLOBAL 키 `preview.watermark` — 번들 4개 재해시 | 변화 없음 |
| Q6 | **[계약 추가]** 데모 전용 | `demo-oidc.openapi.yaml` 1.0.0 | kind 데모 오버레이만. 운영 오버레이에는 경로 자체가 없다(기존 `WebNotServedOutsideDemoIT`·`DemoKeysGuard`) |
| 불변식 | 변화 없음 | 넓힘은 Q1·Q3뿐 | — |

## ② 6B 이월분 목록 (지시문 요구 — Phase 7 보고서 §8 Q6이 적은 셋 + 그대로 둔 것)

Phase 7 보고서 §8 Q6(`phase-07-보고서.md:223`)은 셋을 적었다. 지시문의 "항목이 적혀 있지 않았다"는 그 줄을 놓친 것이다. 이 계획은 그 셋에 Phase 7이 "그대로"로 둔 6B 질문과 D 표의 남은 공백까지 모두 적는다.

| # | 출처 | 내용 | Phase 8 처리(권장) |
|---|---|---|---|
| 6B-a | 6B §8 Q5 | **룰 없는 테넌트의 쓰기 POST가 500** — 멱등 청구가 룰을 읽다 실패 | ① 명시 응답 **503 `TENANT_RULES_NOT_ACTIVE`**(유스케이스 전에 판정, 감사 없음, 멱등 키 묶지 않음)으로 **[계약 추가]** ② 온보딩 런북 순서(룰 배포·승인·활성화 → IdP에 그 테넌트 주체 등록) ③ IT: 룰 없는 테넌트의 쓰기 POST 전수(계약의 쓰기 연산 목록에서) → 503, 500 0 |
| 6B-b | 6B §8 Q7 | **게이트 분당 한도가 인스턴스 메모리**(`RateWindow`) — 복제본 N개면 한도 N배 | 고객 등록과 같은 **DB 집계**: 게이트는 판정마다 감사하므로 그 주체의 최근 1분 게이트 감사 행 수로 판정(새 표 없음), 주체별 `pg_advisory_xact_lock`으로 경합 직렬화, 인덱스는 ③-3 실측으로. 인그레스에도 클라이언트 인증서 주체별 한도(벨트) |
| 6B-c | 6B §8 Q3, §14 #20 | **정정의 행위자·재배정** | **결정하지 않는다** — 새 업무 기능 금지(지시문). 운영자 CLI만 유지, 최종 §14 목록에 "남음"으로 |
| (같은 부류) | 6A §8 Q6 | 공개 서명 테넌트 분당 한도도 인스턴스 메모리 | 인증 전 경로라 DB 집계는 DB 부하 증폭 — **메모리 유지 + 인그레스 IP 한도**, 운영 문서에 "실효 한도 = 룰 값 × 복제본 수" |
| 6B Q2 | §14 #22 | 중복 가명 병합 | 남음(새 업무 기능) |
| 6B Q6 | — | 스칼라 숫자 → 문자열 강제 변환 | 그대로(생성 클라이언트는 문자열로만 보낸다) |
| 6B Q8 | — | 계약 피드 주체의 `JOB_READ` | 그대로(인가 넓힘 — 요구 없음) |
| 6B Q9 | §14 #17 | 징구율 정의 | 남음 — README 첫 화면의 알려진 한계 |
| 6B Q10 | §14 #21 | 산식 불변 재계산(`computation_seq`) | 남음 |
| 6B D-2 | — | 간헐 실패 원인 미증명(연결 재사용 가설) | Phase 7까지 재발 0. kind·복구 증명에서 재발하면 원인 증명, 아니면 "미증명·재발 0"으로 닫음 |
| 6B D-6 잔여 | P7-3 | Playwright `error-context.md`의 입력값 | ③-5 스냅샷 마스킹 |

## ③ 배포 형태

**권장: Kubernetes 매니페스트 + Kustomize, 로컬은 kind** (지시문 권장 그대로).

```
deploy/
├── images/            app.Dockerfile · web.Dockerfile · web/nginx.conf(헤더) — 베이스 digest 고정
├── base/              네임스페이스·SA·Deployment(app, web)·Service·NetworkPolicy·PDB
│                      Job(db-migrate)·CronJob × JobKind 전수·ConfigMap(비밀 아님)
├── components/
│   ├── ingress/       인그레스 컨트롤러 설정(공개 진입점 2 + 클러스터 내부 진입점 1)
│   └── secrets-eso/   ExternalSecret·SecretStore 템플릿(렌더·스키마 린트만, kind에 적용하지 않음)
├── overlays/
│   ├── prod/          prod 프로파일, 실 IdP issuer·TSA URL 자리(값은 비밀 저장소), 복제본 2
│   └── kind-demo/     demo 프로파일·TSA 스텁·데모 OIDC, 복제본 2(인스턴스 메모리 한도 관찰용), PostgreSQL·SeaweedFS StatefulSet
├── scripts/           kind-up · secrets-local(로컬 키 생성 → kind Secret) · deploy-smoke · backup · restore · rotate-*
└── tools.lock         kind·kubeconform 등 버전 + SHA-256 (Gradle이 받아 build/tools에 — 설치 스크립트·brew 없음)
```

- **이미지 2개 권장(지시문은 3개 — Q6)**: ① `app` — 부트 jar 계층 분해, 비루트(UID 10001), 읽기 전용 루트 FS(`/tmp`만 emptyDir), 같은 이미지를 **마이그레이션 Job·CronJob(CLI)**이 다른 인자로 쓴다 ② `web` — 정적 파일(`disclosure-web` 산출물) + 비루트 nginx, 헤더(CSP·`no-referrer`·nosniff·프레임 금지·HTML `no-store`·해시 자산 `immutable`)는 데모 서빙(`DemoWebConfiguration`)과 **같은 문자열**(시험이 대조). 마이그레이션 전용 이미지를 따로 두지 않는 이유: 앱이 기동 때 대조하는 마이그레이션 목록과 Job이 적용하는 목록이 같은 jar에서 나와야 한다(따로 빌드하면 그 동일성이 시험 대상이 된다).
- 베이스 이미지: JRE 25(Temurin 계열)·비루트 nginx — 구현 시점 최신 안정판을 확인해 **멀티아키 인덱스 digest**로 고정(로컬 arm64 = CI amd64). `postgres:18.6`은 지금 태그뿐 → digest 고정하고 기존 SeaweedFS 동일성 시험(`imageDigestIsPinnedTheSameEverywhere`)을 PostgreSQL까지(compose·하네스·E2E·kind 매니페스트).
- **이미지 레이어 스캔**(G3): `docker save` → 각 레이어 tar의 파일 전수 — 이름(`*.key`·`*.p12`·`*.pem` 개인키·KEK JSON 형식)·내용(PEM `PRIVATE KEY` 머리, 로컬 생성 키 바이트, 개인정보 센티널). Gradle `imageScan`, CI 잡.
- **마이그레이션 = 선행 Job**: 앱의 Flyway 자동 실행을 끈다(`spring.flyway.enabled=false` 전 프로파일). Job은 `app` 이미지의 CLI `db migrate`(마이그레이터 자격, 그 Job에만 마운트). 앱은 기동 때 **스키마 버전 가드** — jar의 최고 `V*`와 `flyway_schema_history`의 최고 성공 버전이 다르면 기동 실패(메시지: 두 버전 번호만). 그 조회는 헬스 롤 연결(Q1)로 — 앱 롤은 이력 표 권한이 없다. 기존 IT·E2E·데모 스크립트는 하네스·`db migrate`로 먼저 적용하게 바꾼다(앱이 마이그레이션하던 경로 전수 제거 — 남아 있으면 가드가 잡는다).
- **DB 롤 매니페스트**: `init-roles.sql`은 롤 **7개**다 — 지시문의 "5종 + 정의자"에 `disclosure_operator`(운영자 CLI `--tenants all`)가 빠져 있다. 매니페스트는 7개 + Q1 `disclosure_health` + Q3 `disclosure_backup` = 9개. 비밀번호는 비밀 저장소에서, `init-roles.sql`은 비밀번호를 인자로 받는 형태(지금의 로컬 기본 비밀번호는 compose·하네스 전용으로 남기고 운영 오버레이에서 쓰면 기동 실패).
- **스케줄 = CronJob**, 모두 `timeZone: Asia/Seoul`·`concurrencyPolicy: Forbid`·`startingDeadlineSeconds`·`backoffLimit`·`activeDeadlineSeconds`. 각 Job은 `app` 이미지의 CLI가 테넌트마다 기존 `JobRunner`(6A advisory lock 그대로)를 부른다 — 새 CLI 명령 `jobs run <KIND> --tenants all [--param k=v]`(배관이지 업무 기능 아님, 기존 핸들러 맵 재사용). **2중 방어**: Forbid는 같은 CronJob의 겹침만 막는다(수동 `create job --from` 겹침, 복제된 CronJob, 다른 클러스터는 못 막는다) → 앱의 advisory lock이 테넌트·작업 종류 단위로 막는다. 운영 문서에 그 표.
  - 매니페스트 = `JobKind` 전수 1:1(+ 신설 `KEK_REWRAP`) — 주기 없는 종류는 `suspend: true` CronJob(런북의 `kubectl create job --from=cronjob/…` 원형). 린트 시험이 `JobKind` ↔ 매니페스트 양방향.

  | 종류 | 주기(KST, 권장) | 비고 |
  |---|---|---|
  | `ANCHOR` | 매일 00:20 | 플랫폼 CLI `anchor run --tenants all`(테넌트별 아님 — 6A Q7) |
  | `VERIFY_TENANT` | 매일 02:00 | §10 "체인 검증 일 1회" |
  | `EXPIRE` | 15분마다 | |
  | `NOTIFY` | 1분마다 | 통지 사업자 미연동 — kind는 콘솔 통지 |
  | `FLAG_SLA_SWEEP` | 15분마다 | 6B "스케줄은 Phase 8" |
  | `IDEMPOTENCY_PURGE` | 매시 10분 | |
  | `RECONCILE`(산출물 대사·잠금 재적용) | 매일 03:00 | |
  | `DESTROY` | 매일 04:00 | |
  | `ABANDON_DRAFTS` | 매일 05:00 | 룰 `draft.abandonAfterDays` null이면 NOOP |
  | `CONTRACT_LINK_UNMATCHED_PURGE` | 매일 05:30 | |
  | `COLLECTION_RATE_SNAPSHOT` | 매월 1일 06:00 | 전월 |
  | `DESTROY_DRY_RUN`·`RETENTION_RECOMPUTE`·`CONTRACT_LINK_IMPORT`·`KEK_REWRAP` | 정지(`suspend`) | 수동·피드 계기 |

- **인그레스**: 진입점 셋.
  - 공개 진입점 A — 호스트 `staff.<도메인>`: `/`(web)·`/api`(app). 사람 역할 OIDC(운영은 사내 IdP — 실 연동 없음, issuer·JWKS 설정만).
  - 공개 진입점 B — **별도 호스트** `sign.<도메인>`: `/s`·`/assets`(web)·`/public`(app)만. IP 단위 분당 한도, 요청 크기 한도(앱의 4 MiB보다 작게 — 서명 이미지 실측으로), TLS만(평문 리다이렉트 없이 거부).
  - 클러스터 내부 진입점 C — `ClusterIP`만, `/internal`만, **mTLS 종단**(클라이언트 인증서 검증 → 주체를 `ga.api.client-cert.subject-header` 헤더로, 들어온 같은 이름 헤더는 지움). 외부 진입점 A·B는 그 헤더를 **언제나 지운다**.
  - **app의 `/internal`을 별도 포트로(권장, Q5)**: 지금은 같은 포트에서 경로로만 갈린다 — 인그레스 경로 설정 하나가 틀리면 열린다. Tomcat 커넥터 2개(8080 = `/api`·`/public`, 8081 = `/internal`)와 "들어온 포트 ≠ 경로 접두면 없는 경로와 같은 404" 필터. NetworkPolicy: 8081은 진입점 C 파드와 같은 네임스페이스의 CronJob 파드만, 8080은 진입점 A·B 파드만, 관리 포트(헬스·메트릭)는 kubelet·모니터링 네임스페이스만.
  - **컨트롤러(Q4)**: 권장 **Traefik**(IngressRoute + 미들웨어 — IP 한도 `RateLimit`(sourceCriterion IP)·크기 `Buffering.maxRequestBodyBytes`·mTLS `TLSOption.clientAuth`·인증서 주체 전달 `PassTLSClientCert`가 한 제품의 문서화된 기능). ingress-nginx는 쿠버네티스 쪽이 지원 종료를 발표했다(구현 시 현재 상태 재확인). 대안 Envoy Gateway(Gateway API 표준 — 한도·mTLS 기능은 같은데 kind 구성 단계가 더 많다). 버전은 구현 시 최신 안정판·digest 고정.
  - 시험(G4): 매니페스트 린트(kubeconform — 스키마, + 우리 규칙: `/internal` 경로가 공개 진입점에 0, 헤더 지움 미들웨어가 A·B에 있음) + kind: 밖에서 `/internal/**` → 거부(인그레스 404, 노드 포트·포트포워드 없이), 클러스터 안에서 인증서 없이 C → TLS 실패, 인증서 있음 → 통과, `/public` N+1회째 429·크기 초과 413·평문 HTTP 거부, A 호스트로 `/public` → 404.
  - **kind의 NetworkPolicy 집행**: kindnet의 NetworkPolicy 지원(kind 0.24+)을 구현 시 확인, 집행이 안 되면 Calico로 — 정책이 실제로 막는지를 시험이 먼저 단언(공회전 방지, 6B D-5 교훈).
- **운영 필수 키 가드**(G3): `prod` 프로파일 기동 전에(`EnvironmentPostProcessor`) 닫힌 목록을 검사 — `ga.public-sign.min-response-millis`, `ga.sign.link-base-url`(지금 기본값 `https://sign.example.invalid/s#` → prod에서 기본값 금지), `ga.tsa.url`(지금 비어도 기동 → prod 금지), `ga.tsa.trust-pem`(지금 `build/demo/…` 기본값 → prod 금지), `ga.api.jwt.issuer`·`audience`·`jwk-set-uri`, 키 출처(③-2의 `SecretSource` 항목들), `ga.api.client-cert.subject-header`(기존 `ClientCertHeaderGuard`를 이 목록으로 흡수), DB·S3 접속. 실패 메시지는 **키 이름만**(값·기본값·경로 0). 시험: 키를 하나씩 빼고 기동 → 실패 + 메시지 = 그 키 이름, 메시지·로그에 다른 키의 값 0. `application.yaml`의 운영 해당 키에 기본값 0(YAML 스캔 시험 — 주입 "필수 키 기본값 부여").
- **헬스**(G7): `DatabaseHealthIndicator`(Q1) — `disclosure_health` 롤의 풀 없는 단일 연결(잠금 게이트웨이와 같은 방식)로 `SELECT 1`과 `flyway_schema_history`의 최고 성공 버전만. 테넌트 표 권한 0을 V21이 단언. 준비성 = DB·저장소(버킷 Object Lock 확인 — 지금 첫 사용 때 하던 것을 준비성으로 앞당김)·IdP JWKS 도달, 활성 = 프로세스(`livenessState`)만. 관리 포트 분리(인그레스 미노출). 기존 `management.health.db.enabled:false`는 유지(Spring 기본 DB 지표는 앱 풀로 테넌트 바인딩 없이 쿼리 — 허용 목록 밖).
- **관측**(G8): Micrometer 미터(Boot BOM — Prometheus 레지스트리, 관리 포트만): `ga.jobs.runs`{kind, outcome, tenant}·`ga.jobs.duration`{kind, tenant}·`ga.public.rejections`{reason}(공개 거부는 테넌트 미상이 있으므로 **reason만**, reason은 거부 코드 닫힌 enum)·`ga.anchor.unanchored.days`{}(플랫폼)·`ga.flags.sla.breached`{tenant}. 라벨 키 닫힌 목록 {kind, outcome, reason, tenant} — 미터 레지스트리 필터가 목록 밖 라벨이면 **등록 거부**(예외) + 시험. 로그: Boot 내장 구조화 로그(JSON, 추가 의존성 0) — 기존 `ga.access` 접근 로그 포함. **배포 전 검사**: `deploy-smoke` 끝에 모든 파드·Job 로그 전체를 모아 센티널·키 바이트 스캔(0이어야 통과) — CI는 kind 잡에서.

## ④ 비밀 주입

**권장: 외부 비밀 저장소 → External Secrets Operator(ESO) → 쿠버네티스 Secret → 파일 마운트**, 앱은 `SecretSource` 포트로 읽는다. 실 벤더 연동은 하지 않는다(지시문) — `components/secrets-eso/`는 `SecretStore`·`ExternalSecret` 템플릿과 문서, 렌더·스키마 린트까지. 로컬 kind는 `secrets-local` 스크립트가 키를 생성해(소유자 전용 파일, 저장소·이미지 밖) `kubectl create secret`으로 넣는다. CSI 대신 ESO를 권하는 이유: 앱이 이미 "소유자 전용 파일"을 읽는 규약(`OwnerOnlyKeyFile`·`LocalFileKeyProvider`)이라 Secret 볼륨 마운트로 바뀌는 것이 없고, CSI 드라이버는 벤더별 프로바이더 설치가 kind에서 재현되지 않는다.

- **`SecretSource` 포트**(`platform-core`, Spring 무의존): `bytes(SecretRef)`·`version(SecretRef)` — 어댑터 셋: `FileSecretSource`(마운트 디렉터리, 권한 검사 — 쿠버네티스 Secret 볼륨은 `defaultMode: 0400` + `fsGroup`), `EnvSecretSource`(지시문 요구 — **prod 프로파일에서는 키 재료에 쓰면 기동 실패**: 환경변수는 `/proc/*/environ`·`kubectl describe`·크래시 덤프로 새기 쉽다. 개발 편의만), `ExternalSecretSource`(인터페이스 + 문서, 구현 없음 — 실 연동 금지).
- 대상 전수(지금 → 바뀌는 것):

  | 비밀 | 지금 | Phase 8 |
  |---|---|---|
  | KEK | 전역 하나(`ga.crypto.local-kek-file`, `{"current", "keys"}`) | **테넌트별(Q2)**: `kek/<TENANT>` = `{"current", "keys"}`, 포트 `currentKekId(TenantId)`. 기존 데이터는 지금 KEK ID 그대로 풀리고(키 ID로 찾음), 새 감싸기만 테넌트 KEK |
  | 커서 키 | `ga.api.cursor-key-file` | `SecretSource` |
  | 요청 해시 키 | `ga.api.request-hash-key-file` | 〃 |
  | 영수증 키 | `ga.api.receipt-key-file` | 〃 |
  | 데모 OIDC 키 | `ga.demo.oidc-key-file`(없으면 생성) | 〃(kind-demo만) — 운영 오버레이에 0 |
  | TSA 신뢰 앵커 | `ga.tsa.trust-pem` | 〃(비밀은 아니지만 바꿔치기가 곧 위조 — 같은 경로로) |
  | 엔진 서비스 토큰 | `ga.engine.credentials.<TENANT>`(환경) | `SecretSource`(Q12 — 설계서 §9의 "Phase 2 볼트(DB)" 대신) |
  | DB 롤 비밀번호·S3 자격 | `application.yaml` 기본값 | Secret → 환경(이건 Spring 데이터소스가 요구) — **prod는 기본값이면 기동 실패** |

- 지금 `OwnerOnlyKeyFile.loadOrCreate`는 **파일이 없으면 만든다** — 운영에서 키 파일 경로 오타 = 새 키로 조용히 기동(커서·영수증이 바뀐다). prod는 `load`만(없으면 기동 실패), 생성은 `secrets-local`·개발만.
- 스캔(G3): 저장소(추적 파일 전수 — PEM 개인키 머리·로컬 키 형식·센티널), 이미지(③ 레이어 스캔), 로그(③ 배포 전 검사).

### 키 회전 런북 (G5 — 각 1회 실제 실행, kind-demo에서)

| 키 | 영향 | 절차 | 검증 |
|---|---|---|---|
| 커서 키 | 진행 중 페이지 커서 무효(400 `INVALID_CURSOR` — 처음부터 다시) | 새 키 → Secret 갱신 → 롤링 재기동 | 옛 커서 → 400, 새 목록 조회 정상 |
| 요청 해시 키 | 진행 중(24시간 안) 멱등 청구의 같은 키 재전송이 재생 대신 422 `IDEMPOTENCY_KEY_REUSED` | 〃(멱등 기록 만료를 기다리면 영향 0 — 런북에 대기 선택지) | 회전 전 키로 보낸 요청 재전송 → 422, 새 키 재전송 → 재생 |
| 영수증 키 | 멱등 만료 뒤 같은 등록 키 NOOP의 `receiptId`가 첫 응답과 달라진다 | 〃 | 회전 전후 같은 등록 키 → 가명 같음, 영수증 다름(설계서 §9 배포 노트) |
| 데모 OIDC 키 | 발급된 토큰(1시간) 무효 → 다시 로그인 | 〃 + 공개키 재게시 | 옛 토큰 401, 새 로그인 정상 |
| KEK(테넌트 하나) | 새 감싸기는 새 KEK. 옛 KEK는 **재래핑이 끝날 때까지 지우면 안 된다** | 새 KEK를 `keys`에 추가·`current` 교체 → 재기동 → `KEK_REWRAP` 작업 → `verify tenant` → 옛 KEK 보존기간 뒤 제거(별도 단계) | 재래핑 전후 `verify tenant` MATCH, 옛 KEK ID로 감싼 행 0(`document_key`·`customer_data_key`·`async_job.report_key_wrapped`) |
| (선택) TSA 신뢰 앵커 | 스텁 CA 교체 — 옛 토큰 검증에는 옛 앵커가 남아야 한다(앵커 집합) | 집합에 추가 → 새 앵커 토큰 | 옛·새 토큰 모두 검증 |

- **`KEK_REWRAP` 작업 신설** — 새 `JobKind`(V21 `ck_job_kind`). 대상: `document_key.wrapped_dek`(파기된 행 제외 — 파기 묘비를 되살리지 않는다), `customer_data_key`(ACTIVE·RETIRED, DESTROYED 제외), `async_job.report_key_wrapped`. 행마다 unwrap(옛 KEK) → wrap(테넌트 현재 KEK) → `kek_id` 갱신, 평문 DEK는 메모리에서 지움. **불변 트리거**: `document_key`의 감싼 바이트·KEK ID는 지금 갱신이 막혀 있을 수 있다(봉인 산출물 키) — 트리거가 허용하는 좁은 갱신(같은 DEK의 재래핑 — 다른 컬럼 불변, `kek_id`가 바뀌는 경우만)을 V21에서 **새 함수 + 정의자 롤 경로**로 열지, 트리거 분기를 둘지는 1단계에서 트리거를 읽고 정한다(**[넓힘] 불변식 후보** — 보고). 지시문 주의 그대로: 감사(행마다가 아니라 작업당 1행 + 대상 수·KEK ID 쌍), **dry-run 기본**(세기만), 연장 전용 아님(보존기한을 건드리지 않는다), 멱등(이미 현재 KEK인 행은 건너뜀 — 2회 실행 = 0건), 테넌트 advisory lock. IT: 2회 실행 NOOP, 중간 실패 뒤 재실행, 파기 행 불변, 다른 테넌트 행 0, 재래핑 뒤 산출물 열람 바이트 동일.

## ⑤ 백업·복구와 복구 증명

**PostgreSQL — 물리 백업 권장(Q3)**: `pg_basebackup`(tar, WAL 포함) + WAL 아카이빙(시점 복구는 문서). 논리 백업(`pg_dump`)은 RLS가 걸린 표를 읽으려면 `BYPASSRLS`가 필요하다 — 지금 모든 롤이 NOBYPASSRLS이고(V9·V12·V14가 롤마다 단언), 표는 FORCE RLS라 소유자인 마이그레이터도 정책을 따른다(V2). 물리 백업 롤 `disclosure_backup`은 `REPLICATION LOGIN`, 테이블 권한 0 — 데이터 파일 전체를 받으므로 사실상 전권이라는 점은 같다(**[넓힘] 롤** — 백업 Job에만 자격을 마운트, 앱·CLI 파드에는 없음). 복구에 롤·비밀번호 해시가 같이 오므로 복구 환경의 비밀 저장소가 같은 비밀번호를 가져야 한다(런북).

**저장소 — 객체 버전 전수 복제**: 백업 버킷(Object Lock + 버저닝)에 키마다 **모든 버전**을 순서대로 복사하면서 보존 모드·기한(COMPLIANCE)·법적 보류를 같이 건다(CLI `backup objects export|import` — S3 어댑터 재사용, `ArtifactStoreContract`의 능력 검사 그대로). 복구 버킷의 버전 ID는 새로 생긴다 — DB가 버전 ID를 저장하지 않는다는 것을 확인했다(마이그레이션 전수에 `version_id` 0), 그래서 키 + 평문·암호문 해시 대조로 충분하다. 산출물은 이미 문서 DEK로 암호화된 바이트다.

**백업 암호화**: DB 백업 tar를 백업 키(비밀 저장소, 앱 키와 별개)로 AES-256-GCM 봉투 암호화 — 같은 `app` 이미지의 CLI `backup seal|open`(새 도구 의존 0). 보존기간 = 룰 보존기간 최대값 이상(런북이 `rules` 조회로 계산), 백업 버킷 기본 보존 규칙은 두지 않고 객체마다 원본의 기한을 그대로.

**복구 증명(G2) — 자동화 시험** `RestoreProofIT`(disclosure-app 통합시험, Testcontainers — CI `build` 잡에서도 돈다; kind 판은 `deploy/scripts/restore-proof`로 로컬 재현):
1. 환경 A(PostgreSQL·SeaweedFS·앱 컨텍스트): 데모 시드(테넌트 둘) → 확인서 봉인·서명·완료 → `anchor run`(TSA 스텁) → 영수증 내보내기 → 파기 1건(묘비 포함 상태를 복구가 보존하는지).
2. 백업: `pg_basebackup` → `backup seal` → 객체 버전 전수 export.
3. 환경 B(새 컨테이너, 빈 볼륨): 복호화 → 데이터 디렉터리 복원 → 기동 → 객체 import.
4. 단언: 모든 테넌트 `verify tenant` MATCH, 영수증 `verify package` 실패 0, 앵커 TSA 토큰 검증(신뢰 앵커 대조) 통과, `artifacts reconcile` 0건, 표 전수 행 수 A = B, 산출물 열람 바이트 = A, 법적 보류·보존 기한 = A. 그리고 **음성 대조**: B에서 객체 하나를 빼고 복구하면 `artifacts reconcile`이 정확히 그 키를 잡는다(공회전 방지).

## ⑥ 환급금 표 수정 방법 (지시문 §3 제약 안에서)

**원인 = 렌더러 코드**(조사 결과, 산출물 `scratchpad`): 서식 데이터는 그 칸에 라벨·바인딩(`CATALOG_DEFAULT`)만 준다. 값은 카탈로그의 `[{year, refundWon}, …]`이고, `HtmlComposer.value()`(`disclosure-seal/…/HtmlComposer.java:120-144`)가 **배열은 줄마다, 객체는 키·값 표**로 그린다(3B 승인 Q10의 범용 규칙) — 그래서 행마다 `refundWon | 0 / year | 10` 작은 표가 쌓이고 JCS 정렬 때문에 `refundWon`이 먼저 나온다. CSS도 자바 문자열이고, 서식 스키마는 모든 층이 `additionalProperties:false`라 **새 서식 버전만으로는 고칠 수 없다**. (골든 case-01을 JDK 25로 다시 그려 저장된 `pdfSha256`과 같은 바이트임을 확인한 뒤 본 HTML이다.)

방법 — 지시문의 "렌더러 코드면" 경로 그대로:
1. **[계약 추가] 서식 스키마** `contracts/rules/v1/form-template.schema.json`: `field.render.columns`(선택) = `[{key, label}]` — 객체 배열 값을 **머리행 + 서식이 정한 열 순서의 표 하나**로. 라벨은 서식 데이터(규칙 4), 렌더러에 항목 코드·열 키 리터럴 0(`NoFieldCodeLiteralsTest` 그대로). 열 키가 값에 없으면 빈 칸이 아니라 **산출불가 표기**(서식의 `unavailableText`) — 지어내지 않는다.
2. **새 서식 버전** STANDARD v2(`supersedes` v1, 새 `applyFrom`) — 환급금 칸에 `columns`(경과년수·해약환급금(원)) 추가, 그 밖 동일. 배포는 `rules distribute`(0단계 경로).
3. **렌더러 2판**: 렌더러 버전을 enum으로(`RendererVersion {V1, V2}`), V1 경로는 동결 — V2는 V1 + `columns` 분기 + 그 CSS, PDF `/Producer`·XMP `pdf:Producer`가 `ga-disclosure-renderer/2`. V1이 `columns` 있는 서식을 받으면 거부(조용히 무시하지 않는다).
4. **고정**: V21 `document_artifact.renderer_version SMALLINT NOT NULL DEFAULT 1`(기존 행은 `ADD COLUMN … DEFAULT`로 UPDATE 없이 1 — 불변 트리거가 `to_jsonb` 비교라 새 컬럼도 자동으로 불변), 증거 매니페스트 `manifestVersion` 2 + `rendererVersion`(**[계약 추가]** `contracts/seal/v1/evidence-manifest.schema.json` — 1판 ZIP은 그대로 유효, "없음 = 1"). canonical에는 넣지 않는다(봉인 본문·해시가 바뀐다). 새 봉인은 현재 렌더러(V2), 재렌더·서명본 덧붙임·미리보기 워터마크·`RerenderMain`은 **저장된 버전**으로.
5. **증명(G10)**: 기존 골든 4건 바이트 불변(파일 변경 0), 새 골든 `case-04`(v2 서식, 환급금 두 행)만 추가, `TemplateVersionsIT` 확장 — v1 문서 재렌더 = 저장 바이트(렌더러 V1로), v2 문서 재렌더 = 저장 바이트(V2로), **주입 "렌더러 버전 미고정"**(재렌더가 언제나 현재 렌더러) → v1 문서의 재렌더 바이트가 `/Producer` 차이로 달라져 실패. veraPDF(`pdfa-verify` 잡)에 case-04 추가.
6. 대안(권하지 않음): 카탈로그 값을 미리 포맷한 문자열로 — 코드 0이지만 서식 버전 경로가 아니고(G10 경로 아님) 구조화 값을 잃는다.

## ③-x 정비 (지시문 §3)

1. **룰 어휘 API** `GET /api/v1/rules/vocabulary` — **[계약 추가]** `disclosure-api` 2.9.0, 행위 `RULE_VOCABULARY_READ`(설계사·관리자·준법 모두 TENANT — **[넓힘] 인가 표 한 행**, Q8), 감사 없음(룰 데이터 읽기, 개인정보 0), `ETag` = (GLOBAL·TENANT 룰 버전 ID, 오늘 KST)의 해시. 응답: 오늘(KST) ACTIVE 룰에서 닫힌 목록만 — `voidReasons`·`supersedeReasons`·`legalHoldReasons`·`legalHoldReleaseReasons`·`draft.abandonReasons`·플래그 유형과 유형별 `resolutionCodes`·추천사유 `reasonCodes`(**`auto` 코드 제외**)·채널·역할, 각 `{code, label}`만. `requiresText`·한도·임계치·SLA·가시성·보존 값 0(계약 `additionalProperties:false` + 응답 스키마에 수 타입 필드 0을 시험). 화면: 자유 입력 → 선택(`Choice`/`chooseCode`), 서버 거부는 그대로(화면이 목록 밖 값을 막지 않는다 — 막을 값을 만들 수 없을 뿐). E2E: 선택 → 성공, 계약 밖 직접 요청 → 기존 422 그대로. 주입 "어휘 API가 임계치 반환" → 계약 검증 실패.
2. **성능 인덱스**: 생성 스크립트(허구 데이터, 시드 고정 — `SeededCases`)로 테넌트 하나에 감사 로그 10만 행·확인서 10만·플래그 수만 → 화면·API의 실제 질의(감사 조회, 확인서 목록 필터·커서, 플래그 큐 필터, 6B-b 게이트 집계) `EXPLAIN (ANALYZE, FORMAT JSON)` 실측 → 순차 스캔·정렬이 지배하는 것만 V21 인덱스 → 플래너 사용 시험(`V20AuditIndexIT` 방식). 보고서에 질의마다 전후 실측표. 감사 행은 체인을 지키는 실제 경로로 넣는다(빠른 직접 INSERT는 체인 트리거가 거부).
3. **폰트 상한**: 측정(지금 화면 = PDF와 같은 NanumGothic 2개, 4,128,612바이트). **화면 폰트만** 서브셋 후보 — 한글 완성형 11,172자 + ASCII + 문장부호(고객 성명이 임의 한글이라 KS X 1001 2,350자로는 부족). 도구는 이미 의존 중인 PDFBox FontBox의 TTF 서브셋터(결정론, 새 의존성 0). 서브셋 효과가 작으면(측정 뒤 10% 미만 감소면) 하지 않고 지금 바이트 그대로 상한만. **[규칙 변경]** Phase 7 산출물 검사 "화면 폰트 바이트 = 렌더러 폰트"는 서브셋을 하면 성립하지 않는다 → "화면 폰트 = 렌더러 폰트의 결정론 서브셋(같은 원본 해시·같은 글리프 윤곽, 빌드가 다시 만들어 바이트 대조)"으로 바꾼다(서브셋을 할 때만). 상한 = 측정값 + 여유 5%, `scan-dist.mjs`가 초과 시 실패. PDF 폰트·골든 불변.
4. **E2E 실패 스냅샷 마스킹**: Playwright 고정 장치 정리 단계에서 실패한 테스트의 페이지 입력 칸 값을 지운 뒤 산출물 생성, + `run.mjs`가 실행 뒤 `test-results/` 전체를 센티널로 스캔(있으면 실패). 증명: 입력 직후 일부러 실패하는 시험을 주입 → `error-context.md`에 센티널 0 + 스캔 통과, 마스킹을 끄는 주입 → 스캔 실패. CI 아티팩트 제외는 유지.

## ⑦ 포트폴리오 문서 목차

**`README.md`** (첫 화면 = 스크롤 없이)
1. 한 문단 요약 — 무엇(대형 GA의 비교설명 확인서 워크플로)·왜(규제 배경 2줄 + 출처 링크 — **공식 문서만**, 구현 때 원문 확인 후. 설계서의 기사 링크 하나는 출처로 쓰지 않는다)
2. **알려진 한계·미결정**(첫 화면): 협회 표준서식 미확보(§14 #2), 징구율 규제 정의 없음(#17), 실 TSA·통지 사업자·IdP·비밀 저장소 미연동, 패딩 상수의 규모 상한(추정 — 측정 근거 있는 것만 수치로), 학습·포트폴리오 목적(면책 — "법령 요건 충족" 단언 0)
3. 숫자(전부 CI run 링크): 테스트 수(모듈별 합)·주입 수·E2E 수
4. 아키텍처 그림 1장(모듈·경계·외부 의존 — SVG, 텍스트 원본 동반)
5. 15분 둘러보기: kind 배포 → 데모 → 화면(역할별 로그인) → `verify tenant`
6. 색인: 설계서·Phase 지시문·계획·승인·보고서·수용심사, ARCHITECTURE·EVIDENCE·DECISIONS, 운영 런북

**`docs/ARCHITECTURE.md`**: 대전제(설계서 §0)·CLAUDE.md 절대 규칙 9개 → 강제 지점 표(규칙 | 코드 위치 | 시험·ArchUnit·트리거·빌드 검사 | 주입 기록 Phase) — "대전제 7개"(지시문)는 설계서 §0 기준으로 세고 CLAUDE.md 9개와의 대응을 같이 적는다. 모듈 의존 방향, 직접 DB 접근 허용 목록(전수 — §9 표에 빠진 플랫폼 기반 3종 `TenantJdbcGateway`·`TenantSessionBinder`·`PlatformJdbcAutoConfiguration`도 적는다), 경로 접두 셋과 인그레스.

**`docs/EVIDENCE.md`**: 데모 확인서 하나의 수명주기 — 초안 → 스냅샷 → 봉인(JCS·canonical 해시·PDF 해시·렌더러 버전) → 서명(서명-해시 귀속) → 체인(seq·prev) → 앵커(머클 루트·TSA 토큰) → 영수증 → 파기 묘비 → `verify tenant`·`verify package` → 복구 뒤 같은 결과. 모든 해시는 실제 데모 산출물의 앞 12자리(kind 실행에서).

**`docs/DECISIONS.md`**: 시간순(Phase 0 → 8). 각 항목 = 결정 · 출처(계획승인·수용심사 줄) · 되돌렸거나 틀렸던 것(예: 6A 키 없는 요청 해시 → 6B HMAC, 원 URI 채널 판정, 4단계 심사 전 병합 절차 위반, 지시문 오기 — 6B "§14 #10", 이번 지시문의 "5종 롤"·"앱 롤 헬스", 심사 누락 — 6A가 놓친 둘) · 남은 영향.

**`docs/operations/`**: 배포·롤 매니페스트·스케줄 표·인그레스·키 회전 런북·백업·복구·온보딩(6B-a)·팝업 허용 전제(7 심사 §2)·한도(인스턴스 메모리 한도의 실효값).

스크린샷: Phase 7 `e2eDemo` 산출물(허구 데이터)에서, 다시 찍을 때는 센티널 스캔 통과본만. 형용사 금지 목록(강력한·완벽한·안전한 등)을 문서 린트로.

## 단계 — 단계마다 시험·주입·설계서 같은 커밋

| 단계 | 내용 | 완료 기준 |
|---|---|---|
| 1 | 비밀: `SecretSource`·테넌트별 KEK(Q2)·prod `load`만·엔진 토큰, 저장소 스캔 | G3 일부 |
| 2 | V21: `disclosure_health`·`disclosure_backup` 롤 단언, `renderer_version`, `ck_job_kind` + `KEK_REWRAP`, (재래핑 갱신 경로), Flyway 자동 실행 제거·`db migrate`·스키마 버전 가드, 헬스·준비성/활성 | G7·G3(가드) |
| 3 | 운영 필수 키 가드(prod)·관측(미터·라벨 닫힌 목록·JSON 로그)·`/internal` 포트 분리(Q5)·6B-a 503·6B-b DB 집계 | G3·G8·② |
| 4 | `KEK_REWRAP` 작업·IT, `jobs run` CLI | G5(IT) |
| 5 | 환급금 표: 서식 스키마·STANDARD v2·렌더러 2판·매니페스트 2판·골든 case-04·`TemplateVersionsIT` | G10 |
| 6 | 룰 어휘 API·화면 선택 입력·E2E | G9 |
| 7 | 성능 실측·V22(필요한 것만)·플래너 시험, 폰트 측정·상한, 스냅샷 마스킹 | G11 |
| 8 | 이미지 2개·레이어 스캔·digest 동일성 시험(PostgreSQL 포함) | G3 |
| 9 | `deploy/` 매니페스트·Kustomize·린트·tools.lock·kind-up·`deploy-smoke`·인그레스 시험·배포 전 로그 스캔, CI `kind` 잡 | G1(스크립트)·G4·G6·G8 |
| 10 | 백업·복구 CLI·`RestoreProofIT`·kind 복구 스크립트 | G2 |
| 11 | 키 회전 5종 실제 실행(kind-demo) → `verify tenant`·E2E(kind 대상) | G5 |
| 12 | 포트폴리오 문서·운영 문서·재현(G1·G12) | G1·G12 |
| 13 | 전체 check·주입 기록·보고서(알려진 한계 최종·§14 최종 목록)·태그 `phase-8` | G13 |

E2E를 kind 대상으로도 돌리는 모드(기준 URL 둘 — 직원·서명 호스트, 행 수 대조는 `kubectl exec psql`, 서버 로그는 파드 로그)를 9단계에 더한다 — G5 "회전 뒤 E2E 통과"를 배포 환경에서 증명하기 위해.

**주입(G13 최소 8 + 추가)**: 이미지에 키 파일 포함 → 레이어 스캔 / 필수 키 기본값 → YAML 스캔·기동 시험 / `/internal` 공개 진입점 노출 → 린트·kind / CronJob `Allow` → 린트 / 헬스가 테넌트 표 조회 → V21 권한 단언·IT / 메트릭 라벨에 확인서 번호 → 레지스트리 필터·시험 / 어휘 API 임계치 → 계약 / 렌더러 버전 미고정 → `TemplateVersionsIT`. 추가: 공개 진입점이 인증서 주체 헤더를 지우지 않음 → kind / `EnvSecretSource`를 prod 키 재료에 → 기동 시험 / `KEK_REWRAP`이 파기 행을 재래핑 → IT / 복구에서 객체 하나 누락 → `artifacts reconcile`(⑤ 음성 대조) / 스냅샷 마스킹 끔 → 스캔 / 앱이 기동 때 마이그레이션 → 스키마 가드·시험 / NetworkPolicy 집행 안 됨 → kind 시험의 선행 단언.

**환경**: Docker가 지금 꺼져 있다(9단계 이후 kind·이미지 단계에 필요). kind·kubeconform은 `tools.lock`의 버전·SHA-256으로 Gradle이 `build/tools`에 받는다(전역 설치 없음). 컨테이너·볼륨·kind 클러스터는 실행마다 라벨을 달고 끝나면 지운다 — 사용자 compose 볼륨과 Phase 7 P7-4의 주인 모를 볼륨은 건드리지 않는다.

## ⑧ Phase 8 질문

| # | 질문 | 권장 |
|---|---|---|
| Q1 **[규칙 충돌]** | `DatabaseHealthIndicator`의 롤. 지시문: "전용 롤 불요(앱 롤, 테넌트 바인딩 없음)", "다섯째 항목". CLAUDE.md 규칙 5와 설계서 §9 표·§12: "각 항목은 … **전용 롤**을 쓴다", "전용 헬스 롤". 덧붙여 §12는 "두 번째 항목"이라 적었다 — §9 표에서는 둘째 행이지만 구현 순서로는 다섯째(현재 4개 뒤)다 | **전용 롤 `disclosure_health`**(LOGIN·NOINHERIT·NOBYPASSRLS, `CONNECT` + `flyway_schema_history`의 `version`·`success` 컬럼 SELECT만, 테넌트 표 0을 V21이 단언). 규칙 5가 Phase 지시보다 우선한다. 앱 롤로 하면 테넌트 바인딩 없이 쓰이는 앱 롤 연결이 처음 생긴다(지금 앱 롤 연결은 `TenantScopedRepository`의 바인딩 경로로만 쓰인다 — 그래서 Spring 기본 DB 지표를 꺼 두었다). 스키마 버전 가드도 이 연결을 쓴다 |
| Q2 | KEK 테넌트별 — 지금은 전역 KEK 하나(`currentKekId()`에 테넌트 인자 없음), 테넌트는 AAD로만 묶인다. 지시문·설계서 §9 "테넌트 KEK" 문언과 다르다 | 테넌트별로 바꾼다(포트 `currentKekId(TenantId)`, 비밀 `kek/<TENANT>`). 기존 행은 키 ID로 계속 풀리고 `KEK_REWRAP`이 옮긴다 — 회전 시연이 곧 이전 경로. 설계서 §9 키 계층 문단 갱신 |
| Q3 **[넓힘] 롤** | 백업 방식 | 물리(`pg_basebackup` + WAL), 롤 `disclosure_backup`(REPLICATION, 테이블 권한 0, 백업 Job에만). 논리 백업은 BYPASSRLS 롤이 필요해 기각 — "모든 롤 NOBYPASSRLS"를 지킨다 |
| Q4 | 인그레스 컨트롤러 | Traefik(IP 한도·크기 한도·mTLS·주체 전달이 한 제품 기능). 대안 Envoy Gateway |
| Q5 **[불변식 추가]** | `/internal`을 앱의 별도 포트로 | 둔다 — 인그레스 경로 실수 하나로 열리지 않게(포트 ≠ 접두면 404, NetworkPolicy가 포트 단위) |
| Q6 | 이미지 수 — 지시문 "앱·화면·마이그레이션 잡" | 2개(마이그레이션 Job·CronJob은 앱 이미지의 CLI) — 기동 대조 목록과 적용 목록이 같은 jar |
| Q7 | 앱의 Flyway 자동 실행 제거 — 테스트·데모·E2E가 기동 마이그레이션에 기대고 있다 | 전부 `db migrate`/하네스로 옮기고, 앱은 버전 가드만 |
| Q8 **[넓힘] 인가 표** | 룰 어휘 API의 범위와 추천사유 코드 포함 여부 | 사람 역할 셋 모두 TENANT. 추천사유 코드는 **넣는다**(코드·표기만, `auto` 제외) — 코드 선택은 설계사의 입력이고 설명 텍스트는 여전히 설계사가 쓴다(규칙 7). Phase 7 "자동완성·예시 금지"는 텍스트 제안 금지로 읽는다 — 다르게 읽으면 추천사유만 자유 입력으로 남긴다 |
| Q9 **[계약 추가] ×2** | 환급금 표 — 서식 스키마 `render.columns`, 증거 매니페스트 2판 | ⑥대로 |
| Q10 | 6B-a 룰 없는 테넌트 | 503 `TENANT_RULES_NOT_ACTIVE` **[계약 추가]** + 온보딩 순서 런북 |
| Q11 | 6B-b 게이트 한도 | 게이트 감사 행 DB 집계 + 인그레스 주체별 한도(벨트). 공개 서명 테넌트 한도는 메모리 유지 + IP 한도 |
| Q12 | 엔진 서비스 토큰 보관 — 설계서 §9 "Phase 8에서 Phase 2 볼트(DB, 테넌트 DEK)" | `SecretSource`로(비밀 저장소가 단일 출처 — DB 볼트는 토큰 쓰기 API·회전 경로를 따로 만들어야 한다). 설계서 문장 정정 |
| Q13 | `EnvSecretSource` | 지시문대로 두되 prod에서 키 재료에 쓰면 기동 실패 |
| Q14 | G1·G12 "사람 재현" — 누가 | ① 먼저 맥락 없는 별도 에이전트가 새 클론에서 README만 보고 재현(막힌 곳 기록) ② 그 뒤 **사용자(또는 지정한 사람)** 재현 — 기록 양식은 보고서에 둔다. 사람 재현이 수용 전에 불가하면 ①만으로 보고하고 사람 재현을 수용 조건으로 남길지 |
| Q15 | 회전 "5종" | KEK·커서·요청 해시·영수증·데모 OIDC. TSA 신뢰 앵커(앵커 집합)는 6번째로 실행까지, 엔진 토큰은 런북만(엔진이 겹침 기간 두 해시를 인정 — E3.1) |
| Q16 | CronJob 주기 | ③의 표 |
| Q17 | 성능 실측 규모 | 지시문 "10만 행 수준" — 감사 10만·확인서 10만·플래그 2만, 테넌트 둘(RLS 경계 포함) |
| Q18 | kind CI 잡 | PR·main에서(빌드와 같은 조건) — 시간이 길면(>20분) main·태그만으로 낮추고 보고 |
| Q19 | `docker-compose.yml` | 개발용으로 유지(PostgreSQL digest 고정만), README의 둘러보기는 kind. 설계서 §12 Phase 8 완료 기준("docker compose up …")은 이 지시문의 G1로 대체됐다고 §12에 적는다 |
