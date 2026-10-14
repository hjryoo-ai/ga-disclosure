# 테넌트 온보딩 런북 (Phase 8)

> 학습·포트폴리오 저장소의 운영 문서다. 값(보존기간·번호 체계 등)은 예시이고, 이 문서는 절차의 **순서**와 **틀렸을 때의 증상**을 정한다.

## 순서 — KEK가 먼저, 테넌트가 나중

테넌트의 데이터 키(DEK)는 그 테넌트의 KEK(`kek/<T>/<T>-KEK-<n>`)로 감싸진다(설계서 §9, V21 `tenant_kek`). KEK 바이트는 DB에 없고 비밀 출처
(`ga.secrets.dir` — 쿠버네티스는 Secret 볼륨)에만 있으므로, **KEK가 모든 파드에 마운트된 뒤에** 테넌트를 만든다.

1. **KEK 프로비저닝** — 비밀 저장소에 `kek/<T>/<T>-KEK-1`(32바이트 키)을 만든다. 운영은 비밀 저장소에서(ESO `ExternalSecret ga-secrets`에 한 줄),
   로컬·kind는 `crypto kek init --tenant <T> --kek-id <T>-KEK-1 --secrets-dir <dir>`.
2. **마운트** — `ga-secrets` 볼륨의 `items`에 `kek.<T>.<T>-KEK-1 → kek/<T>/<T>-KEK-1` 한 줄(웹 Deployment·작업 CronJob 패치 둘 다 — 오버레이의
   `secret-items-*.yaml`). 적용하면 웹 파드가 롤링된다. CronJob은 다음 실행부터.
3. **확인** — 새 파드가 준비된 뒤(`kubectl rollout status deployment/ga-app`) 다음 단계.
4. **테넌트 생성·KEK 등록** — `demo seed`/테넌트 생성, `crypto kek register --tenant <T> --kek-id <T>-KEK-1 --operator <id>`(키가 비밀 출처에서 감싸기·풀기 되는지 확인한 뒤 레지스트리에 CURRENT로 — 마운트 전이면 여기서 실패한다).
5. **룰·카탈로그·고객** — `rules distribute` → `rules activate` → `catalog import` → `customer import`(이 단계부터 DEK를 감싼다).
6. **검증** — `verify tenant --tenants <T>`가 MATCH.

## 순서가 틀렸을 때의 증상

- **KEK가 마운트되기 전에 테넌트를 만들고 데이터를 넣은 경우**(비밀 파일은 운영자 기계에만 있고 Secret에는 없다): 그 테넌트를 다루는 파드의 첫 작업이
  `SecretMissingException: secret kek/<T>/<T>-KEK-1 is not available`로 실패한다(키 이름만 — 값·경로 없음). `verify tenant --tenants all`은 그 테넌트에서
  실패해 **전체 명령이 실패**하고, 다른 테넌트의 MATCH 줄은 이미 출력돼 있다(kind 실측 — Phase 8 10d, DEMO3: seed.sh가 KEK를 클러스터 Secret보다 늦게
  만들었다).
- 웹 요청은 그 테넌트에서만 500(`INTERNAL_ERROR`)이고 다른 테넌트는 영향이 없다.
- **조치**: 1·2단계를 마저 하고(같은 KEK 바이트 — 새로 만들면 이미 감싼 DEK를 풀 수 없다) 롤링 뒤 `verify tenant --tenants <T>`. 키를 잃었으면 그
  테넌트의 감싼 데이터는 복구할 수 없다(백업에도 KEK는 없다 — 비밀 저장소의 백업이 따로 있어야 한다).

## 하지 말 것

- KEK 파일을 다시 만들기(`crypto kek init`은 있으면 거부한다 — `--if-absent yes`는 "있으면 그대로"이지 덮어쓰기가 아니다).
- 테넌트 생성 뒤 KEK ID를 바꾸기 — 회전은 `crypto kek register`(새 ID) + `KEK_REWRAP`(키 회전 런북 `keys.md`).
