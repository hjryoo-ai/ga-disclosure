#!/usr/bin/env bash
# kind 데모(Phase 8 ③, G4): 로컬 쿠버네티스 클러스터에 kind-demo 오버레이를 올리고 스모크·로그 스캔을 돈다. 도구는 ./gradlew deployTools가 받은
# build/tools의 고정판(tools.lock — 전역 설치 없음), 노드 이미지는 tools.lock의 digest.
#
#   deploy/scripts/kind.sh up      <cluster>   클러스터(노드 이미지 digest, 호스트 127.0.0.1:18443 → 공개 진입점) + 이미지 적재(appImage·webImage)
#   deploy/scripts/kind.sh secrets <cluster>   비밀 생성(저장소 밖 ~/.ga-disclosure/kind/<cluster>, 소유자 전용) → kubectl Secret
#   deploy/scripts/kind.sh deploy  <cluster>   네임스페이스·CRD → 비밀 → 렌더 적용 → 마이그레이션 Job 완료 → 롤아웃 대기
#   deploy/scripts/kind.sh smoke   <cluster>   인그레스·mTLS·한도·NetworkPolicy 단언(실패하면 종료 1)
#   deploy/scripts/kind.sh logscan <cluster>   모든 파드·Job 로그를 모아 비밀 값·허구 개인정보 센티널 스캔(걸리면 종료 1)
#   deploy/scripts/kind.sh down    <cluster>   클러스터와 그 비밀 디렉터리 삭제(이 스크립트가 만든 것만)
#
# 값은 출력하지 않는다. 비밀 값은 명령줄·환경변수에 싣지 않는다 — 파일(소유자 전용)과 --from-file·--from-env-file로만 넘긴다.
set -euo pipefail
cd "$(dirname "$0")/../.."

CMD="${1:?usage: kind.sh up|secrets|deploy|smoke|logscan|down <cluster>}"
CLUSTER="${2:?cluster name (e.g. ga-p8-$(date +%s))}"
[[ "$CLUSTER" =~ ^ga-[a-z0-9-]{1,40}$ ]] || { echo "cluster name must match ga-[a-z0-9-]+" >&2; exit 2; }

TOOLS="build/tools"
KIND="$TOOLS/kind"
KUBECTL="$TOOLS/kubectl --context kind-$CLUSTER"
STATE="$HOME/.ga-disclosure/kind/$CLUSTER"
HOST_PORT=18443
NS=ga-disclosure

lock() { awk -v n="$1" '$1 == n { print $4 }' deploy/tools.lock; }
render() { $TOOLS/kubectl kustomize --load-restrictor LoadRestrictionsNone deploy/overlays/kind-demo; }
java_bin() { ./gradlew -q :disclosure-app:demoJavaLauncher; }
app_jar() { ls disclosure-app/build/libs/disclosure-app-*.jar | grep -v -- '-plain.jar' | head -n 1; }

up() {
  [ -x "$KIND" ] || ./gradlew -q :disclosure-app:deployTools
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
  ./gradlew -q :disclosure-app:appImage :disclosure-app:webImage
  "$KIND" load docker-image --name "$CLUSTER" ga-disclosure/app:dev ga-disclosure/web:dev
}

# 무작위 비밀번호(16진 32자) — 파일로만. (tr < /dev/urandom | head는 pipefail에서 SIGPIPE로 실패한다)
password() { openssl rand -hex 16 | tr -d '\n'; }

