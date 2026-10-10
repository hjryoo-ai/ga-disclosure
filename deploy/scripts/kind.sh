#!/usr/bin/env bash
# kind 데모(Phase 8 ③, G4): 로컬 쿠버네티스 클러스터에 kind-demo 오버레이를 올리고 스모크·로그 스캔을 돈다. 도구는 ./gradlew deployTools가 받은
# build/tools의 고정판(tools.lock — 전역 설치 없음), 노드 이미지는 tools.lock의 digest.
#
#   deploy/scripts/kind.sh up      <cluster>   클러스터(노드 이미지 digest, 호스트 127.0.0.1:18443 → 공개 진입점) + 이미지 적재(appImage·webImage)
#   deploy/scripts/kind.sh secrets <cluster>   비밀 생성(저장소 밖 ~/.ga-disclosure/kind/<cluster>, 소유자 전용) → kubectl Secret
#   deploy/scripts/kind.sh deploy  <cluster>   네임스페이스·CRD → 비밀 → 렌더 적용 → 마이그레이션 Job 완료 → 롤아웃 대기
#   deploy/scripts/kind.sh smoke   <cluster>   인그레스·mTLS·한도·NetworkPolicy 단언(실패하면 종료 1)
#   deploy/scripts/kind.sh logscan <cluster>   모든 파드·Job 로그를 모아 비밀 값·허구 개인정보 센티널 스캔(걸리면 종료 1)
#   deploy/scripts/kind.sh seed    <cluster>   데모 시드(disclosure-demo/scripts/seed.sh를 포트 포워드로 — 클러스터의 DB·저장소·비밀) → 클러스터 안 VERIFY_TENANT
#   deploy/scripts/kind.sh verify  <cluster>   클러스터 안 VERIFY_TENANT 작업(CronJob에서 한 번) — 모든 테넌트 MATCH가 아니면 종료 1
#   deploy/scripts/kind.sh backup  <cluster>   표 내용 해시를 저장한 뒤 ga-backup CronJob을 한 번 → 실행 이름 출력
#   deploy/scripts/kind.sh restore <cluster> <실행>  DB 볼륨을 지우고 kind-restore 오버레이로 복구 → 표 해시 = 백업 때, VERIFY_TENANT MATCH
#   deploy/scripts/kind.sh rotate  <cluster> [cursor|request-hash|receipt|demo-oidc|kek|tsa|all]   키 회전(docs/operations/keys.md) — 키마다
#                                  영향 단언(회전 전에 만든 것이 회전 뒤 어떻게 되는지) + VERIFY_TENANT MATCH. 기본 all(여섯 가지를 이 순서로 한 번씩)
#   deploy/scripts/kind.sh e2e-prep <cluster> <dir>  클러스터 대상 E2E 준비(disclosure-web e2e — GA_E2E_KIND): 6A 주체, DEMO2 CHAIN_BROKEN 사건,
#                                  스케줄러·준법 토큰 파일(<dir>, 소유자 전용)
#   deploy/scripts/kind.sh down    <cluster>   클러스터와 그 비밀 디렉터리 삭제(이 스크립트가 만든 것만)
#
# 값은 출력하지 않는다. 비밀 값은 명령줄·환경변수에 싣지 않는다 — 파일(소유자 전용)과 --from-file·--from-env-file로만 넘긴다.
set -euo pipefail
cd "$(dirname "$0")/../.."

CMD="${1:?usage: kind.sh up|secrets|deploy|smoke|logscan|seed|verify|backup|restore|rotate|e2e-prep|down <cluster> [run|key|dir]}"
CLUSTER="${2:?cluster name (e.g. ga-p8-$(date +%s))}"
[[ "$CLUSTER" =~ ^ga-[a-z0-9-]{1,40}$ ]] || { echo "cluster name must match ga-[a-z0-9-]+" >&2; exit 2; }

TOOLS="build/tools"
KIND="$TOOLS/kind"
KUBECTL="$TOOLS/kubectl --context kind-$CLUSTER"
STATE="$HOME/.ga-disclosure/kind/$CLUSTER"
HOST_PORT=18443
ROT="$STATE/rotate"
NS=ga-disclosure

lock() { awk -v n="$1" '$1 == n { print $4 }' deploy/tools.lock; }
render() { $TOOLS/kubectl kustomize --load-restrictor LoadRestrictionsNone "deploy/overlays/${1:-kind-demo}"; }
# Gradle은 umask 022 하위 셸에서 — 이 스크립트의 비밀 단계는 umask 077이고, 그 안에서 뜬 Gradle 데몬은 umask를 물려받아 이후 모든 빌드의 산출물을 0600으로
# 만든다(시험 하네스가 컨테이너에 복사한 설정 파일을 컨테이너가 못 읽었다 — 11단계)
gradle() { (umask 022; ./gradlew -q "$@"); }
java_bin() { gradle :disclosure-app:demoJavaLauncher; }
app_jar() { ls disclosure-app/build/libs/disclosure-app-*.jar | grep -v -- '-plain.jar' | head -n 1; }

up() {
  [ -x "$KIND" ] || gradle :disclosure-app:deployTools
  local config="$STATE/kind-config.yaml"
  mkdir -p "$STATE" && chmod 700 "$STATE"
  cat > "$config" <<EOF
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
    image: $(lock kindest-node)
    extraPortMappings:
      - { containerPort: 30443, hostPort: $HOST_PORT, listenAddress: 127.0.0.1, protocol: TCP }
EOF
  "$KIND" create cluster --name "$CLUSTER" --config "$config" --wait 120s
  gradle :disclosure-app:appImage :disclosure-app:webImage
  "$KIND" load docker-image --name "$CLUSTER" ga-disclosure/app:dev ga-disclosure/web:dev
}

# 스텁 TSA 키 저장소의 인증서(공개 — PEM). 저장소 비밀번호는 비밀이 아닌 고정값(LocalStubTsa)이고 파일로만 keytool에 준다
stub_cert() {
  local pass="$STATE/stub-p12.pass"
  printf 'ga-disclosure-stub-not-a-secret' > "$pass"
  "$(dirname "$JAVA")/keytool" -exportcert -rfc -alias tsa -storetype PKCS12 -keystore "$1" -storepass:file "$pass" 2>/dev/null
}

# 무작위 비밀번호(16진 32자) — 파일로만. (tr < /dev/urandom | head는 pipefail에서 SIGPIPE로 실패한다)
password() { openssl rand -hex 16 | tr -d '\n'; }

