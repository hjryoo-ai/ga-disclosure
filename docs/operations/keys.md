# 키 회전 런북 (Phase 8 ④·11단계, G5)

> 학습·포트폴리오 저장소의 운영 문서다. 절차의 **순서·영향·검증 명령**을 정하고, 값(기간·이름)은 예시다.
> kind 데모에서의 실제 실행은 `deploy/scripts/kind.sh rotate <클러스터> [키]`가 한다 — 아래 "실행 기록".

## 공통 원칙

- 서버 비밀은 전부 비밀 출처(`SecretSource`)의 **이름**으로 읽는다(설계서 §9). 운영은 비밀 저장소 → External Secrets Operator → Secret 볼륨
  (`ga.secrets.dir`), 로컬·kind는 `~/.ga-disclosure/...`의 소유자 전용 파일. **앱은 비밀을 만들지 않는다** — 없으면 그 비밀이 필요한 순간 실패하고 문장은
  이름뿐이다.
- 웹 키(커서·요청 해시·영수증·데모 OIDC)와 TSA 스텁 키는 **기동 때** 읽는다 → 값을 바꾼 뒤 `kubectl rollout restart deployment/ga-app`. 작업 CronJob은
  다음 실행부터 새 값이다(파드마다 새로 뜬다). 테넌트 KEK 바이트는 처음 쓸 때 읽어 프로세스에 둔다 — 새 KEK ID는 새 이름이라 재기동이 필요 없지만, 마운트(볼륨
  `items` 줄)가 바뀌면 롤링된다.
- **재기동은 무중단이어야 한다** — 앱 Deployment는 `maxUnavailable: 0`, preStop 10초 대기, 종료 유예 45초(`base/app.yaml`, 린트
  `theAppDrainsBeforeItStopsAndNeverDropsBelowItsReplicas`). kind 회전 첫 실행에서 재기동 직후 502가 나왔다(종료 신호와 엔드포인트 제거가 동시라 진입점이
  닫히는 파드로 보낸 요청) — 지금은 `kind.sh`가 재기동마다 0.2초 간격으로 직원 API를 불러 5xx·연결 실패 0을 단언한다(약 100요청).
- 값은 어디에도 출력하지 않는다(명령줄·환경변수·로그 0 — `kind.sh logscan`이 회전한 옛 값까지 바늘로 찾는다). 옛 값은 **지우지 않고 보관**한다(되돌리기·백업
  복구 — 아래 키별 "옛 값").
- 운영의 새 값 만들기: 비밀 저장소에서 새 버전(32바이트 무작위, base64 — 예: `openssl rand -base64 32`를 저장소에 직접, 셸 기록·파일에 남기지 않는다) →
  ESO 동기화(`refreshInterval` 또는 `force-sync` 주석) → Secret 반영 확인(값이 아니라 `resourceVersion`) → 롤링 재기동.

## 키별

