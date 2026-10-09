// E2E 환경(Phase 7 계획 ⑦): 격리 컨테이너(PostgreSQL 18.6·SeaweedFS digest 고정, 임의 포트 — 사용자 compose 볼륨과 무관) + 부트 jar로 데모 데이터 준비
// + 데모 프로파일 웹 앱(같은 출처 화면). Docker가 없으면 실패한다(스킵 없음). 키·KEK·PEM은 build/e2e/run 아래(저장소 밖으로 나가지 않고 git 무시).
//   up:   컨테이너 → 시드(CLI) → 웹 앱 → build/e2e/env.json
//   down: 웹 앱 종료 → 컨테이너 제거(-v). 남긴 것이 있으면 출력한다(6B D-8 — 잔존 상태는 보고서 D 항목).
// 개인정보는 다루지 않는다 — 고객은 E2E가 화면으로 등록한다(허구 센티널 파일, run.mjs). CLI 인자·환경변수에 개인정보 없음.
import { spawn, spawnSync } from 'node:child_process';
import { closeSync, existsSync, mkdirSync, openSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const web = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const repo = resolve(web, '..');
const out = resolve(web, 'build/e2e');
const run = resolve(out, 'run');
const ENV = resolve(out, 'env.json');
const PG_IMAGE = 'postgres:18.6';
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

export async function up() {
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
    GA_LOCAL_KEK_FILE: resolve(run, 'kek.json'),
  };
  const keys = [`--ga.demo.oidc-key-file=${resolve(run, 'demo-oidc.key')}`, `--ga.demo.oidc-public-pem=${resolve(run, 'demo-oidc.pem')}`];
  const common = ['--ga.storage.s3.create-bucket=true', '--ga.engine.mode=stub', `--ga.engine.stub-table=${DEMO}/demo/engine-table.json`,
    '--ga.tsa.mode=stub', `--ga.tsa.stub.key-store=${resolve(run, 'tsa-stub.p12')}`, `--ga.tsa.trust-pem=${resolve(run, 'tsa-trust.pem')}`];
  const cli = (...args) => sh(java, ['-jar', jar, '--spring.profiles.active=cli', ...common, ...args], { env: appEnv, cwd: repo });
  const cliDemo = (...args) => sh(java, ['-jar', jar, '--spring.profiles.active=cli,demo', ...common, ...keys, ...args], { env: appEnv, cwd: repo });
  const log = [];
  const step = (label, f) => { const o = f(); log.push(`== ${label}\n${o}`); return o; };

  const today = new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Seoul' });
  step('seed', () => cli('demo', 'seed', '--file', `${DEMO}/demo/phase1-seed.json`, '--operator', 'e2e'));
  step('seed 6a', () => cli('demo', 'seed', '--file', `${DEMO}/demo/phase6a-seed.json`, '--operator', 'e2e'));
  for (const b of ['rules/DISC-2026-07.bundle.json', 'rules/DISC-2027-01.bundle.json', 'templates/STANDARD-v1.bundle.json']) {
    step(`distribute ${b}`, () => cli('rules', 'distribute', '--bundle', b, '--tenants', 'DEMO1,DEMO2', '--operator', 'e2e'));
  }
  step('approve house rule', () => cli('rules', 'approve', '--tenant', 'DEMO1', '--rule', 'DEMO1-HOUSE-2026', '--operator', 'e2e'));
  step('activate', () => cli('rules', 'activate', '--as-of', today, '--tenants', 'all', '--operator', 'e2e'));
  for (const t of ['DEMO1', 'DEMO2']) {
    for (const f of ['product-groups.json', 'insurer-panel.json', 'products.json']) {
      step(`catalog ${t} ${f}`, () => cli('catalog', 'import', '--tenant', t, '--file', `${DEMO}/demo/catalog/${f}`, '--operator', 'e2e'));
    }
  }
  step('kek', () => cli('crypto', 'init-kek', '--file', resolve(run, 'kek.json')));
  // 데모 웹 앱은 기동 때 공개키 PEM을 읽는다 — 키를 먼저 만든다. 스케줄러 토큰은 하네스의 통지 작업용(화면이 아니다 — 소유자 전용 파일)
  const jwt = (subject) => cliDemo('demo', 'token', '--tenant', 'DEMO1', '--subject', subject, '--ttl', 'PT3H').trim().split('\n')
    .filter((l) => /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(l)).pop();
  writeFileSync(resolve(run, 'scheduler.jwt'), jwt('demo-scheduler') ?? '', { mode: 0o600 });

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
  env.baseUrl = `http://127.0.0.1:${appPort}`;
  const serverLog = openSync(resolve(out, 'server.log'), 'w', 0o600);
  const app = spawn(java, ['-jar', jar, '--spring.profiles.active=demo', `--server.port=${appPort}`, '--server.address=127.0.0.1', ...common, ...keys,
    `--ga.api.jwt.public-key-location=${resolve(run, 'demo-oidc.pem')}`, `--ga.api.cursor-key-file=${resolve(run, 'cursor.key')}`,
    `--ga.api.request-hash-key-file=${resolve(run, 'request-hash.key')}`, `--ga.api.receipt-key-file=${resolve(run, 'receipt.key')}`,
    `--ga.sign.link-base-url=${env.baseUrl}/s#`], { env: appEnv, cwd: repo, stdio: ['ignore', serverLog, serverLog], detached: true });
  closeSync(serverLog);
  env.app = app.pid;
  app.unref();
  writeFileSync(ENV, JSON.stringify(env, null, 2));
  await waitFor('web app', async () => {
    try { return (await fetch(`${env.baseUrl}/actuator/health`)).ok; } catch { return false; }
  }, 180);
  return env;
}

export function down() {
  if (!existsSync(ENV)) return [];
  const env = JSON.parse(readFileSync(ENV, 'utf8'));
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