secrets() {
  umask 077
  mkdir -p "$STATE"/{secrets,db,pki,s3}
  chmod 700 "$STATE"
  local JAVA JAR
  gradle :disclosure-app:bootJar
  JAVA="$(java_bin)"; JAR="$(app_jar)"
  # 키 재료(앱의 오프라인 명령 — 없는 것만 만든다): API 키·백업 키·데모 OIDC 서명 키·스텁 TSA 키 저장소, 데모 테넌트의 첫 KEK
  "$JAVA" -jar "$JAR" --spring.profiles.active=cli secrets init --secrets-dir "$STATE/secrets" --demo yes
  # TSA 신뢰 앵커 집합(운영 http 모드와 같은 비밀 이름 tsa/trust-anchors.pem — 회전은 덧붙이기): 처음엔 스텁 인증서 하나. 클러스터 안 verify가 이 집합을 쓴다
  if [ ! -f "$STATE/secrets/tsa/trust-anchors.pem" ]; then
    mkdir -p "$STATE/secrets/tsa"
    stub_cert "$STATE/secrets/demo/tsa-stub.p12" > "$STATE/secrets/tsa/trust-anchors.pem"
  fi
  # 데모 테넌트 셋(DEMO3 = 짧은 보존 — seed.sh가 등록한다)의 첫 KEK. 클러스터 Secret보다 늦게 생긴 키는 파드가 읽지 못한다 — 여기서 미리 만든다.
  for t in DEMO1 DEMO2 DEMO3; do
    "$JAVA" -jar "$JAR" --spring.profiles.active=cli crypto kek init --tenant "$t" --kek-id "$t-KEK-1" --secrets-dir "$STATE/secrets" --if-absent yes
  done
  # DB 롤 비밀번호(파일 하나에 하나)
  for r in superuser migrator app operator job-lock health backup; do
    [ -f "$STATE/db/$r-password" ] || password > "$STATE/db/$r-password"
  done
  {
    echo "DISCLOSURE_APP_USER=disclosure_app";           echo "DISCLOSURE_APP_PASSWORD=$(cat "$STATE/db/app-password")"
    echo "DISCLOSURE_HEALTH_USER=disclosure_health";     echo "DISCLOSURE_HEALTH_PASSWORD=$(cat "$STATE/db/health-password")"
    echo "DISCLOSURE_OPERATOR_USER=disclosure_operator"; echo "DISCLOSURE_OPERATOR_PASSWORD=$(cat "$STATE/db/operator-password")"
    echo "DISCLOSURE_JOB_LOCK_USER=disclosure_job_lock"; echo "DISCLOSURE_JOB_LOCK_PASSWORD=$(cat "$STATE/db/job-lock-password")"
  } > "$STATE/db/app.env"
  { echo "DISCLOSURE_MIGRATOR_USER=disclosure_migrator"; echo "DISCLOSURE_MIGRATOR_PASSWORD=$(cat "$STATE/db/migrator-password")"; } > "$STATE/db/migrator.env"
  # S3(SeaweedFS 인증 설정과 앱의 키가 같은 값)
  [ -f "$STATE/s3/access" ] || password > "$STATE/s3/access"
  [ -f "$STATE/s3/secret" ] || password > "$STATE/s3/secret"
  { echo "GA_S3_ACCESS_KEY_ID=$(cat "$STATE/s3/access")"; echo "GA_S3_SECRET_ACCESS_KEY=$(cat "$STATE/s3/secret")"; } > "$STATE/s3/app.env"
  { echo "GA_BACKUP_S3_ACCESS_KEY_ID=$(cat "$STATE/s3/access")"; echo "GA_BACKUP_S3_SECRET_ACCESS_KEY=$(cat "$STATE/s3/secret")"; } > "$STATE/s3/backup.env"
  echo "PGPASSWORD=$(cat "$STATE/db/backup-password")" > "$STATE/db/backup.env"
  printf '{"identities":[{"name":"ga-app","credentials":[{"accessKey":"%s","secretKey":"%s"}],"actions":["Admin","Read","List","Tagging","Write"]}]}' \
    "$(cat "$STATE/s3/access")" "$(cat "$STATE/s3/secret")" > "$STATE/s3/s3.json"
  # 데모 OIDC 공개키(비밀 아님 — 서명 키에서 유도)
  openssl pkey -in "$STATE/secrets/demo/oidc-signing" -pubout -out "$STATE/demo-oidc.pem" 2>/dev/null
  pki

  # 클러스터로(값은 파일에서만). 이미 있으면 바꾼다(같은 값 — 재실행 멱등)
  local apply="$KUBECTL apply -f -"
  push_secrets
  $KUBECTL -n $NS create secret generic ga-pg \
    --from-file=superuser-password="$STATE/db/superuser-password" --from-file=migrator-password="$STATE/db/migrator-password" \
    --from-file=app-password="$STATE/db/app-password" --from-file=operator-password="$STATE/db/operator-password" \
    --from-file=job-lock-password="$STATE/db/job-lock-password" --from-file=health-password="$STATE/db/health-password" \
    --from-file=backup-password="$STATE/db/backup-password" --dry-run=client -o yaml | $apply
  $KUBECTL -n $NS create secret generic ga-db-app --from-env-file="$STATE/db/app.env" --dry-run=client -o yaml | $apply
  $KUBECTL -n $NS create secret generic ga-db-migrator --from-env-file="$STATE/db/migrator.env" --dry-run=client -o yaml | $apply
  $KUBECTL -n $NS create secret generic ga-s3 --from-env-file="$STATE/s3/app.env" --dry-run=client -o yaml | $apply
  $KUBECTL -n $NS create secret generic ga-seaweed --from-file=s3.json="$STATE/s3/s3.json" --dry-run=client -o yaml | $apply
  $KUBECTL -n $NS create secret generic ga-backup-s3 --from-env-file="$STATE/s3/backup.env" --dry-run=client -o yaml | $apply
  $KUBECTL -n $NS create secret generic ga-db-backup --from-env-file="$STATE/db/backup.env" --dry-run=client -o yaml | $apply
  $KUBECTL -n $NS create configmap ga-demo-oidc-public --from-file=demo-oidc.pem="$STATE/demo-oidc.pem" --dry-run=client -o yaml | $apply
  $KUBECTL -n ga-ingress create secret tls ga-tls-staff --cert="$STATE/pki/staff.crt" --key="$STATE/pki/staff.key" --dry-run=client -o yaml | $apply
  $KUBECTL -n ga-ingress create secret tls ga-tls-sign --cert="$STATE/pki/sign.crt" --key="$STATE/pki/sign.key" --dry-run=client -o yaml | $apply
  $KUBECTL -n ga-ingress-internal create secret tls ga-tls-internal --cert="$STATE/pki/internal.crt" --key="$STATE/pki/internal.key" --dry-run=client -o yaml | $apply
  $KUBECTL -n ga-ingress-internal create secret generic ga-internal-client-ca --from-file=ca.crt="$STATE/pki/client-ca.crt" --dry-run=client -o yaml | $apply
  echo "KIND_SECRETS created in $NS, ga-ingress, ga-ingress-internal (values not shown)"
}

# 비밀 디렉터리 전체 → Secret ga-secrets(키 = 비밀 이름의 '/'를 '.'으로). 회전도 이 함수로 다시 올린다
push_secrets() {
  local secret_args=() f name
  while IFS= read -r f; do
    name="${f#"$STATE/secrets/"}"
    secret_args+=("--from-file=${name//\//.}=$f")
  done < <(find "$STATE/secrets" -type f | sort)
  $KUBECTL -n $NS create secret generic ga-secrets "${secret_args[@]}" --dry-run=client -o yaml | $KUBECTL apply -f - >/dev/null
}

# 데모 PKI: 서버 CA → 진입점 인증서 셋, 클라이언트 CA → 내부 호출자 인증서 하나(스모크). CA는 v3 확장(basicConstraints CA:TRUE)을 명시한다 — 설정 없는
# LibreSSL req -x509는 v1 인증서를 만들고, Traefik(Go)은 v1을 클라이언트 CA로 받지 않는다(unknown ca — kind에서 확인). 서명은 -sha256을 명시한다 —
# LibreSSL x509 -req의 기본은 SHA-1이고 Go는 SHA-1 서명 인증서를 거부한다(같은 unknown ca).
pki() {
  local d="$STATE/pki"
  [ -f "$d/server-ca.crt" ] && return 0
  openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -pkeyopt ec_param_enc:named_curve -nodes -days 30 -subj "/CN=ga kind demo server CA" \
    -addext basicConstraints=critical,CA:TRUE -addext keyUsage=critical,keyCertSign,cRLSign \
    -keyout "$d/server-ca.key" -out "$d/server-ca.crt" 2>/dev/null
  openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -pkeyopt ec_param_enc:named_curve -nodes -days 30 -subj "/CN=ga kind demo client CA" \
    -addext basicConstraints=critical,CA:TRUE -addext keyUsage=critical,keyCertSign,cRLSign \
    -keyout "$d/client-ca.key" -out "$d/client-ca.crt" 2>/dev/null
  for pair in staff:staff.ga.example.invalid sign:sign.ga.example.invalid \
              internal:traefik-internal.ga-ingress-internal.svc,traefik-internal.ga-ingress-internal.svc.cluster.local; do
    local name="${pair%%:*}" hosts="${pair#*:}" san=""
    IFS=, read -ra hs <<< "$hosts"; for h in "${hs[@]}"; do san="${san:+$san,}DNS:$h"; done
    openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -pkeyopt ec_param_enc:named_curve -nodes -subj "/CN=${hs[0]}" -keyout "$d/$name.key" -out "$d/$name.csr" 2>/dev/null
    openssl x509 -req -sha256 -in "$d/$name.csr" -CA "$d/server-ca.crt" -CAkey "$d/server-ca.key" -CAcreateserial -days 30 \
      -extfile <(printf 'subjectAltName=%s\nextendedKeyUsage=serverAuth' "$san") -out "$d/$name.crt" 2>/dev/null
  done
  openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -pkeyopt ec_param_enc:named_curve -nodes -subj "/CN=demo-feed" -keyout "$d/client.key" -out "$d/client.csr" 2>/dev/null
  openssl x509 -req -sha256 -in "$d/client.csr" -CA "$d/client-ca.crt" -CAkey "$d/client-ca.key" -CAcreateserial -days 30 \
    -extfile <(printf 'extendedKeyUsage=clientAuth') -out "$d/client.crt" 2>/dev/null
}