| 키(비밀 이름) | 영향(회전 직후) | 절차 | 검증 | 옛 값 |
|---|---|---|---|---|
| 커서 키 `api/cursor` | 진행 중 목록 커서가 무효 — 400 `INVALID_CURSOR`(화면은 처음 쪽부터 다시) | 새 값 → 재기동 | 회전 전 커서 → 400 `INVALID_CURSOR`, 새 목록 200 | 필요 없음(커서는 저장하지 않는다) |
| 요청 해시 키 `api/request-hash` | 멱등 기록 창(룰 `idempotencyTtlHours`, 데모 24시간) 안의 같은 키·같은 본문 재전송이 재생 대신 422 `IDEMPOTENCY_KEY_REUSED`(요청 해시가 달라진다). 새 키의 청구는 정상 | 새 값 → 재기동. **무중단 선택지**: 쓰기가 적은 시간에 돌리고 창 길이만큼 422를 감수하거나, 창이 지난 뒤의 재시도만 있는 업무면 영향 0 | 회전 전 키 재전송 → 422, 새 키 → 201, 같은 새 키 재전송 → 재생(`Idempotency-Replayed: true`) | 필요 없음 |
| 영수증 키 `api/customer-receipt` | 고객 등록 영수증 ID는 (테넌트, 가명, 등록 키)의 결정론적 파생이다 — 멱등 기록이 **만료된 뒤** 같은 등록 키로 다시 보내면 가명은 같고 `receiptId`가 다르다(창 안에서는 저장된 응답 재생 — 변화 없음). 설계서 §9 배포 노트 | 새 값 → 재기동 | 만료 뒤 같은 등록 키 → 회전 전 같은 영수증, 회전 뒤 같은 가명·다른 영수증(kind는 만료를 슈퍼유저 SQL로 앞당긴다 — 데모 장치) | 영수증 ID를 외부에 보관하는 업무가 있으면 옛 값으로 재현할 수 있게 보관 |
| 데모 OIDC 서명 키 `demo/oidc-signing`(데모 전용) | 발급된 토큰(데모 1시간) 무효 → 401, 다시 로그인 | 새 값 → **공개키 재게시**(ConfigMap `ga-demo-oidc-public`, 서명 키에서 유도 — 비밀 아님) → 재기동 | 옛 토큰 401, 새 로그인 200 | 필요 없음. 운영은 사내 IdP의 JWKS(`ga.api.jwt.jwk-set-uri`) — 회전은 IdP가 하고 앱은 `kid`로 고른다(겹침 기간은 IdP 몫) |
| 테넌트 KEK `kek/<T>/<T>-KEK-<n>` | 등록 순간부터 **새 감싸기는 새 KEK**, 기존 DEK는 재래핑 전까지 옛 KEK로 풀린다(둘 다 마운트돼 있어야 한다) | 아래 "테넌트 KEK" | 아래 | **지우지 않는다** — 아래 |
| TSA 신뢰 앵커 집합 `tsa/trust-anchors.pem` | 새 TSA(또는 스텁) 키의 토큰을 검증하려면 새 앵커가 집합에 있어야 하고, **옛 토큰 검증에는 옛 앵커가 남아 있어야 한다** | 집합에 새 앵커를 **덧붙인다**(교체가 아니다) → 재기동 → 새 토큰 발급 | 옛·새 토큰 모두 검증(`verify tenant` MATCH), 대조: 새 앵커만으로는 옛 앵커 토큰이 `TSA_UNTRUSTED` | 그 앵커가 서명한 토큰이 든 문서의 보존 기한까지 집합에 둔다 |
| 엔진 서비스 토큰 `engine/<T>`(런북만) | 엔진이 겹침 기간 동안 두 토큰 해시를 인정한다(엔진 E3.1) — 끊김 없음 | 엔진에 새 토큰 해시 등록 → 이쪽 비밀 교체·재기동 → 겹침 종료 뒤 엔진에서 옛 해시 제거 | 회전 뒤 비교 요청 정상(엔진 응답 스냅샷 저장) | 겹침 종료 뒤 폐기 |
| 백업 키 `backup/key` | 새 백업만 새 키로 봉한다. 봉투 머리에 키 ID가 없다 — **옛 백업을 열려면 복구 환경의 `backup/key`에 옛 값을 둔다**(틀린 키는 `NotOpenable`, 평문을 남기지 않는다) | 새 값 → 다음 백업부터(백업 CronJob은 파드마다 새로) | 다음 백업 `backup open` 성공, 옛 백업은 옛 값으로 열림 | 그 키로 봉한 마지막 백업의 보존 기한까지 보관(알려진 한계: 키 ID 없는 봉투) |
| DB 롤 비밀번호·S3 자격 증명 | 연결 풀이 새 연결부터 실패 → 롤링 중 짧은 오류 | DB/S3에 새 값(옛 값과 겹치게 — S3는 키 두 개, DB는 롤을 바꾸지 않고 `ALTER ROLE … PASSWORD`) → Secret → 재기동 → 옛 값 폐기 | 준비성 `database`·`storage` UP | 복구 환경은 백업 시점의 롤 비밀번호 해시가 함께 온다(§11 백업·복구 — 비밀 저장소에 그 값이 있어야 한다) |

