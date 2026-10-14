// E2E 환경(Phase 7 계획 ⑦): 격리 컨테이너(PostgreSQL 18.6·SeaweedFS digest 고정, 임의 포트 — 사용자 compose 볼륨과 무관) + 부트 jar로 데모 데이터 준비
// + 데모 프로파일 웹 앱(같은 출처 화면). Docker가 없으면 실패한다(스킵 없음). 키·KEK·PEM은 build/e2e/run 아래(저장소 밖으로 나가지 않고 git 무시).
//   up:   컨테이너 → 시드(CLI) → 웹 앱 → build/e2e/env.json
//   down: 웹 앱 종료 → 컨테이너 제거(-v). 남긴 것이 있으면 출력한다(6B D-8 — 잔존 상태는 보고서 D 항목).
// (Phase 8 11단계) GA_E2E_KIND=<kind 클러스터>: 컨테이너·앱을 띄우지 않고 kind-demo 배포(deploy/scripts/kind.sh)의 공개 진입점 A(직원)·B(서명)와 내부
// 진입점 C(mTLS — 포트 포워드)로 같은 시험을 돈다. 준비(6A 주체·DEMO2 CHAIN_BROKEN 사건·토큰)는 kind.sh e2e-prep, 행 수 대조는 kubectl exec psql,
// 서버 로그는 앱 파드 로그. 이름 해석: 브라우저 --host-resolver-rules, Node(요청 문맥) kind-hosts.mjs. 인증서: 브라우저는 두 진입점 인증서의 SPKI만
// 허용, Node는 데모 서버 CA(NODE_EXTRA_CA_CERTS) — 둘 다 그 클러스터의 kind 비밀 디렉터리(~/.ga-disclosure/kind/<클러스터>/pki, 공개 인증서)에서.
// 개인정보는 다루지 않는다 — 고객은 E2E가 화면으로 등록한다(허구 센티널 파일, run.mjs). CLI 인자·환경변수에 개인정보 없음.
import { spawn, spawnSync } from 'node:child_process';
import { createHash, X509Certificate } from 'node:crypto';
import { closeSync, existsSync, mkdirSync, openSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { connect, createServer } from 'node:net';
import { homedir } from 'node:os';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const web = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const repo = resolve(web, '..');
const out = resolve(web, 'build/e2e');
const run = resolve(out, 'run');
const ENV = resolve(out, 'env.json');
const PG_IMAGE = 'postgres@sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336';   // 18.6 — 하네스·카탈로그·compose와 같다(PostgresDigestIT)
const S3_IMAGE = 'chrislusf/seaweedfs@sha256:4e61d15fd35994cb1e43e1e553dff106794841fd9a99ade2fc8c8bfce4d7872d';
const DEMO = resolve(repo, 'disclosure-demo/src/main/resources');

const sh = (cmd, args, opts = {}) => {
  const r = spawnSync(cmd, args, { encoding: 'utf8', ...opts });
  if (r.status !== 0) throw new Error(`${cmd} ${args.slice(0, 3).join(' ')} … failed (${r.status}): ${(r.stderr || r.stdout || '').slice(-2000)}`);
  return r.stdout;
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const freePort = () => new Promise((res, rej) => {
  const s = createServer();
  s.once('error', rej);
  s.listen(0, '127.0.0.1', () => { const { port } = s.address(); s.close(() => res(port)); });
});

function requireEnv(name) {
  const v = process.env[name];
  if (!v) throw new Error(`${name} is not set (run through ./gradlew :disclosure-web:e2e)`);
  return v;
}

async function waitFor(what, check, seconds) {
  for (let i = 0; i < seconds * 2; i++) {
    if (await check()) return;
    await sleep(500);
  }
  throw new Error(`${what} did not become ready in ${seconds}s`);
}

// ------------------------------------------------------------------------------------------- 클러스터 대상(GA_E2E_KIND)
const KIND_PORT = 18443;
export const KIND_INTERNAL_HOST = 'traefik-internal.ga-ingress-internal.svc';
export const KIND_HOSTS = ['staff.ga.example.invalid', 'sign.ga.example.invalid', KIND_INTERNAL_HOST];
export const kindState = (cluster) => resolve(homedir(), '.ga-disclosure/kind', cluster);

/** 인증서 공개키(SPKI DER)의 SHA-256 base64 — Chromium --ignore-certificate-errors-spki-list 형식. */
const spki = (file) => createHash('sha256').update(new X509Certificate(readFileSync(file)).publicKey.export({ type: 'spki', format: 'der' })).digest('base64');
const listening = (port) => new Promise((res) => {
  const s = connect(port, '127.0.0.1', () => { s.destroy(); res(true); });
  s.on('error', () => res(false));
});

async function upKind(cluster) {
  if (!/^ga-[a-z0-9-]{1,40}$/.test(cluster)) throw new Error('GA_E2E_KIND must be a kind.sh cluster name (ga-…)');
  const state = kindState(cluster);
  const kubectl = [resolve(repo, 'build/tools/kubectl'), '--context', `kind-${cluster}`];
  rmSync(out, { recursive: true, force: true });
  mkdirSync(run, { recursive: true, mode: 0o700 });
  const env = {
    mode: 'kind', id: `ga-e2e-kind-${process.pid}`, cluster, kubectl, pg: 'ga-postgres-0', dir: run, app: null,
    baseUrl: `https://staff.ga.example.invalid:${KIND_PORT}`, signBase: `https://sign.ga.example.invalid:${KIND_PORT}`, hosts: KIND_HOSTS,
    pki: { ca: resolve(state, 'pki/server-ca.crt'), cert: resolve(state, 'pki/client.crt'), key: resolve(state, 'pki/client.key') },
    spki: ['staff', 'sign'].map((n) => spki(resolve(state, `pki/${n}.crt`))),
  };
  writeFileSync(ENV, JSON.stringify(env, null, 2));
  writeFileSync(resolve(out, 'seed.log'), sh(resolve(repo, 'deploy/scripts/kind.sh'), ['e2e-prep', cluster, run], { cwd: repo }));
  // 내부 진입점 C(ClusterIP — 클러스터 밖에서는 포트 포워드로만): 하네스의 통지 작업 접수(클라이언트 인증서 — 시험 쪽 요청 문맥)
  const port = await freePort();
  const pf = spawn(kubectl[0], [...kubectl.slice(1), '-n', 'ga-ingress-internal', 'port-forward', 'svc/traefik-internal', `${port}:9443`],
    { stdio: 'ignore', detached: true });
  pf.unref();
  env.pf = pf.pid;
  env.internalUrl = `https://${KIND_INTERNAL_HOST}:${port}`;
  writeFileSync(ENV, JSON.stringify(env, null, 2));
  await waitFor('internal ingress port-forward', () => listening(port), 30);
  const staff = new URL(env.baseUrl);
  await waitFor('staff host', () => spawnSync('curl', ['-s', '-o', '/dev/null', '-w', '%{http_code}', '--cacert', env.pki.ca,
    '--resolve', `${staff.hostname}:${staff.port}:127.0.0.1`, `${env.baseUrl}/staff`], { encoding: 'utf8' }).stdout === '200', 60);
  return env;
}

function downKind(env) {
  if (env.pf) {
    try { process.kill(env.pf, 'SIGTERM'); } catch { /* already gone */ }
  }
  return [];                                                               // 클러스터는 kind.sh down이 지운다
}

export async function up() {
  if (process.env.GA_E2E_KIND) return upKind(process.env.GA_E2E_KIND);
  const java = requireEnv('GA_E2E_JAVA');
  const jar = requireEnv('GA_E2E_JAR');
  sh('docker', ['info', '--format', '{{.ServerVersion}}']);            // Docker가 없으면 여기서 실패(스킵 아님)
  rmSync(out, { recursive: true, force: true });
  mkdirSync(run, { recursive: true, mode: 0o700 });
  const id = `ga-e2e-${process.pid}-${Date.now()}`;
  const env = { id, pg: `${id}-pg`, s3: `${id}-s3`, app: null, baseUrl: null, dir: run };
  writeFileSync(ENV, JSON.stringify(env, null, 2));

  sh('docker', ['run', '-d', '--name', env.pg, '--label', `ga-e2e=${id}`, '-e', 'POSTGRES_DB=disclosure', '-e', 'POSTGRES_USER=postgres',
    '-e', 'POSTGRES_PASSWORD=postgres', '-v', `${repo}/docker/postgres/init-roles.sql:/docker-entrypoint-initdb.d/00-init-roles.sql:ro`,
    '-p', '127.0.0.1::5432', PG_IMAGE]);
  sh('docker', ['run', '-d', '--name', env.s3, '--label', `ga-e2e=${id}`, '-v', `${repo}/docker/seaweedfs/s3.json:/etc/seaweedfs/s3.json:ro`,
    '-p', '127.0.0.1::8333', S3_IMAGE, 'server', '-dir=/data', '-volume.max=2000', '-master.volumeSizeLimitMB=64', '-s3', '-s3.port=8333',
    '-s3.config=/etc/seaweedfs/s3.json']);
  const port = (name, p) => Number(sh('docker', ['port', name, `${p}/tcp`]).trim().split(':').pop());
  env.pgPort = port(env.pg, 5432);
  env.s3Port = port(env.s3, 8333);
  writeFileSync(ENV, JSON.stringify(env, null, 2));
  await waitFor('postgres', () => {
    const logs = spawnSync('docker', ['logs', env.pg], { encoding: 'utf8' });
    if (!`${logs.stdout}${logs.stderr}`.includes('PostgreSQL init process complete')) return false;
    return spawnSync('docker', ['exec', env.pg, 'pg_isready', '-h', '127.0.0.1', '-U', 'postgres', '-d', 'disclosure']).status === 0;
  }, 120);
  await waitFor('seaweedfs', async () => {
    try { await fetch(`http://127.0.0.1:${env.s3Port}/`); return true; } catch { return false; }
  }, 120);

  const appEnv = {
    ...process.env,
    DISCLOSURE_DB_URL: `jdbc:postgresql://127.0.0.1:${env.pgPort}/disclosure`,
    GA_S3_ENDPOINT: `http://127.0.0.1:${env.s3Port}`,
    GA_S3_BUCKET: 'ga-e2e',
    GA_SECRETS_DIR: resolve(run, 'secrets'),
  };
  const keys = [`--ga.demo.oidc-public-pem=${resolve(run, 'demo-oidc.pem')}`];
  const common = ['--ga.storage.s3.create-bucket=true', '--ga.engine.mode=stub', `--ga.engine.stub-table=${DEMO}/demo/engine-table.json`,
    '--ga.tsa.mode=stub', `--ga.tsa.stub.key-store=${resolve(run, 'tsa-stub.p12')}`, `--ga.tsa.trust-pem=${resolve(run, 'tsa-trust.pem')}`];
  const cli = (...args) => sh(java, ['-jar', jar, '--spring.profiles.active=cli', ...common, ...args], { env: appEnv, cwd: repo });
  const cliDemo = (...args) => sh(java, ['-jar', jar, '--spring.profiles.active=cli,demo', ...common, ...keys, ...args], { env: appEnv, cwd: repo });
  const log = [];
  const step = (label, f) => { const o = f(); log.push(`== ${label}\n${o}`); return o; };

  const today = new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Seoul' });
  // Phase 8: 앱은 기동 때 마이그레이션하지 않는다 — 마이그레이터 롤의 db migrate가 먼저(앱 컨텍스트 없이). 그 뒤 명령은 스키마 버전 가드를 지난다
  step('db migrate', () => cli('db', 'migrate'));
  step('seed', () => cli('demo', 'seed', '--file', `${DEMO}/demo/phase1-seed.json`, '--operator', 'e2e'));
  step('seed 6a', () => cli('demo', 'seed', '--file', `${DEMO}/demo/phase6a-seed.json`, '--operator', 'e2e'));
  for (const b of ['rules/DISC-2026-07.bundle.json', 'rules/DISC-2027-01.bundle.json', 'templates/STANDARD-v1.bundle.json', 'templates/STANDARD-v2.bundle.json']) {
    step(`distribute ${b}`, () => cli('rules', 'distribute', '--bundle', b, '--tenants', 'DEMO1,DEMO2', '--operator', 'e2e'));
  }
  step('approve house rule', () => cli('rules', 'approve', '--tenant', 'DEMO1', '--rule', 'DEMO1-HOUSE-2026', '--operator', 'e2e'));
  step('activate', () => cli('rules', 'activate', '--as-of', today, '--tenants', 'all', '--operator', 'e2e'));
  for (const t of ['DEMO1', 'DEMO2']) {
    for (const f of ['product-groups.json', 'insurer-panel.json', 'products.json']) {
      step(`catalog ${t} ${f}`, () => cli('catalog', 'import', '--tenant', t, '--file', `${DEMO}/demo/catalog/${f}`, '--operator', 'e2e'));
    }
  }
  // 비밀(Phase 8): 실행 디렉터리 안의 비밀 디렉터리 — API 키·데모 OIDC 서명 키, 테넌트 KEK(만들고 레지스트리에 등록). 앱은 비밀을 만들지 않는다
  step('secrets', () => cli('secrets', 'init', '--secrets-dir', resolve(run, 'secrets'), '--demo', 'yes'));
  for (const t of ['DEMO1', 'DEMO2']) {
    step(`kek ${t}`, () => cli('crypto', 'kek', 'init', '--tenant', t, '--kek-id', `${t}-KEK-1`, '--secrets-dir', resolve(run, 'secrets')));
    step(`kek register ${t}`, () => cli('crypto', 'kek', 'register', '--tenant', t, '--kek-id', `${t}-KEK-1`, '--operator', 'e2e'));
  }
  // 데모 웹 앱은 기동 때 공개키 PEM을 읽는다 — 키를 먼저 만든다. 스케줄러 토큰은 하네스의 통지 작업용(화면이 아니다 — 소유자 전용 파일)
  const jwt = (subject) => cliDemo('demo', 'token', '--tenant', 'DEMO1', '--subject', subject, '--ttl', 'PT3H').trim().split('\n')
    .filter((l) => /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(l)).pop();
  writeFileSync(resolve(run, 'scheduler.jwt'), jwt('demo-scheduler') ?? '', { mode: 0o600 });
  // Phase 8 G9: 어휘 밖 코드의 직접 요청(화면을 거치지 않는다)이 지금처럼 거부되는지 — 준법 토큰
  writeFileSync(resolve(run, 'compliance.jwt'), jwt('demo-compliance') ?? '', { mode: 0o600 });

  // 준법 흐름의 CHAIN_BROKEN(DEMO2): 감사 행 하나를 슈퍼유저로 바꿔 verify가 불일치를 찾게 한 뒤 원래대로 되돌린다(seed.sh와 같은 사건 — 해소는 화면이 한다)
  const psql = (sql) => sh('docker', ['exec', '-i', env.pg, 'psql', '-q', '-tA', '-U', 'postgres', '-d', 'disclosure', '-v', 'ON_ERROR_STOP=1'], { input: sql });
  const seq = psql("SELECT min(seq) FROM audit_log WHERE tenant_id = 'DEMO2' AND action = 'RULE_ACTIVATE';").trim();
  const original = psql(`SELECT detail::text FROM audit_log WHERE tenant_id = 'DEMO2' AND seq = ${seq};`).trim();
  psql(`BEGIN; SET LOCAL session_replication_role = replica; UPDATE audit_log SET detail = '{"tampered": true}' WHERE tenant_id = 'DEMO2' AND seq = ${seq}; COMMIT;`);
  const verify = spawnSync(java, ['-jar', jar, '--spring.profiles.active=cli', ...common, 'verify', 'tenant', '--tenants', 'DEMO2', '--tsa-trust',
    resolve(run, 'tsa-trust.pem'), '--report-dir', run, '--operator', 'e2e'], { encoding: 'utf8', env: appEnv, cwd: repo });
  log.push(`== verify DEMO2 (mismatch expected, exit ${verify.status})\n${verify.stdout}`);
  psql(`BEGIN; SET LOCAL session_replication_role = replica; UPDATE audit_log SET detail = $json$${original}$json$::jsonb WHERE tenant_id = 'DEMO2' AND seq = ${seq}; COMMIT;`);
  step('chain broken flag', () => cli('flags', 'list', '--tenant', 'DEMO2', '--type', 'CHAIN_BROKEN', '--status', 'OPEN', '--operator', 'e2e'));
  writeFileSync(resolve(out, 'seed.log'), log.join('\n'));

  const appPort = await freePort();
  const managementPort = await freePort();
  const internalPort = await freePort();
  env.baseUrl = `http://127.0.0.1:${appPort}`;
  // Phase 8 Q5: /internal/**은 내부 포트에만(운영은 클러스터 안 진입점 C) — 하네스의 통지 작업도 그 포트로
  env.internalUrl = `http://127.0.0.1:${internalPort}`;
  env.managementUrl = `http://127.0.0.1:${managementPort}`;
  const serverLog = openSync(resolve(out, 'server.log'), 'w', 0o600);
  const app = spawn(java, ['-jar', jar, '--spring.profiles.active=demo', `--server.port=${appPort}`, '--server.address=127.0.0.1',
    `--management.server.port=${managementPort}`, '--management.server.address=127.0.0.1', `--ga.internal.port=${internalPort}`, ...common, ...keys,
    `--ga.api.jwt.public-key-location=${resolve(run, 'demo-oidc.pem')}`,
    `--ga.sign.link-base-url=${env.baseUrl}/s#`], { env: appEnv, cwd: repo, stdio: ['ignore', serverLog, serverLog], detached: true });
  closeSync(serverLog);
  env.app = app.pid;
  app.unref();
  writeFileSync(ENV, JSON.stringify(env, null, 2));
  await waitFor('web app', async () => {
    try { return (await fetch(`${env.managementUrl}/actuator/health/readiness`)).ok; } catch { return false; }
  }, 180);
  return env;
}

export function down() {
  if (!existsSync(ENV)) return [];
  const env = JSON.parse(readFileSync(ENV, 'utf8'));
  if (env.mode === 'kind') return downKind(env);
  const left = [];
  if (env.app) {
    try { process.kill(env.app, 'SIGTERM'); } catch { /* already gone */ }
  }
  for (const name of [env.pg, env.s3]) {
    if (name && spawnSync('docker', ['rm', '-f', '-v', name], { encoding: 'utf8' }).status !== 0) left.push(name);
  }
  const still = spawnSync('docker', ['ps', '-aq', '--filter', `label=ga-e2e=${env.id}`], { encoding: 'utf8' }).stdout.trim();
  if (still) left.push(...still.split('\n'));
  return left;
}

if (process.argv[2] === 'up') {
  up().then((e) => { console.log(`E2E UP ${e.baseUrl}`); }, (err) => { console.error(String(err)); down(); process.exit(1); });
} else if (process.argv[2] === 'down') {
  const left = down();
  console.log(left.length === 0 ? 'E2E DOWN (containers removed)' : `E2E DOWN — left behind: ${left.join(', ')}`);
}