deploy() {
  local out="$STATE/rendered.yaml"
  render > "$out"
  # 1) 네임스페이스(파드 보안 라벨은 3에서 같은 적용이 먼저 쓴다 — 렌더 순서가 Namespace 먼저)·CRD 먼저(비밀이 들어갈 자리, IngressRoute 등이 알아볼 종류)
  for ns in $NS ga-ingress ga-ingress-internal; do
    $KUBECTL create namespace "$ns" --dry-run=client -o yaml | $KUBECTL apply -f - >/dev/null
  done
  $KUBECTL apply --server-side -f deploy/components/ingress/traefik/kubernetes-crd-definition-v1.yml >/dev/null
  $KUBECTL wait --for=condition=Established crd --all --timeout=60s >/dev/null
  # 2) 비밀
  secrets
  # 3) 나머지. 끝난 마이그레이션 Job은 지우고 다시 만든다 — Job의 파드 틀은 바꿀 수 없고(설정 ConfigMap 이름에 내용 해시가 붙는다) db migrate는 멱등이다
  $KUBECTL -n $NS delete job ga-db-migrate --ignore-not-found >/dev/null
  $KUBECTL apply --server-side -f "$out" >/dev/null
  $KUBECTL -n $NS rollout status statefulset/ga-postgres --timeout=300s
  $KUBECTL -n $NS rollout status statefulset/ga-seaweedfs --timeout=300s
  $KUBECTL -n $NS wait --for=condition=complete job/ga-db-migrate --timeout=300s
  # 성공한 시도의 출력(앞선 시도는 DB 초기화 전 연결 실패일 수 있다 — Job 재시도)
  $KUBECTL -n $NS logs "$($KUBECTL -n $NS get pods -l job-name=ga-db-migrate --field-selector=status.phase==Succeeded -o name | head -n 1)" | grep '^DB_MIGRATE '
  for d in "$NS/ga-app" "$NS/ga-web" ga-ingress/traefik-public ga-ingress-internal/traefik-internal; do
    $KUBECTL -n "${d%%/*}" rollout status "deployment/${d#*/}" --timeout=600s
  done
  mount_keks                                                               # 회전으로 더한 KEK 줄(렌더에는 첫 KEK만)
}

# 공개 진입점에 보내는 요청(이름 해석은 --resolve — DNS 없이 kind 노드 포트로)
pub() {
  local host="$1"; shift
  curl -sS --cacert "$STATE/pki/server-ca.crt" --resolve "$host:$HOST_PORT:127.0.0.1" "$@"
}

# 클러스터 안 시험 파드(app 이미지의 busybox wget) — 출발지 네임스페이스·라벨을 바꿔 NetworkPolicy를 본다
probe() {
  local ns="$1" label="$2" url="$3" name="probe-$RANDOM"
  $KUBECTL -n "$ns" run "$name" --image=ga-disclosure/app:dev --image-pull-policy=Never --restart=Never --labels="app.kubernetes.io/name=$label" \
    --overrides='{"spec":{"securityContext":{"runAsNonRoot":true,"runAsUser":10001,"seccompProfile":{"type":"RuntimeDefault"}},"containers":[{"name":"p","image":"ga-disclosure/app:dev","imagePullPolicy":"Never","command":["sh","-c","wget -T 4 -S -O /dev/null '"$url"' 2>&1 | grep -E \"HTTP/|timed out|refused|error\" | head -n 1"],"securityContext":{"allowPrivilegeEscalation":false,"readOnlyRootFilesystem":true,"capabilities":{"drop":["ALL"]}}}]}}' \
    >/dev/null
  $KUBECTL -n "$ns" wait --for=jsonpath='{.status.phase}'=Succeeded "pod/$name" --timeout=60s >/dev/null 2>&1 || true
  $KUBECTL -n "$ns" logs "$name" 2>/dev/null || true
  $KUBECTL -n "$ns" delete pod "$name" --wait=false >/dev/null 2>&1 || true
}