## 테넌트 KEK

키 계층: 테넌트 KEK(비밀 출처, DB에 없음) → 문서·고객 DEK(감싸서 DB — `document_key.wrapped_dek`, `customer_data_key.wrapped_key`, 끝난 작업의
`async_job.report_key_wrapped`). 레지스트리 `tenant_kek`(테넌트당 CURRENT 하나, 옛 것은 RETIRED — 한 번만). 재래핑은 작업 `KEK_REWRAP`(운영자 CLI만,
기본 dry-run, 행마다 감사 `KEK_REWRAPPED`, 파기된 키는 대상 아님 — 묘비를 되살리지 않는다).

1. **프로비저닝** — 비밀 저장소에 `kek/<T>/<T>-KEK-<n+1>`(32바이트). 로컬·kind: `crypto kek init --tenant T --kek-id T-KEK-<n+1> --secrets-dir D`.
2. **마운트** — Secret 볼륨 `items`에 `kek.<T>.<T>-KEK-<n+1> → kek/<T>/<T>-KEK-<n+1>`(웹 Deployment·작업 CronJob 둘 다 — 운영은 오버레이 커밋, kind는
   `kind.sh`의 `mount_keks`). 롤아웃 완료까지 기다린다. **옛 KEK 줄은 그대로 둔다.**
   - 순서가 틀리면(마운트 전에 등록): 등록 명령이 "비밀 출처에서 감싸기·풀기"를 확인하다 `SecretMissingException`(이름만)으로 거부한다 — 온보딩 런북
     (`tenant-onboarding.md`)과 같은 증상.
3. **등록** — `crypto kek register --tenant T --kek-id T-KEK-<n+1> --operator <id>` → `KEK_REGISTER T T-KEK-<n+1> CURRENT`(감사 `KEK_REGISTERED`).
   이 순간부터 새 DEK는 새 KEK로 감싼다.
4. **재래핑 dry-run** — `crypto kek rewrap --tenants T --operator <id>` → `KEK_REWRAP T to=T-KEK-<n+1> DRY_RUN rewrapped=0 pending=<N> …`(쓰기·행 감사 없음).
5. **적용** — `--apply yes` → `rewrapped=<N> failed=0`. 행마다 트랜잭션(재개 가능 — 중간에 멈추면 다시 돌린다), `failed`는 풀 수 없는 행(무결성 신호 —
   `verify tenant`의 `KEK_UNWRAP_FAILED`와 같은 원인을 본다).
6. **두 번째 적용** — `rewrapped=0 pending=0`(멱등). 이것이 완료 조건이다.
7. **검증** — `verify tenant --tenants T` MATCH(검사 `KEKS`: 레지스트리 밖 KEK ID로 감싼 키 0, 살아 있는 키 전부 풀림), 옛 KEK ID로 감싼 살아 있는 키 0
   (kind는 슈퍼유저 SQL로 센다), 산출물 열람(증거 ZIP 복호화) 정상.
8. **옛 KEK** — DB에는 더 이상 쓰이지 않지만 **회전 전에 뜬 백업**의 DEK는 옛 KEK로 감싸져 있다. 그 백업들의 보존 기한(§11 — 같은 실행 산출물의 가장 늦은
   보존 기한 이상)이 끝날 때까지 비밀 저장소에 둔다. 마운트 줄은 7 뒤 지워도 된다(복구할 때 다시 마운트 → 복구 → 재래핑). 레지스트리 행은 RETIRED로 남는다
   (기록 — 지우지 않는다).