secrets() {
  umask 077
  mkdir -p "$STATE"/{secrets,db,pki,s3}
  chmod 700 "$STATE"
  local JAVA JAR
  ./gradlew -q :disclosure-app:bootJar
  JAVA="$(java_bin)"; JAR="$(app_jar)"
  # 키 재료(앱의 오프라인 명령 — 없는 것만 만든다): API 키·백업 키·데모 OIDC 서명 키·스텁 TSA 키 저장소, 데모 테넌트의 첫 KEK
  "$JAVA" -jar "$JAR" --spring.profiles.active=cli secrets init --secrets-dir "$STATE/secrets" --demo yes
  for t in DEMO1 DEMO2; do
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
  printf '{"identities":[{"name":"ga-app","credentials":[{"accessKey":"%s","secretKey":"%s"}],"actions":["Admin","Read","List","Tagging","Write"]}]}' \
    "$(cat "$STATE/s3/access")" "$(cat "$STATE/s3/secret")" > "$STATE/s3/s3.json"
  # 데모 OIDC 공개키(비밀 아님 — 서명 키에서 유도)
  openssl pkey -in "$STATE/secrets/demo/oidc-signing" -pubout -out "$STATE/demo-oidc.pem" 2>/dev/null
  pki

  # 클러스터로(값은 파일에서만). 이미 있으면 바꾼다(같은 값 — 재실행 멱등)
  local apply="$KUBECTL apply -f -"
  local secret_args=()
  while IFS= read -r f; do
    local name="${f#"$STATE/secrets/"}"
    secret_args+=("--from-file=${name//\//.}=$f")
  done < <(find "$STATE/secrets" -type f | sort)
  $KUBECTL -n $NS create secret generic ga-secrets "${secret_args[@]}" --dry-run=client -o yaml | $apply
  $KUBECTL -n $NS create secret generic ga-pg \
    --from-file=superuser-password="$STATE/db/superuser-password" --from-file=migrator-password="$STATE/db/migrator-password" \
    --from-file=app-password="$STATE/db/app-password" --from-file=operator-password="$STATE/db/operator-password" \
    --from-file=job-lock-password="$STATE/db/job-lock-password" --from-file=health-password="$STATE/db/health-password" \
    --from-file=backup-password="$STATE/db/backup-password" --dry-run=client -o yaml | $apply
  $KUBECTL -n $NS create secret generic ga-db-app --from-env-file="$STATE/db/app.env" --dry-run=client -o yaml | $apply
  $KUBECTL -n $NS create secret generic ga-db-migrator --from-env-file="$STATE/db/migrator.env" --dry-run=client -o yaml | $apply
  $KUBECTL -n $NS create secret generic ga-s3 --from-env-file="$STATE/s3/app.env" --dry-run=client -o yaml | $apply
  $KUBECTL -n $NS create secret generic ga-seaweed --from-file=s3.json="$STATE/s3/s3.json" --dry-run=client -o yaml | $apply
  $KUBECTL -n $NS create configmap ga-demo-oidc-public --from-file=demo-oidc.pem="$STATE/demo-oidc.pem" --dry-run=client -o yaml | $apply
  $KUBECTL -n ga-ingress create secret tls ga-tls-staff --cert="$STATE/pki/staff.crt" --key="$STATE/pki/staff.key" --dry-run=client -o yaml | $apply
  $KUBECTL -n ga-ingress create secret tls ga-tls-sign --cert="$STATE/pki/sign.crt" --key="$STATE/pki/sign.key" --dry-run=client -o yaml | $apply
  $KUBECTL -n ga-ingress-internal create secret tls ga-tls-internal --cert="$STATE/pki/internal.crt" --key="$STATE/pki/internal.key" --dry-run=client -o yaml | $apply
  $KUBECTL -n ga-ingress-internal create secret generic ga-internal-client-ca --from-file=ca.crt="$STATE/pki/client-ca.crt" --dry-run=client -o yaml | $apply
  echo "KIND_SECRETS created in $NS, ga-ingress, ga-ingress-internal (values not shown)"
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
  # 3) 나머지
  $KUBECTL apply --server-side -f "$out" >/dev/null
  $KUBECTL -n $NS rollout status statefulset/ga-postgres --timeout=300s
  $KUBECTL -n $NS rollout status statefulset/ga-seaweedfs --timeout=300s
  $KUBECTL -n $NS wait --for=condition=complete job/ga-db-migrate --timeout=300s
  # 성공한 시도의 출력(앞선 시도는 DB 초기화 전 연결 실패일 수 있다 — Job 재시도)
  $KUBECTL -n $NS logs "$($KUBECTL -n $NS get pods -l job-name=ga-db-migrate --field-selector=status.phase==Succeeded -o name | head -n 1)" | grep '^DB_MIGRATE '
  for d in "$NS/ga-app" "$NS/ga-web" ga-ingress/traefik-public ga-ingress-internal/traefik-internal; do
    $KUBECTL -n "${d%%/*}" rollout status "deployment/${d#*/}" --timeout=600s
  done
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
  $KUBECTL -n ga-ingress-internal port-forward svc/traefik-internal 19443:9443 >/dev/null 2>&1 &
  local pf=$!
  sleep 3
  local cacert="$STATE/pki/server-ca.crt" host=traefik-internal.ga-ingress-internal.svc
  check "C: without a client certificate the TLS handshake fails" "^000$" \
    "$(curl -s -o /dev/null -w '%{http_code}' --cacert "$cacert" --resolve "$host:19443:127.0.0.1" https://$host:19443/internal/v1/jobs || true)"
  check "C: a different SNI still needs a client certificate" "^000$" \
    "$(curl -s -o /dev/null -w '%{http_code}' -k --resolve "other.invalid:19443:127.0.0.1" https://other.invalid:19443/internal/v1/jobs || true)"
  check "C: with the client certificate the request reaches the app (401 without a token)" "^401$" \
    "$(curl -s -o /dev/null -w '%{http_code}' --cacert "$cacert" --cert "$STATE/pki/client.crt" --key "$STATE/pki/client.key" --resolve "$host:19443:127.0.0.1" https://$host:19443/internal/v1/jobs)"
  kill "$pf" 2>/dev/null || true
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
  find "$STATE/secrets" "$STATE/db" "$STATE/s3" -type f ! -name '*.env' ! -name '*.json' -print0 | while IFS= read -r -d '' f; do
    if LC_ALL=C grep -q '[^[:print:][:space:]]' "$f"; then continue; fi        # 이진(키 저장소) — 텍스트 로그에 원형으로 실리지 않는다
    grep -v -- '-----' "$f" | awk 'length($0) >= 16' >> "$needles"
  done
  jq -r '.customers[] | .name, (.phone // empty)' disclosure-demo/src/main/resources/customers.json >> "$needles"
  local files hits
  files=$(find "$logs" -type f | wc -l | tr -d ' ')
  hits=$(grep -rlF -f "$needles" "$logs" | wc -l | tr -d ' ' || true)
  echo "LOGSCAN files=$files needles=$(wc -l < "$needles" | tr -d ' ') hits=$hits"
  [ "$files" -gt 0 ] && [ "$hits" -eq 0 ]
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
  down) down ;;
  *) echo "unknown command $CMD" >&2; exit 2 ;;
esac