# 포트 포워드가 받기 시작할 때까지(최대 20초) — 고정 sleep은 경합했다(스모크의 C 검사가 간헐 000)
wait_port() {
  for _ in $(seq 1 40); do
    (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null && return 0
    sleep 0.5
  done
  echo "port $1 is not listening" >&2
  return 1
}

# 내부 진입점 C에 포트 포워드 하나를 열고 명령 하나를 돌린 뒤 닫는다
internal() {
  $KUBECTL -n ga-ingress-internal port-forward svc/traefik-internal 19443:9443 >/dev/null 2>&1 &
  local pf=$!
  wait_port 19443 || { kill "$pf" 2>/dev/null; echo "no-port-forward"; return 0; }
  "$@" || true
  kill "$pf" 2>/dev/null || true
  wait "$pf" 2>/dev/null || true
}

# curl의 종료 코드만(응답 코드 아님) — TLS 실패(35·56)와 연결 실패(7)를 가른다
curl_exit() { curl -s -o /dev/null "$@"; echo $?; }

FAILS=0
check() {   # check <설명> <기대 정규식> <실제>
  if [[ "$3" =~ $2 ]]; then echo "PASS $1 ($3)"; else echo "FAIL $1: expected /$2/, got '$3'"; FAILS=$((FAILS + 1)); fi
}

smoke() {
  local staff=staff.ga.example.invalid sign=sign.ga.example.invalid code
  # NetworkPolicy 집행 전제(공회전 방지): 같은 클러스터 안이라도 내부 진입점 라벨이 아닌 파드는 8081에 닿지 못해야 한다 — 이것이 먼저 거부돼야 아래
  # "내부 진입점 파드만 닿는다"가 의미가 있다(kindnet이 정책을 집행하지 않으면 여기서 실패한다)
  check "netpol: public ingress pod -> app 8081 blocked" "timed out|error" "$(probe ga-ingress traefik-public http://ga-app-internal.ga-disclosure.svc:8081/internal/v1/jobs)"
  check "netpol: other pod in app namespace -> app 8081 blocked" "timed out|error" "$(probe $NS smoke-probe http://ga-app-internal.ga-disclosure.svc:8081/internal/v1/jobs)"
  check "netpol: internal ingress pod -> app 8081 reaches the app (401 without a token)" "HTTP/1.1 401" "$(probe ga-ingress-internal traefik-internal http://ga-app-internal.ga-disclosure.svc:8081/internal/v1/jobs)"
  check "netpol: public ingress pod -> app 8080 reaches the app" "HTTP/1.1 (401|404)" "$(probe ga-ingress traefik-public http://ga-app.ga-disclosure.svc:8080/api/v1/disclosures)"

  # 공개 진입점 A·B
  check "A: staff screen" "^200$" "$(pub $staff -o /dev/null -w '%{http_code}' https://$staff:$HOST_PORT/staff)"
  check "A: /api without a token is the app's 401" "^401$" "$(pub $staff -o /dev/null -w '%{http_code}' https://$staff:$HOST_PORT/api/v1/disclosures)"
  check "A: /internal from outside is 404" "^404$" "$(pub $staff -o /dev/null -w '%{http_code}' https://$staff:$HOST_PORT/internal/v1/jobs)"
  check "A: /public on the staff host is 404" "^404$" "$(pub $staff -o /dev/null -w '%{http_code}' -X POST https://$staff:$HOST_PORT/public/v1/sign/open)"
  check "B: /internal on the sign host is 404" "^404$" "$(pub $sign -o /dev/null -w '%{http_code}' https://$sign:$HOST_PORT/internal/v1/jobs)"
  check "B: /api on the sign host is 404" "^404$" "$(pub $sign -o /dev/null -w '%{http_code}' https://$sign:$HOST_PORT/api/v1/disclosures)"
  # 평문: Traefik은 TLS 진입점에서도 평문 연결을 받지만 라우터가 전부 TLS라 아무 데도 닿지 않는다 — Traefik 자신의 404(앱·웹의 응답이 아님)
  check "plain HTTP reaches no route (Traefik's own 404)" "^404 404 page not found$" \
    "$(curl -s -w '%{http_code} ' --resolve "$staff:$HOST_PORT:127.0.0.1" http://$staff:$HOST_PORT/staff -o "$STATE/plain.txt" || true)$(cat "$STATE/plain.txt" 2>/dev/null)"
  # 크기 한도(1 MiB) — 앱에 닿기 전 413
  head -c 1100000 /dev/zero > "$STATE/oversize.bin"
  check "B: oversize /public body is 413" "^413$" "$(pub $sign -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' --data-binary @"$STATE/oversize.bin" https://$sign:$HOST_PORT/public/v1/sign/open)"
  # IP 단위 한도(분당 30, 버스트 30): 한도는 Traefik 복제본마다 메모리에 있다 — 실효 한도 = 30 × 공개 컨트롤러 복제본 수(앱의 공개 서명 테넌트 한도와 같은
  # 성질, 운영 문서). 동시에 보낸다(앱의 공개 응답 하한 250ms 동안 버킷이 다시 차지 않게). 일부는 앱에 닿고(앱의 균일 거부), 실효 한도를 넘기면 429.
  local replicas n codes
  replicas="$($KUBECTL -n ga-ingress get deployment traefik-public -o jsonpath='{.spec.replicas}')"
  n=$((30 * replicas + 30))
  codes="$(seq 1 "$n" | xargs -P 24 -I{} curl -sS -o /dev/null -w '%{http_code}\n' --cacert "$STATE/pki/server-ca.crt" --resolve "$sign:$HOST_PORT:127.0.0.1" \
    -X POST -H 'Content-Type: application/json' --data '{}' "https://$sign:$HOST_PORT/public/v1/sign/open" | sort | uniq -c | awk '{printf "%s×%s ", $2, $1}')"
  check "B: /public requests reach the app until the per-IP limit ($n concurrent, limit 30 x $replicas)" "^404×[0-9]+ 429×[0-9]+ $" "$codes"

  # 내부 진입점 C(클러스터 안에서만): 인증서 없이 → TLS 실패, 인증서와 함께 → 앱의 401
  # 검사마다 새 포트 포워드 — kubectl port-forward는 거부된 TLS 연결의 리셋 뒤에 끝날 수 있다(그 다음 검사가 연결 실패 7을 받았다)
  local cacert="$STATE/pki/server-ca.crt" host=traefik-internal.ga-ingress-internal.svc
  # 연결은 되고(포트 포워드 준비) TLS에서 실패해야 한다 — curl 35(핸드셰이크)·56(받기 중 경고). 7(연결 실패)이면 검사가 헛돈 것
  check "C: without a client certificate the TLS handshake fails" "^(35|56)$" \
    "$(internal curl_exit --cacert "$cacert" --resolve "$host:19443:127.0.0.1" https://$host:19443/internal/v1/jobs)"
  check "C: a different SNI still needs a client certificate" "^(35|56)$" \
    "$(internal curl_exit -k --resolve "other.invalid:19443:127.0.0.1" https://other.invalid:19443/internal/v1/jobs)"
  check "C: with the client certificate the request reaches the app (401 without a token)" "^401$" \
    "$(internal curl -s -o /dev/null -w '%{http_code}' --cacert "$cacert" --cert "$STATE/pki/client.crt" --key "$STATE/pki/client.key" \
      --resolve "$host:19443:127.0.0.1" https://$host:19443/internal/v1/jobs)"
  echo "SMOKE failures=$FAILS"
  [ "$FAILS" -eq 0 ]
}

# 모든 파드(지금·이전 컨테이너)·Job 로그 → 센티널 스캔. 바늘: 비밀 디렉터리의 텍스트 값(base64 키·PEM 본문 줄·비밀번호)과 데모 고객 이름·전화(허구).
logscan() {
  umask 077
  local logs="$STATE/logs" needles="$STATE/needles.txt"
  rm -rf "$logs"; mkdir -p "$logs"
  for ns in $NS ga-ingress ga-ingress-internal; do
    for p in $($KUBECTL -n "$ns" get pods -o name); do
      $KUBECTL -n "$ns" logs "$p" --all-containers --prefix > "$logs/${ns}_${p#pod/}.log" 2>/dev/null || true
      $KUBECTL -n "$ns" logs "$p" --all-containers --prefix --previous >> "$logs/${ns}_${p#pod/}.log" 2>/dev/null || true
    done
  done
  : > "$needles"
  # 회전한 옛 키(rotate/retired)와 회전 단언이 쓴 토큰도 바늘이다
  local dirs=("$STATE/secrets" "$STATE/db" "$STATE/s3")
  [ -d "$ROT/retired" ] && dirs+=("$ROT/retired")
  find "${dirs[@]}" -type f ! -name '*.env' ! -name '*.json' -print0 | while IFS= read -r -d '' f; do
    if LC_ALL=C grep -q '[^[:print:][:space:]]' "$f"; then continue; fi        # 이진(키 저장소) — 텍스트 로그에 원형으로 실리지 않는다
    grep -v -- '-----' "$f" | awk 'length($0) >= 16' >> "$needles"
  done
  [ -f "$ROT/agent.hdr" ] && sed 's/^Authorization: Bearer //' "$ROT/agent.hdr" >> "$needles"
  jq -r '.customers[] | .name, (.phone // empty)' disclosure-demo/src/main/resources/customers.json >> "$needles"
  local files hits
  files=$(find "$logs" -type f | wc -l | tr -d ' ')
  hits=$(grep -rlF -f "$needles" "$logs" | wc -l | tr -d ' ' || true)
  echo "LOGSCAN files=$files needles=$(wc -l < "$needles" | tr -d ' ') hits=$hits"
  [ "$files" -gt 0 ] && [ "$hits" -eq 0 ]
}

# 표마다 행 수·내용 해시(슈퍼유저 — DB 파드 안 psql, 소켓 인증). 복구 전후 대조용(값이 아니라 해시만).
digests() {
  $KUBECTL -n $NS exec ga-postgres-0 -- psql -U postgres -d disclosure -At -c "SELECT table_name || ' ' || (xpath('/row/c/text()', query_to_xml(format(
    'SELECT count(*) || '':'' || coalesce(md5(string_agg(x::text, ''|'' ORDER BY x::text)), ''-'') AS c FROM %I x', table_name), false, true, '')))[1]::text
    FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE' ORDER BY table_name"
}

