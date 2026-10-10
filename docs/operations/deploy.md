# 배포 런북 (Phase 8 ①③④)

> 학습·포트폴리오 저장소의 운영 문서다. 도메인·레지스트리·버킷 이름은 `.invalid` 예시값이고, 운영 오버레이의 이미지 digest는 0으로 채운 자리표시다.
> kind에서 끝까지 돌려 보는 방법은 README의 "15분 둘러보기".

## 묶음

| 경로 | 내용 |
|---|---|
| `deploy/images/` | app(Temurin Alpine JRE 25, 비루트, 읽기 전용 루트 FS)·web(nginx-unprivileged, 정적 화면) Dockerfile — 베이스는 digest 고정. `./gradlew :disclosure-app:appImage :disclosure-app:webImage`, `imageTest`가 레이어 전수 스캔(키·비밀·PEM·개인정보 0 — 주입 P8-23) |
| `deploy/base/` | 네임스페이스·서비스 계정·app·web Deployment·마이그레이션 Job·CronJob 15개·백업 CronJob·NetworkPolicy |
| `deploy/components/ingress` | Traefik 두 컨트롤러(공개 `ga-ingress`, 내부 `ga-ingress-internal`) — `ingress.md` |
| `deploy/components/secrets-eso` | External Secrets Operator 템플릿(SecretStore 네임스페이스마다, ExternalSecret) — 실제 비밀 저장소 연동은 인터페이스·문서까지 |
| `deploy/components/restore` | 복구 initContainer·객체 복구 Job — `backup-restore.md` |
| `deploy/overlays/prod` | 운영 프로파일·ESO·LB — 복사해 조직의 값으로 바꾼다 |
| `deploy/overlays/kind-demo`, `kind-restore` | 데모 프로파일·클러스터 안 PostgreSQL·SeaweedFS·kind 비밀 |
| `deploy/tools.lock` | kind·kubectl·kubeconform·노드 이미지·Traefik의 버전·SHA-256(`./gradlew deployTools`가 `build/tools`에 받는다 — 전역 설치 없음) |

린트 `./gradlew :disclosure-app:deployTest`: 모든 오버레이를 렌더 → kubeconform strict → 규칙(digest 고정, 비밀 마운트 키 ↔ 경로, 운영 프로파일 가드를 렌더된
파드 환경으로 실행, 진입점 경로, CronJob, 무중단 롤링, DB 연결 `verify-full` 등).

## 운영 배포 순서

1. **이미지** — 릴리스 파이프라인이 app·web 이미지를 빌드·푸시하고 digest를 `overlays/prod`의 `images`에 넣는다(태그 금지 — 린트).
2. **DB 롤** — 롤 9종(아래)을 DB 관리자가 만든다(`docker/postgres/init-roles.sql`이 형태의 정본, 비밀번호는 비밀 저장소). 마이그레이션이 존재·속성·멤버십을 단언한다.
3. **비밀** — 비밀 저장소에 DB 자격 증명·S3 자격 증명·서버 비밀(`api/cursor`·`api/request-hash`·`api/customer-receipt`·`tsa/trust-anchors.pem`·`backup/key`·
   테넌트마다 `kek/<T>/<T>-KEK-1`·엔진 토큰 `engine/<T>`). ESO가 Secret으로 동기화한다. 값은 명령줄·환경변수·로그에 두지 않는다(`keys.md`).
4. **적용** — `kubectl kustomize deploy/overlays/<조직 오버레이> | kubectl apply -f -`. 마이그레이션 Job `ga-db-migrate`(`db migrate`, 마이그레이터 롤)이 먼저
   끝나야 한다 — 앱은 기동 때 마이그레이션하지 않고, 배포물의 최고 버전 ≠ DB 최고 성공 버전이면 두 버전만 담은 문장으로 기동을 멈춘다(스키마 버전 가드 — 헬스 롤).
   끝난 Job의 파드 틀은 바꿀 수 없으므로 설정이 바뀐 재적용은 그 Job을 지우고 다시 만든다(`db migrate`는 멱등 — KIND-7).
5. **기동 가드** — `prod` 프로파일은 필수 키(아래)가 하나라도 없거나, 스텁 TSA·환경변수 비밀·로컬 기본 자격 증명·데모 프로파일 같은 운영 금지 값이면 **키 이름만**
   담은 문장으로 기동을 멈춘다(`ProdStartupGuard` — 주입 P8-10·P8-30·P8-31).
6. **테넌트** — `tenant-onboarding.md`(KEK 먼저).
7. **확인** — 준비성 `/actuator/health/readiness`(관리 포트 8082 — DB·저장소 Object Lock·IdP JWKS), `verify tenant`(VERIFY_TENANT CronJob을 한 번).

### 운영 필수 키(`application-prod.yaml` — 기본값 없음)

`DISCLOSURE_DB_URL`, 롤별 자격 `DISCLOSURE_{APP,MIGRATOR,OPERATOR,JOB_LOCK,HEALTH}_{USER,PASSWORD}`, `GA_JWT_ISSUER`·`GA_JWT_AUDIENCE`·`GA_JWT_JWK_SET_URI`,
`GA_SECRETS_DIR`, `GA_TSA_URL`, `GA_SIGN_LINK_BASE_URL`(서명 호스트의 `…/s#`), `GA_PUBLIC_SIGN_MIN_RESPONSE_MILLIS`(공개 서명 응답 하한), `GA_CLIENT_CERT_SUBJECT_HEADER`
(§14 #23 — 미결정). 신뢰 앵커는 비밀 `tsa/trust-anchors.pem`.

## DB 롤 9종

| 롤 | LOGIN | 하는 일 |
|---|---|---|
| `disclosure_migrator` | ○ | 스키마 소유, `db migrate`(마이그레이션 Job)에만 |
| `disclosure_app` | ○ | 앱·CLI의 DML — RLS 적용, 테넌트마다 바인딩 |
| `disclosure_operator` | ○ | 운영자 CLI의 테넌트 목록(`--tenants all`) 조회만 |
| `disclosure_job_lock` | ○ | 작업 advisory 잠금 전용 커넥션 — 표 권한 0 |
| `disclosure_health` | ○ | 헬스·스키마 버전 가드 — `flyway_schema_history` 두 컬럼만 |
| `disclosure_backup` | ○ | 물리 백업(REPLICATION) — CONNECT 없음, 표 권한 0 |
| `disclosure_destroyer` | × | 파기 함수 EXECUTE만(앱이 트랜잭션 안에서 `SET LOCAL ROLE`, 권한 비상속) |
| `disclosure_abandoner` | × | 초안 폐기 함수 EXECUTE만(같은 방식) |
| `disclosure_destroy_definer` | × | 파기·폐기·재래핑 함수의 소유자(SECURITY DEFINER) — 표 소유자가 아니라 RLS를 따른다 |

모든 롤이 NOBYPASSRLS다. 정본 설명은 설계서 §9 "DB 롤".

## 무중단 롤링

app Deployment: `maxUnavailable: 0`·`maxSurge: 1`, preStop 10초 대기, 종료 유예 45초(린트 — 주입 P8-52). 엔드포인트 제거가 진입점에 퍼지기 전에 프로세스가
끝나면 502가 난다(KIND-11 — kind 회전 첫 실행에서 실측). `kind.sh rotate`가 재기동마다 5xx·연결 실패 0을 단언한다.
