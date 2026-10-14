# 백업·복구 런북 (Phase 8 ⑤, G2)

> 학습·포트폴리오 저장소의 운영 문서다. 보존 기한 등 수치는 예시값이다. 설계 근거는 설계서 §11 "백업·복구" 줄(승인 Q3).

## 무엇을 백업하나

| 대상 | 방법 | 롤·키 |
|---|---|---|
| PostgreSQL 전체 | 물리 백업 `pg_basebackup -Ft -X fetch`(복제 프로토콜) | `disclosure_backup` — REPLICATION, CONNECT 없음(일반 세션 거부), 표 권한 0(V22가 단언 — 주입 P8-5/P8-44). 연결은 `verify-full` + 채널 바인딩(SEC-7) |
| DB 백업 파일 | 봉투 AES-256-GCM(`backup seal`) | 백업 키 `backup/key`(비밀 출처 — 테넌트 KEK와 별개) |
| 봉인 산출물 | 모든 버전·삭제 마커 복제(`backup objects export`) — 보존 기한(retain-until)·법적 보류 표지를 함께 | 산출물 저장소 → 백업 저장소 |
| 백업 저장소 | Object Lock 버킷, 경로 `runs/<실행>/objects/…`·`runs/<실행>/db.gabk` | 기한 = 같은 실행 산출물의 가장 늦은 retain-until을 날 단위로 올림(`--retain-until auto`, 이르면 업로드 거부) |

논리 덤프(`pg_dump`)는 쓰지 않는다 — RLS 때문에 BYPASSRLS가 필요하고, 이 시스템의 모든 롤은 NOBYPASSRLS다(마이그레이션이 단언한다).

**법적 보류**: 보류 중인 객체는 끝이 정해져 있지 않다. 백업 사본(DB 봉투·객체 복제본)에는 보류를 걸지 않고, 객체 복제본에 보류 표지만 옮겨 두며, 보류는
**복구 때** 객체에 다시 붙는다. 백업 사본이 기한 뒤 사라져도 원본이 보류를 쥐고 있으므로 통제는 유지된다(설계서 §11).

**KEK는 백업에 없다** — 감싼 DEK만 DB에 있다. 테넌트 KEK·백업 키는 비밀 저장소의 백업으로 따로 지킨다. 회전한 옛 KEK는 그 KEK로 감싼 행이 든 백업의
보존 기한까지 보관한다(`keys.md`).

## 정기 백업

CronJob `ga-backup`(매일 01:40 KST, `deploy/base/backup.yaml`): initContainer가 `pg_basebackup` → 본 컨테이너가 `backup objects export --run <실행>` →
`backup seal`(평문 tar는 바로 지운다) → `backup upload --retain-until auto`. 출력 줄 `BACKUP_OBJECTS_EXPORTED`·`BACKUP_SEALED`·`BACKUP_UPLOADED`·
`BACKUP_RUN`(값·경로 없이 수·해시·기한만).

## 복구

빈 환경(새 DB 볼륨·새 산출물 버킷)에 오버레이 `kind-restore`(운영은 같은 component `deploy/components/restore`를 운영 오버레이에) + 설정 `GA_RESTORE_RUN=<실행>`:

1. DB StatefulSet의 initContainer `restore`: 데이터 디렉터리가 이미 있으면 `RESTORE_SKIPPED`(덮어쓰지 않는다) → `backup download` → `backup open`(봉투 검증·복호)
   → tar 풀기 → `RESTORE_EXTRACTED`.
2. Job `ga-restore-objects`: `backup objects import --run <실행>` — 버전 순서대로 다시 쓰고 보존 기한·보류를 다시 건다. 대상 버킷이 비어 있지 않으면 거부, 단 그
   실행과 **같은 모양**이면 쓰지 않고 성공(`alreadyPresent=true` — 중단된 복구의 재시도, KIND-8).
3. `db migrate`(멱등 — 백업 뒤 배포된 마이그레이션이 있으면 적용) → 앱 롤아웃.
4. 검증 — 아래.

## 복구 증명 (G2)

| 검사 | 명령 | 기대 |
|---|---|---|
| 표 내용 | `kind.sh restore`가 백업 직전에 저장한 표별 내용 해시와 복구 뒤 해시 대조 | `RESTORE_TABLES MATCH tables=38` |
| 체인·채번·객체·앵커·영수증·KEK | 클러스터 안 `verify tenant --tenants all` | 모든 테넌트 MATCH |
| 객체 잠금 | `artifacts reconcile`(`RestoreProofIT`) | 실패 0 |
| 증거 패키지 | 복구 전·후 같은 문서의 증거 ZIP(`RestoreProofIT`) | 바이트 동일 |
| 영수증·TSA | `verify package --receipt … --tsa-trust …`(`RestoreProofIT`) | `VERIFY_PACKAGE MATCH findings=0` |
| 음성 대조 | 객체 하나를 뺀 백업으로 복구 | 그 키가 `OBJECT_MISSING`으로 보고(`RestoreProofIT`) |

자동화: `RestoreProofIT`(통합 테스트 — 같은 순서를 Testcontainers로, 백업 파일 평문 스캔 포함 — 주입 P8-45·P8-46), kind는
`deploy/scripts/kind.sh backup <클러스터>` → `kind.sh restore <클러스터> <실행>`(CI 잡 `kind`가 같은 순서).

### 실행 기록 (2026-10-10, 로컬 kind — 11단계 깨끗한 실행)

```
BACKUP_OBJECTS_EXPORTED run=20261010T105914Z keys=59 versions=59 deleteMarkers=0 retained=35 held=2 maxRetainUntil=2031-10-10T15:00:00Z
BACKUP_SEALED bytes=53686272 sha256=d678c9c69a5a…
BACKUP_UPLOADED run=20261010T105914Z retainUntil=2031-10-11T00:00:00Z artifactRetentionFloor=2031-10-10T15:00:00Z
BACKUP_OPENED bytes=53686272 sha256=d678c9c69a5a…
BACKUP_OBJECTS_IMPORTED run=20261010T105914Z keys=59 versions=59 deleteMarkers=0 retained=35 held=2 maxRetainUntil=2031-10-10T15:00:00Z
RESTORE_TABLES MATCH tables=38
```

복구 뒤 스모크 0 실패, 세 테넌트 `verify tenant` MATCH. 보존 기한 2031은 데모 룰의 예시 보존기간(5년)에서 나온 값이다.