# CronJob의 파드 틀로 Job 하나를 만들어(인자를 바꿀 수 있다 — JSON 배열) 성공·실패까지 기다리고 로그를 낸다(실패하면 종료 1)
run_job() {
  local cron="$1" args="${2:-}" name="$1-$(date +%s)" phase=""
  if [ -z "$args" ]; then
    $KUBECTL -n $NS create job "$name" --from="cronjob/$cron" >/dev/null
  else
    $KUBECTL -n $NS get cronjob "$cron" -o json | jq --arg n "$name" --argjson a "$args" \
      '{apiVersion: "batch/v1", kind: "Job", metadata: {name: $n, namespace: .metadata.namespace, labels: .spec.jobTemplate.metadata.labels},
        spec: (.spec.jobTemplate.spec | .template.spec.containers[0].args = $a)}' | $KUBECTL create -f - >/dev/null
  fi
  for _ in $(seq 1 180); do
    phase="$($KUBECTL -n $NS get job "$name" -o jsonpath='{.status.succeeded}/{.status.failed}')"
    case "$phase" in 1/*) break ;; */[1-9]*) break ;; esac
    sleep 5
  done
  $KUBECTL -n $NS logs "job/$name" --all-containers 2>/dev/null
  [[ "$phase" == 1/* ]] || { echo "job $name did not succeed ($phase)" >&2; return 1; }
}

# 클러스터 안 검증: VERIFY_TENANT CronJob의 파드 틀(같은 이미지·설정·비밀)로 운영자 명령 verify tenant — 테넌트마다 MATCH/MISMATCH를 출력하고 불일치면
# 종료 코드가 0이 아니다(예약 작업 jobs run VERIFY_TENANT는 보고서에만 남긴다). 신뢰 앵커는 비밀 tsa/trust-anchors.pem(집합 — 운영과 같은 이름; 스텁이
# 기동 때 쓰는 /tmp/tsa-trust.pem은 지금 키 하나뿐이라 TSA 회전 뒤 옛 앵커를 검증하지 못한다 — rotate_tsa의 대조).
TRUST_SET=/var/run/ga-secrets/tsa/trust-anchors.pem
verify() {
  local out
  out="$(run_job ga-job-verify-tenant "[\"--spring.profiles.active=cli,\$(GA_PROFILE)\",\"verify\",\"tenant\",\"--tenants\",\"all\",\"--tsa-trust\",\"${VERIFY_TRUST:-$TRUST_SET}\",\"--operator\",\"kind-verify\"]")" \
    || { echo "$out" | grep -E '^VERIFY_TENANT|^  ' >&2; return 1; }
  echo "$out" | grep -E '^VERIFY_TENANT'
  ! echo "$out" | grep -qE '^VERIFY_TENANT .* MISMATCH'
}

# 데모 시드: seed.sh(로컬 JVM — 운영자 CLI)를 클러스터의 DB·S3에 포트 포워드로. 비밀번호·키는 저장소 밖 비밀 디렉터리의 파일에서(이 프로세스의 환경에만 —
# 개인정보 아님), 고객 정보는 seed.sh 그대로 허구 파일로만.
# 클러스터의 DB·S3에 포트 포워드 두 개(로컬 JVM 운영자 CLI·seed.sh가 쓴다). 스크립트가 끝나면 닫는다(EXIT 트랩)
PF_PIDS=()
forwards_up() {
  [ "${#PF_PIDS[@]}" -gt 0 ] && return 0
  $KUBECTL -n $NS port-forward svc/ga-postgres 15432:5432 >/dev/null 2>&1 &
  PF_PIDS+=($!)
  $KUBECTL -n $NS port-forward svc/ga-seaweedfs 18333:8333 >/dev/null 2>&1 &
  PF_PIDS+=($!)
  trap forwards_down EXIT
  wait_port 15432
  wait_port 18333
}
forwards_down() {
  [ "${#PF_PIDS[@]}" -gt 0 ] || return 0
  kill "${PF_PIDS[@]}" 2>/dev/null || true
  wait "${PF_PIDS[@]}" 2>/dev/null || true
  PF_PIDS=()
}

# 명령 하나를 클러스터 DB·S3·비밀 디렉터리를 보는 환경으로(비밀번호·키는 파일에서 이 프로세스의 환경에만 — 개인정보 아님)
with_cluster() {
  forwards_up
  DISCLOSURE_DB_URL=jdbc:postgresql://localhost:15432/disclosure \
  DISCLOSURE_APP_PASSWORD="$(cat "$STATE/db/app-password")" DISCLOSURE_HEALTH_PASSWORD="$(cat "$STATE/db/health-password")" \
  DISCLOSURE_OPERATOR_PASSWORD="$(cat "$STATE/db/operator-password")" DISCLOSURE_JOB_LOCK_PASSWORD="$(cat "$STATE/db/job-lock-password")" \
  DISCLOSURE_MIGRATOR_PASSWORD="$(cat "$STATE/db/migrator-password")" \
  GA_SECRETS_DIR="$STATE/secrets" GA_TSA_STUB_KEY_STORE="$STATE/secrets/demo/tsa-stub.p12" \
  GA_S3_ENDPOINT=http://localhost:18333 GA_S3_BUCKET=ga-disclosure-kind \
  GA_S3_ACCESS_KEY_ID="$(cat "$STATE/s3/access")" GA_S3_SECRET_ACCESS_KEY="$(cat "$STATE/s3/secret")" \
  DEMO_PSQL="$TOOLS/kubectl --context kind-$CLUSTER -n $NS exec -i ga-postgres-0 -- psql -U postgres -d disclosure" \
    "$@"
}

# 슈퍼유저 psql(DB 파드 안, 소켓 인증) — 질의 하나의 값
sql() { $KUBECTL -n $NS exec -i ga-postgres-0 -- psql -U postgres -d disclosure -v ON_ERROR_STOP=1 -qtA -c "$1"; }

seed() {
  with_cluster disclosure-demo/scripts/seed.sh > "$STATE/seed.log" 2>&1 || { tail -n 40 "$STATE/seed.log" >&2; return 1; }
  grep -cE '^(SEED|DEMO_|ANCHOR_RUN|VERIFY_TENANT)' "$STATE/seed.log" | sed 's/^/SEED_LINES /'
  verify
}

backup() {
  umask 077
  digests > "$STATE/digests-before.txt"
  local out run
  out="$(run_job ga-backup)"
  echo "$out" | grep -E '^BACKUP_'
  run="$(echo "$out" | sed -nE 's/^BACKUP_UPLOADED run=([^ ]+) .*/\1/p')"
  [ -n "$run" ] || { echo "no BACKUP_UPLOADED line" >&2; return 1; }
  echo "$run" > "$STATE/last-backup-run"
  echo "BACKUP_RUN $run tables=$(wc -l < "$STATE/digests-before.txt" | tr -d ' ')"
}

# 재해 → 복구: 앱을 멈추고 DB StatefulSet·볼륨을 지운 뒤 kind-restore 오버레이(빈 볼륨에 백업 → 새 버킷에 객체)를 적용한다.
restore() {
  local run="${RUN_ARG:-$(cat "$STATE/last-backup-run")}"
  [ -n "$run" ] || { echo "no backup run" >&2; return 1; }
  $KUBECTL -n $NS scale deployment ga-app --replicas=0 >/dev/null
  $KUBECTL -n $NS delete statefulset ga-postgres --wait=true >/dev/null
  $KUBECTL -n $NS delete pvc data-ga-postgres-0 --wait=true >/dev/null
  # 복구마다 새 산출물 버킷(잠긴 객체는 지울 수 없다 — 이전 시도의 버킷은 남는다)
  local bucket
  bucket="ga-kind-r-$(echo "$run" | tr 'A-Z' 'a-z')-$(date +%s)"
  $KUBECTL -n $NS create configmap ga-restore --from-literal=GA_RESTORE_RUN="$run" --from-literal=GA_S3_BUCKET="$bucket" --dry-run=client -o yaml \
    | $KUBECTL apply -f - >/dev/null
  render kind-restore > "$STATE/rendered-restore.yaml"
  # 마이그레이션 Job도 다시(복구한 스키마가 지금 이미지보다 오래됐으면 올린다 — 같으면 적용 0), 객체 복구 Job은 실행마다 새로
  $KUBECTL -n $NS delete job ga-db-migrate ga-restore-objects --ignore-not-found >/dev/null
  $KUBECTL apply --server-side --force-conflicts -f "$STATE/rendered-restore.yaml" >/dev/null
  $KUBECTL -n $NS rollout status statefulset/ga-postgres --timeout=600s
  $KUBECTL -n $NS logs ga-postgres-0 -c restore | grep -E '^RESTORE_|^BACKUP_'
  $KUBECTL -n $NS wait --for=condition=complete job/ga-restore-objects --timeout=600s >/dev/null
  $KUBECTL -n $NS logs job/ga-restore-objects | grep '^BACKUP_OBJECTS_IMPORTED'
  $KUBECTL -n $NS wait --for=condition=complete job/ga-db-migrate --timeout=600s >/dev/null
  $KUBECTL -n $NS logs "$($KUBECTL -n $NS get pods -l job-name=ga-db-migrate --field-selector=status.phase==Succeeded -o name | head -n 1)" | grep '^DB_MIGRATE '
  mount_keks                                                               # 회전으로 더한 KEK 줄(복구한 DB의 키를 풀려면 그 KEK가 있어야 한다)
  digests > "$STATE/digests-after.txt"
  if diff -q "$STATE/digests-before.txt" "$STATE/digests-after.txt" >/dev/null; then
    echo "RESTORE_TABLES MATCH tables=$(wc -l < "$STATE/digests-after.txt" | tr -d ' ')"
  else
    echo "RESTORE_TABLES DIFFER: $(diff "$STATE/digests-before.txt" "$STATE/digests-after.txt" | grep -c '^[<>]') lines" >&2
    return 1
  fi
  $KUBECTL -n $NS rollout status deployment/ga-app --timeout=600s
  verify
}

# ---------------------------------------------------------------------------------------------- 키 회전(11단계, G5 — docs/operations/keys.md)
# 키마다: 회전 전에 만든 것(커서·멱등 청구·등록 영수증·토큰·감싼 DEK) → 회전 → 영향 단언 → VERIFY_TENANT MATCH. 옛 값은 rotate/retired/로 옮겨 둔다(소유자
# 전용 — 되돌리기·로그 스캔 바늘). 값은 출력하지 않는다. HTTP는 공개 진입점 A(직원 호스트)로, 토큰은 머리 줄 파일(-H @파일)로만 넘긴다.
jar_env() { gradle :disclosure-app:bootJar; JAVA="$(java_bin)"; JAR="$(app_jar)"; }

# 로컬 JVM 운영자 CLI(부트 jar)를 클러스터 환경으로 — 데모 프로파일(데모 토큰 발급)
cluster_cli() {
  with_cluster "$JAVA" -jar "$JAR" --spring.profiles.active=cli,demo --ga.demo.oidc-public-pem="$STATE/demo-oidc.pem" "$@"
}

# 데모 OIDC 토큰을 파일로(소유자 전용, 토큰은 출력·명령줄에 없다): 기본은 머리 줄(curl -H @파일), raw면 토큰만(E2E 하네스가 읽는 형식)
mint() {   # mint <tenant> <subject> <file> [raw] [ttl]
  local jwt
  jwt="$(cluster_cli demo token --tenant "$1" --subject "$2" --ttl "${5:-PT1H}" | grep -E '^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$' | tail -n 1)"
  [ -n "$jwt" ] || { echo "demo token for $1/$2 failed" >&2; return 1; }
  if [ "${4:-}" = raw ]; then printf '%s\n' "$jwt" > "$3"; else printf 'Authorization: Bearer %s\n' "$jwt" > "$3"; fi
}

# 직원 호스트 API 한 번: 상태 코드를 출력하고 본문은 $ROT/body, 머리는 $ROT/headers
api() {   # api <method> <path> <token file> [curl 인자…]
  local method="$1" path="$2" auth="$3"; shift 3
  pub staff.ga.example.invalid -o "$ROT/body" -D "$ROT/headers" -w '%{http_code}' -X "$method" -H @"$auth" "$@" \
    "https://staff.ga.example.invalid:$HOST_PORT$path"
}
header() { awk -v h="$(echo "$1" | tr 'A-Z' 'a-z')" 'BEGIN { FS = ": " } tolower($1) == h { sub(/\r$/, "", $2); print $2 }' "$ROT/headers"; }
idem() { echo "kind-rot-$1-$(openssl rand -hex 8)"; }

# 롤링 재기동 + 무중단 단언: 재기동 동안(옛 파드가 끝날 때까지 — preStop 대기 포함) 직원 호스트 API를 0.2초마다 불러 5xx·연결 실패(000)를 센다.
# 401은 센다에서 뺀다(데모 OIDC 회전 직후의 옛 토큰은 설계된 거부). 첫 실행에서 재기동 직후 502가 나왔다 — preStop·maxUnavailable 0(base/app.yaml).
restart_app() {
  local probe="$ROT/restart-probe.txt" flag="$ROT/restart-probe.run"
  : > "$probe"; echo run > "$flag"
  ( while [ "$(cat "$flag")" = run ]; do
      pub staff.ga.example.invalid -o /dev/null -w '%{http_code}\n' -H @"$ROT/agent.hdr" \
        "https://staff.ga.example.invalid:$HOST_PORT/api/v1/disclosures?limit=1" >> "$probe" 2>/dev/null || echo 000 >> "$probe"
      sleep 0.2
    done ) &
  local poller=$!
  $KUBECTL -n $NS rollout restart deployment/ga-app >/dev/null
  $KUBECTL -n $NS rollout status deployment/ga-app --timeout=600s >/dev/null
  # 옛 파드(종료 중 — preStop 대기·우아한 종료)가 사라질 때까지
  for _ in $(seq 1 120); do
    [ "$($KUBECTL -n $NS get pods -l app.kubernetes.io/name=ga-app --no-headers | grep -c Terminating || true)" = 0 ] && break
    sleep 1
  done
  echo stop > "$flag"; wait "$poller" 2>/dev/null || true
  check "rolling restart: no 5xx or dropped connection while the pods were replaced ($(wc -l < "$probe" | tr -d ' ') requests)" "^0$" \
    "$(grep -cE '^(5[0-9][0-9]|000)$' "$probe" || true)"
}

# 비밀 하나를 새 값으로: 옛 파일을 retired/로 옮기고 secrets init이 빠진 그 이름만 새로 만든다(앱은 비밀을 만들지 않는다) → Secret. 재기동은 부르는 쪽
rotate_file() {   # rotate_file <비밀 이름>
  local name="$1" old
  old="$ROT/retired/$name.$(date +%Y%m%dT%H%M%S)"
  mkdir -p "$(dirname "$old")"
  mv "$STATE/secrets/$name" "$old"
  "$JAVA" -jar "$JAR" --spring.profiles.active=cli secrets init --secrets-dir "$STATE/secrets" --demo yes | grep -F "SECRET $name "
  if [ ! -f "$STATE/secrets/$name" ] || cmp -s "$old" "$STATE/secrets/$name"; then echo "$name was not replaced" >&2; return 1; fi
  push_secrets
}

rotate_cursor() {
  echo "== ROTATE api/cursor"
  check "cursor: list page 1 before (200)" "^200$" "$(api GET '/api/v1/disclosures?limit=1' "$ROT/agent.hdr")"
  local cursor
  cursor="$(jq -r '.next // empty' "$ROT/body")"
  check "cursor: there is a next page to hold on to" "^present$" "$([ -n "$cursor" ] && echo present)"
  rotate_file api/cursor
  restart_app
  check "cursor: the cursor from before the rotation is refused (400 INVALID_CURSOR)" "^400 INVALID_CURSOR$" \
    "$(api GET "/api/v1/disclosures?limit=1&after=$cursor" "$ROT/agent.hdr") $(jq -r .code "$ROT/body")"
  check "cursor: a fresh list works (200)" "^200$" "$(api GET '/api/v1/disclosures?limit=1' "$ROT/agent.hdr")"
}

# 고객 등록(허구 값 — 파일로만). 같은 등록 키(주체 + 멱등 키)는 같은 가명이다
register() {   # register <멱등 키> → 상태 코드, 본문 $ROT/body
  api POST /api/v1/customers "$ROT/agent.hdr" -H 'Content-Type: application/json' -H "Idempotency-Key: $1" --data-binary @"$ROT/customer.json"
}
same() { [ "$(jq -r ".$1" "$ROT/body")" = "$(cat "$2")" ] && echo same || echo other; }

rotate_request_hash() {
  echo "== ROTATE api/request-hash"
  printf '{"name":"가상회전","phone":"010-0000-0%03d","birthDate":"1990-01-01"}' "$((RANDOM % 1000))" > "$ROT/customer.json"   # 허구
  local k1 k2
  k1="$(idem rh)"; echo "$k1" > "$ROT/k1"
  check "request-hash: register before (201)" "^201$" "$(register "$k1")"
  jq -r .customerRef "$ROT/body" > "$ROT/ref"; jq -r .receiptId "$ROT/body" > "$ROT/receipt-1"
  check "request-hash: the same key and body replays before the rotation" "^201 true$" "$(register "$k1") $(header Idempotency-Replayed)"
  rotate_file api/request-hash
  restart_app
  check "request-hash: the same key and body within the window is refused after the rotation (422 IDEMPOTENCY_KEY_REUSED)" \
    "^422 IDEMPOTENCY_KEY_REUSED$" "$(register "$k1") $(jq -r .code "$ROT/body")"
  k2="$(idem rh)"
  check "request-hash: a new key works (201)" "^201$" "$(register "$k2")"
  check "request-hash: and replays (201, Idempotency-Replayed)" "^201 true$" "$(register "$k2") $(header Idempotency-Replayed)"
}

# 멱등 기록 만료를 앞당긴다(데모 클러스터의 슈퍼유저 — 24시간을 기다리지 않고 "만료 뒤"를 본다. 트리거 밖, 이 키 한 줄만)
expire_claim() {
  sql "BEGIN; SET LOCAL session_replication_role = replica; UPDATE idempotency_key
       SET created_at = created_at - interval '2 days', claimed_at = claimed_at - interval '2 days', expires_at = created_at - interval '1 day'
       WHERE tenant_id = 'DEMO1' AND actor_subject = 'demo-agent' AND idem_key = '$1'; COMMIT;" >/dev/null
}

rotate_receipt() {
  echo "== ROTATE api/customer-receipt"
  local k1
  k1="$(cat "$ROT/k1")"
  # 만료 뒤 같은 등록 키 = 다시 파생한 같은 응답(영수증은 결정론적) — 회전 전 기준선
  expire_claim "$k1"
  check "receipt: after expiry the same registration key gives the same pseudonym and receipt (before rotation)" "^201 same same$" \
    "$(register "$k1") $(same customerRef "$ROT/ref") $(same receiptId "$ROT/receipt-1")"
  rotate_file api/customer-receipt
  restart_app
  expire_claim "$k1"
  check "receipt: after the rotation the same registration key keeps the pseudonym but the receipt changes" "^201 same other$" \
    "$(register "$k1") $(same customerRef "$ROT/ref") $(same receiptId "$ROT/receipt-1")"
}

rotate_demo_oidc() {
  echo "== ROTATE demo/oidc-signing"
  check "demo-oidc: the token from before works (200)" "^200$" "$(api GET '/api/v1/disclosures?limit=1' "$ROT/agent.hdr")"
  rotate_file demo/oidc-signing
  # 공개키 재게시(비밀 아님 — 서명 키에서 유도) → 앱 준비 컨테이너가 기동 때 옮긴다
  openssl pkey -in "$STATE/secrets/demo/oidc-signing" -pubout -out "$STATE/demo-oidc.pem" 2>/dev/null
  $KUBECTL -n $NS create configmap ga-demo-oidc-public --from-file=demo-oidc.pem="$STATE/demo-oidc.pem" --dry-run=client -o yaml | $KUBECTL apply -f - >/dev/null
  restart_app
  check "demo-oidc: the token signed with the old key is refused (401)" "^401$" "$(api GET '/api/v1/disclosures?limit=1' "$ROT/agent.hdr")"
  mint DEMO1 demo-agent "$ROT/agent.hdr"
  check "demo-oidc: a new login works (200)" "^200$" "$(api GET '/api/v1/disclosures?limit=1' "$ROT/agent.hdr")"
}

# 테넌트 KEK의 Secret 볼륨 줄: 비밀 디렉터리의 모든 kek/<T>/<ID> 파일이 웹 Deployment와 작업 CronJob의 items에 있게 한다(오버레이는 첫 KEK만 — 회전한
# 키는 여기서 더한다; 운영은 오버레이 커밋). 없는 줄만 더하므로 재실행 멱등.
mount_keks() {
  local f name key target spec idx have
  while IFS= read -r f; do
    name="${f#"$STATE/secrets/"}"; key="${name//\//.}"
    for target in deployment/ga-app $($KUBECTL -n $NS get cronjob -l app.kubernetes.io/name=ga-job -o name); do
      spec=/spec/template/spec
      [[ "$target" == cronjob* ]] && spec=/spec/jobTemplate/spec/template/spec
      read -r idx have < <($KUBECTL -n $NS get "$target" -o json | jq -r --arg p "$spec" --arg k "$key" \
        '($p | ltrimstr("/") | split("/")) as $s | getpath($s).volumes as $v | ($v | map(.name) | index("ga-secrets")) as $i
         | "\($i) \([$v[$i].secret.items[] | select(.key == $k)] | length)"')
      [ "$have" -eq 0 ] || continue
      $KUBECTL -n $NS patch "$target" --type=json \
        -p "[{\"op\":\"add\",\"path\":\"$spec/volumes/$idx/secret/items/-\",\"value\":{\"key\":\"$key\",\"path\":\"$name\"}}]" >/dev/null
    done
  done < <(find "$STATE/secrets/kek" -type f | sort)
  $KUBECTL -n $NS rollout status deployment/ga-app --timeout=600s >/dev/null
}

# 옛 KEK로 감싼 살아 있는 키(document_key·customer_data_key·끝난 작업의 보고서 키 — 재래핑 대상과 같은 조건)
old_kek_rows() {
  sql "SELECT (SELECT count(*) FROM document_key WHERE tenant_id = '$1' AND kek_key_id = '$2' AND wrapped_dek IS NOT NULL)
            + (SELECT count(*) FROM customer_data_key WHERE tenant_id = '$1' AND kek_id = '$2' AND status <> 'DESTROYED' AND wrapped_key IS NOT NULL)
            + (SELECT count(*) FROM async_job WHERE tenant_id = '$1' AND report_kek_id = '$2' AND report_key_wrapped IS NOT NULL)"
}

# 클러스터 안 운영자 명령(KEK_REWRAP CronJob의 파드 틀 — 클러스터 Secret의 KEK로 감싸고 푼다: 마운트가 됐는지가 곧 시험)
# 인자 배열은 jq -R로(jq --args는 뒤의 --tenant를 자기 옵션으로 읽는다). 빈 배열이면 거부 — run_job은 빈 인자면 CronJob 기본 명령을 돈다(첫 실행에서 그렇게 됐다)
kek_job() {
  [ "$#" -gt 0 ] || { echo "kek_job needs a command" >&2; return 1; }
  run_job ga-job-kek-rewrap "$(printf '%s\n' '--spring.profiles.active=cli,$(GA_PROFILE)' "$@" | jq -R . | jq -sc .)"
}

rotate_kek() {
  local t=DEMO1 old new n out before
  echo "== ROTATE kek/$t"
  old="$(sql "SELECT kek_id FROM tenant_kek WHERE tenant_id = '$t' AND status = 'CURRENT'")"
  n="${old##*-KEK-}"; new="$t-KEK-$((n + 1))"
  before="$(old_kek_rows "$t" "$old")"
  check "kek: live keys wrapped with $old before the rotation" "^[1-9][0-9]*$" "$before"
  # 1) 프로비저닝·마운트가 먼저(온보딩 런북과 같은 순서 — 마운트 전에 등록하면 등록이 실패한다)
  "$JAVA" -jar "$JAR" --spring.profiles.active=cli crypto kek init --tenant "$t" --kek-id "$new" --secrets-dir "$STATE/secrets" --if-absent yes
  push_secrets
  mount_keks
  # 2) 등록(CURRENT 교체 — 새 감싸기는 새 KEK) → 3) 재래핑 dry-run(기본) → 적용 → 두 번째 적용 0건
  out="$(kek_job crypto kek register --tenant "$t" --kek-id "$new" --operator kind-rotate)"
  check "kek: register $new" "^KEK_REGISTER $t $new CURRENT$" "$(echo "$out" | grep '^KEK_REGISTER')"
  out="$(kek_job crypto kek rewrap --tenants "$t" --operator kind-rotate)"
  check "kek: dry-run counts the keys and changes nothing" "^KEK_REWRAP $t to=$new DRY_RUN rewrapped=0 pending=$before skipped=[0-9]+ failed=0$" \
    "$(echo "$out" | grep '^KEK_REWRAP')"
  check "kek: dry-run left the rows on $old" "^$before$" "$(old_kek_rows "$t" "$old")"
  out="$(kek_job crypto kek rewrap --tenants "$t" --apply yes --operator kind-rotate)"
  check "kek: apply rewraps every live key" "^KEK_REWRAP $t to=$new APPLY rewrapped=$before pending=0 skipped=[0-9]+ failed=0$" \
    "$(echo "$out" | grep '^KEK_REWRAP')"
  out="$(kek_job crypto kek rewrap --tenants "$t" --apply yes --operator kind-rotate)"
  check "kek: the second run rewraps nothing (idempotent)" "^KEK_REWRAP $t to=$new APPLY rewrapped=0 pending=0 skipped=[0-9]+ failed=0$" \
    "$(echo "$out" | grep '^KEK_REWRAP')"
  check "kek: no live key is wrapped with $old" "^0$" "$(old_kek_rows "$t" "$old")"
  check "kek: one audit row per rewrapped key" "^$before$" \
    "$(sql "SELECT count(*) FROM audit_log WHERE tenant_id = '$t' AND action = 'KEK_REWRAPPED' AND detail->>'toKekId' = '$new'")"
  check "kek: registry — $new CURRENT, $old RETIRED" "^CURRENT RETIRED$" \
    "$(sql "SELECT string_agg(status, ' ' ORDER BY kek_id = '$old') FROM tenant_kek WHERE tenant_id = '$t' AND kek_id IN ('$new', '$old')")"
}

# TSA 키(스텁 키 저장소) 회전 + 신뢰 앵커 집합에 덧붙이기. 새 키의 토큰은 앵커 실행으로만 생긴다 — 오늘 앵커는 시드가 이미 만들었으므로 데모 시계
# 오프셋(+P1D, 데모 프로파일만)으로 "내일" 앵커를 하나 만든다(데모 장치 — 클러스터는 일회용)
rotate_tsa() {
  echo "== ROTATE demo/tsa-stub.p12 + tsa/trust-anchors.pem"
  local out
  stub_cert "$STATE/secrets/demo/tsa-stub.p12" > "$ROT/tsa-old.pem"
  check "tsa: the anchor set holds the current stub certificate" "^1$" \
    "$(grep -c 'BEGIN CERTIFICATE' "$STATE/secrets/tsa/trust-anchors.pem" | tr -d ' ')"
  rotate_file demo/tsa-stub.p12
  stub_cert "$STATE/secrets/demo/tsa-stub.p12" > "$ROT/tsa-new.pem"
  cat "$ROT/tsa-new.pem" >> "$STATE/secrets/tsa/trust-anchors.pem"           # 덧붙이기(교체가 아니다)
  push_secrets
  restart_app
  check "tsa: the set now holds the old and the new certificate" "^2$" \
    "$(grep -c 'BEGIN CERTIFICATE' "$STATE/secrets/tsa/trust-anchors.pem" | tr -d ' ')"
  out="$(run_job ga-job-anchor '["--spring.profiles.active=cli,$(GA_PROFILE)","--ga.demo.clock-offset=P1D","anchor","run","--tenants","all","--operator","kind-rotate"]')"
  check "tsa: a new anchor is stamped with the new key" "^ANCHOR_RUN " "$(echo "$out" | grep '^ANCHOR_RUN' | head -n 1)"
  # 대조: 새 키 하나(스텁이 기동 때 쓴 /tmp/tsa-trust.pem)로는 옛 앵커가 검증되지 않는다 — 집합이 필요하다는 것을 시험이 먼저 보인다
  local control
  control="$(VERIFY_TRUST=/tmp/tsa-trust.pem verify 2>&1 || true)"
  check "tsa: the new certificate alone does not verify the old anchors (control — TSA trust finding)" "MISMATCH.*TSA_UNTRUSTED|TSA_UNTRUSTED.*MISMATCH" \
    "$(echo "$control" | grep -E 'VERIFY_TENANT|TSA_' | tr '\n' ' ')"
}

rotate() {
  local which="${ROTATE_ARG:-all}"
  umask 077
  mkdir -p "$ROT"
  jar_env
  mint DEMO1 demo-agent "$ROT/agent.hdr"
  case "$which" in
    cursor) rotate_cursor ;;
    request-hash) rotate_request_hash ;;
    receipt) [ -f "$ROT/k1" ] || rotate_request_hash; rotate_receipt ;;
    demo-oidc) rotate_demo_oidc ;;
    kek) rotate_kek ;;
    tsa) rotate_tsa ;;
    all) rotate_cursor; rotate_request_hash; rotate_receipt; rotate_demo_oidc; rotate_kek; rotate_tsa ;;
    *) echo "unknown key $which" >&2; return 2 ;;
  esac
  echo "ROTATE checks failures=$FAILS"
  [ "$FAILS" -eq 0 ] || return 1
  verify
}

