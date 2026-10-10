# 예약 작업 표 (Phase 8 ③, G6)

> 학습·포트폴리오 저장소의 운영 문서다. 주기는 예시값이고, 이 문서는 **작업 종류 ↔ CronJob 1:1**과 **겹침 방지 두 겹**을 정한다.

정본은 `deploy/base/cronjobs.yaml`(작업 종류마다 CronJob 하나)과 `deploy/base/backup.yaml`(백업). deploy 린트 `CronJobManifestTest`가 `JobKind` 열거와
CronJob 라벨 `ga/job-kind`를 양방향으로 대조하고(빠진 종류·남는 CronJob 둘 다 실패), 모든 CronJob이 아래 공통 값을 갖는지 본다.

## 공통 값

| 항목 | 값 | 이유 |
|---|---|---|
| `timeZone` | `Asia/Seoul` | 날짜 경계(앵커 날짜·만료·보존)가 KST다(설계서 §6.7). 노드 시간대에 기대지 않는다 |
| `concurrencyPolicy` | `Forbid` | 같은 CronJob의 실행이 겹치지 않는다(첫 겹) |
| 앱 잠금 | 테넌트 × 작업 종류 advisory 잠금(전용 롤 `disclosure_job_lock`) | Forbid가 못 막는 겹침 — 수동 `kubectl create job --from`, 복제한 CronJob, 다른 클러스터 — 을 막는다(둘째 겹). 바쁜 테넌트는 `JOB_BUSY`, 종료 코드 2 |
| `startingDeadlineSeconds` | 300(NOTIFY는 30) | 놓친 실행을 늦게 몰아서 돌리지 않는다 |
| `backoffLimit`·`activeDeadlineSeconds` | 1 · 종류별 | 실패한 작업은 한 번만 다시, 멈춘 작업은 끊는다 |
| 명령 | app 이미지의 CLI `jobs run <KIND> --tenants all --operator cronjob`(ANCHOR는 `anchor run`) | 내부 작업 API와 같은 처리기 표(6A) — 경로가 둘이어도 처리는 하나 |

## 표

| CronJob | 종류 | 주기(KST) | 비고 |
|---|---|---|---|
| `ga-job-anchor` | ANCHOR | 매일 00:20 | 모든 테넌트를 한 머클 루트로(플랫폼 배치), TSA 토큰 |
| `ga-backup` | (작업 종류 아님) | 매일 01:40 | 물리 백업 + 산출물 모든 버전 복제 — `backup-restore.md` |
| `ga-job-verify-tenant` | VERIFY_TENANT | 매일 02:00 | 체인·채번·객체·앵커·영수증·KEK 전수, 불일치는 `CHAIN_BROKEN` 플래그 |
| `ga-job-reconcile` | RECONCILE | 매일 03:00 | 객체 잠금 대사(`artifacts reconcile`) |
| `ga-job-destroy` | DESTROY | 매일 04:00 | 보존기간 끝난 문서 파기(보류 건너뜀) |
| `ga-job-abandon-drafts` | ABANDON_DRAFTS | 매일 05:00 | 유휴 초안 폐기(룰의 대기 일수) |
| `ga-job-contract-link-unmatched-purge` | CONTRACT_LINK_UNMATCHED_PURGE | 매일 05:30 | 짝 없는 계약 연결 정리 |
| `ga-job-collection-rate-snapshot` | COLLECTION_RATE_SNAPSHOT | 매월 1일 06:00 | 징구율 스냅샷(정의는 §14 #17 — 미결정) |
| `ga-job-expire` | EXPIRE | 15분마다 | 서명 기한 지난 세션 만료 |
| `ga-job-flag-sla-sweep` | FLAG_SLA_SWEEP | 15분마다 | 플래그 SLA 초과 표시 |
| `ga-job-idempotency-purge` | IDEMPOTENCY_PURGE | 매시 10분 | 만료된 멱등 기록 삭제 |
| `ga-job-notify` | NOTIFY | 매분 | 아웃박스의 서명 링크 통지 발송 |
| `ga-job-destroy-dry-run` | DESTROY_DRY_RUN(잠금은 DESTROY와 같다) | `suspend: true` | 런북에서 `kubectl create job --from=cronjob/…`로만 |
| `ga-job-retention-recompute` | RETENTION_RECOMPUTE | `suspend: true` | 룰 보존기간 변경 뒤 수동 |
| `ga-job-contract-link-import` | CONTRACT_LINK_IMPORT | `suspend: true` | 파일 입력 — 수동 |
| `ga-job-kek-rewrap` | KEK_REWRAP | `suspend: true` | KEK 회전 때 수동(`keys.md`) — dry-run 기본, 적용은 `--apply yes` |

주기 없는 종류를 `suspend: true` CronJob으로 두는 이유: 파드 틀(이미지·비밀 마운트·보안 문맥)을 한 곳에 두고, 수동 실행도 같은 틀에서 뜨게 한다
(`kubectl -n ga-disclosure create job <이름> --from=cronjob/<CronJob>` — 인자를 바꿀 때는 `kind.sh`의 `run_job`처럼 틀을 복사해 args만 바꾼다).

## 확인

- `./gradlew :disclosure-app:deployTest` — 린트(모든 오버레이 렌더 → kubeconform strict → 규칙). 주입 기록: CronJob `Allow`(P8-24), 종류 라벨 변경(P8-29).
- kind: `deploy/scripts/kind.sh verify <클러스터>`가 VERIFY_TENANT CronJob의 틀로 한 번 돌린다.