**주의(지시문)**: 재래핑은 보존기한을 건드리지 않는다(연장 전용 작업이 아니다). 감사는 행마다 남고 작업 보고서(`kek-rewrap-report` 계약)에 대상 수·KEK 쌍이
있다. 재래핑 함수는 새 바이트가 같은 DEK인지 DB가 확인할 수 없다 — 어댑터가 쓰기 전에 다시 풀어 같은 DEK인지 보고, 일일 `verify tenant`가 살아 있는 키를
전부 풀어 본다(설계서 v1.18 ① 보안 검토).

### 2단 업그레이드(전역 KEK 시절 볼륨 — Phase 2~7)

Phase 8 1b 이후 이미지는 전역 KEK(`~/.ga-disclosure/kek.json`, `ga.crypto.local-kek-file`)를 읽지 않는다. 그 시절 데이터는 **이행 판**(1a, 커밋 `270e18d`)
이미지로 먼저 올려 테넌트 KEK를 등록하고 `crypto kek rewrap --tenants all --apply yes`를 두 번째 실행 0건까지 돌린 뒤(`verify tenant`에
`KEK_UNREGISTERED` 0) 1b 이후 이미지로 올린다. 로컬 데모 볼륨은 새로 만드는 편이 빠르다.

**실행 기록 (2026-10-10, 로컬 — 일회용 컨테이너, 세 데모 테넌트)**: Phase 7 판(`9b7f2f1`)의 `seed.sh`로 전역 KEK 시절 데이터를 만든 뒤 이 순서를 그대로 돌렸다.

| 단계 | DEMO1 | DEMO2 | DEMO3 |
|---|---|---|---|
| Phase 7 시드 뒤 전역 키 `KEK-LOCAL-1`로 감싼 살아 있는 키(문서·고객·작업 보고서) | 15 (5·1·9) | 8 (1·1·6) | 6 (1·1·4) |
| 1a: `crypto kek init` + `register` | `DEMO1-KEK-1 CURRENT` | `DEMO2-KEK-1 CURRENT` | `DEMO3-KEK-1 CURRENT` |
| `rewrap --tenants all`(dry-run) | pending=15, 행 그대로 | pending=8 | pending=6 |
| `--apply yes` 1회 | rewrapped=15 failed=0 | rewrapped=8 failed=0 | rewrapped=6 failed=0 |
| `--apply yes` 2회 | rewrapped=0 pending=0 | 0 | 0 |
| 감사 `KEK_REWRAPPED` | 15행 | 8행 | 6행 |
| 레지스트리 밖 KEK로 감싼 살아 있는 키(세 테넌트 합) | 29 → **0** | | |
| HEAD: 롤 두 개·CONNECT(docs/DEVELOPMENT.md "업그레이드") → `db migrate`(V22~V24) → 전역 키 파일을 치운 채 `verify tenant --tenants all` | MATCH | MATCH | MATCH |

작업 보고서 키는 재래핑 작업 자체도 보고서를 남기므로 실행마다 늘어난다(새 키는 처음부터 테넌트 KEK로 감싼다). HEAD의 `verify tenant`는 살아 있는 키를 전부
풀어 보며(검사 `KEKS`), 전역 키 ID 읽기 경로는 1b(`9857b5b`)에서 지워졌다 — 코드·설정·스크립트 스캔 `GlobalKekPathScanTest`(주입 P8-3: 설정 한 줄을 남기면 실패).

### TSA 신뢰 앵커(kind)