# 클러스터 대상 E2E 준비: 6A 주체(스케줄러·준법 둘째), DEMO2 CHAIN_BROKEN 사건(seed.sh는 시연 뒤 해소한다 — 화면 흐름이 해소할 새 사건 하나), 토큰 파일
e2e_prep() {
  local dir="${PREP_DIR:?usage: kind.sh e2e-prep <cluster> <dir>}" seq original open
  umask 077
  mkdir -p "$dir"
  jar_env
  cluster_cli demo seed --file disclosure-demo/src/main/resources/demo/phase6a-seed.json --operator e2e | grep -c '^SEED ' | sed 's/^/E2E_PREP seed lines /'
  open="SELECT count(*) FROM compliance_flag WHERE tenant_id = 'DEMO2' AND type = 'CHAIN_BROKEN' AND resolved_at IS NULL"
  if [ "$(sql "$open")" = 0 ]; then
    seq="$(sql "SELECT min(seq) FROM audit_log WHERE tenant_id = 'DEMO2' AND action = 'RULE_ACTIVATE'")"
    original="$(sql "SELECT detail::text FROM audit_log WHERE tenant_id = 'DEMO2' AND seq = $seq")"
    sql "BEGIN; SET LOCAL session_replication_role = replica; UPDATE audit_log SET detail = '{\"tampered\": true}' WHERE tenant_id = 'DEMO2' AND seq = $seq; COMMIT;" >/dev/null
    run_job ga-job-verify-tenant '["--spring.profiles.active=cli,$(GA_PROFILE)","verify","tenant","--tenants","DEMO2","--tsa-trust","/var/run/ga-secrets/tsa/trust-anchors.pem","--operator","e2e"]' \
      | grep -E '^VERIFY_TENANT' || true
    printf '%s\n' "BEGIN;" "SET LOCAL session_replication_role = replica;" \
      "UPDATE audit_log SET detail = :'detail'::jsonb WHERE tenant_id = 'DEMO2' AND seq = $seq;" "COMMIT;" \
      | $KUBECTL -n $NS exec -i ga-postgres-0 -- psql -U postgres -d disclosure -v ON_ERROR_STOP=1 -q -v detail="$original"
  fi
  echo "E2E_PREP open CHAIN_BROKEN on DEMO2: $(sql "$open")"
  mint DEMO1 demo-scheduler "$dir/scheduler.jwt" raw PT3H
  mint DEMO1 demo-compliance "$dir/compliance.jwt" raw PT3H
  echo "E2E_PREP tokens written (values not shown)"
}

down() {
  "$KIND" delete cluster --name "$CLUSTER"
  rm -rf "$STATE"
}

case "$CMD" in
  up) up ;;
  secrets) secrets ;;
  deploy) deploy ;;
  smoke) smoke ;;
  logscan) logscan ;;
  seed) seed ;;
  verify) verify ;;
  backup) backup ;;
  restore) RUN_ARG="${3:-}" restore ;;
  rotate) ROTATE_ARG="${3:-all}" rotate ;;
  e2e-prep) PREP_DIR="${3:-}" e2e_prep ;;
  down) down ;;
  *) echo "unknown command $CMD" >&2; exit 2 ;;
esac