운영(http 모드)은 비밀 `tsa/trust-anchors.pem`(집합)을 신뢰한다. 스텁 모드의 앱은 자기 인증서 하나(`/tmp/tsa-trust.pem`)만 내보내므로, kind는 운영과
같은 이름의 집합을 비밀 디렉터리에 두고(`secrets`가 지금 스텁 인증서 하나로 시작) 클러스터 안 `verify`가 그 집합(`/var/run/ga-secrets/tsa/trust-anchors.pem`)
을 쓴다. 회전: 스텁 키 저장소 `demo/tsa-stub.p12`를 새로 → 새 인증서를 집합에 **덧붙임** → 재기동 → 새 키로 앵커 하나(오늘 앵커는 시드가 이미 만들었으므로
데모 시계 `+P1D` — 데모 장치) → 집합으로 MATCH. 대조: 새 인증서만으로 검증하면 **옛 앵커만** `TSA_UNTRUSTED`(새 키 앵커는 통과).

## kind에서의 실행(`deploy/scripts/kind.sh rotate <클러스터> [cursor|request-hash|receipt|demo-oidc|kek|tsa|all]`)

키마다 회전 전에 무언가를 만들고(커서·멱등 청구·등록 영수증·토큰·감싼 DEK·앵커 토큰) 회전 뒤 영향을 단언한 다음, 클러스터 안 `verify tenant`(VERIFY_TENANT
CronJob의 파드 틀)가 모든 테넌트 MATCH여야 끝난다. HTTP는 공개 진입점 A(직원 호스트), KEK 명령은 KEK_REWRAP CronJob의 파드 틀(클러스터 Secret의 KEK로
감싸고 푼다 — 마운트가 됐는지가 곧 시험). 옛 값은 `~/.ga-disclosure/kind/<클러스터>/rotate/retired/`(소유자 전용)로 옮기고, 로그 스캔이 그 값도 찾는다.
그 뒤 `./gradlew :disclosure-web:e2eKind -Pkind.cluster=<클러스터>`가 같은 E2E를 클러스터의 진입점 A·B·C로 돈다.

## 실행 기록 (2026-10-10, 로컬 kind — CI 잡 kind가 같은 순서를 돈다)

새 클러스터에서 `up → deploy → smoke(0 실패) → seed(세 테넌트 MATCH) → backup → restore(표 38개 해시 일치) → smoke(0 실패) → rotate all → e2eKind → logscan`.

| 키 | 단언(전부 PASS) |
|---|---|
| 커서 | 회전 전 커서 → 400 `INVALID_CURSOR`, 새 목록 200 |
| 요청 해시 | 회전 전 재전송은 재생(`Idempotency-Replayed: true`) → 회전 뒤 같은 키·본문 422 `IDEMPOTENCY_KEY_REUSED`, 새 키 201·재생 |
| 영수증 | 만료 뒤 같은 등록 키: 회전 전 같은 가명·같은 영수증 → 회전 뒤 같은 가명·다른 영수증 |
| 데모 OIDC | 옛 토큰 200 → 회전 뒤 401, 새 로그인 200 |
| 테넌트 KEK(DEMO1) | 옛 KEK로 감싼 살아 있는 키 17 → 등록 `DEMO1-KEK-2 CURRENT` → dry-run `pending=17`(행 그대로) → 적용 `rewrapped=17 failed=0` → 두 번째 적용 `rewrapped=0 pending=0` → 옛 KEK 행 0, 감사 `KEK_REWRAPPED` 17행, 레지스트리 CURRENT/RETIRED |
| TSA 앵커 집합 | 집합 1개 → 스텁 키 회전·덧붙임 → 2개, 새 키 앵커(`+P1D`) 생성, 대조: 새 인증서만으로는 옛 앵커만 `TSA_UNTRUSTED` |
| 재기동(다섯 번) | 각 약 100요청 중 5xx·연결 실패 0 |

회전 뒤 클러스터 안 `verify tenant` 세 테넌트 MATCH, 클러스터 대상 E2E 12/12(산출물 스캔 0), 로그 스캔 0(바늘 98 — 회전한 옛 키 포함). 같은 클러스터에서
서명 창을 상대 경로로 되돌린 웹 이미지(주입)는 E2E의 현장 서명 흐름을 실패시켰고, 원복 뒤 다시 12/12였다.
