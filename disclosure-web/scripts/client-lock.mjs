// 계약 → 화면 클라이언트 타입(G2). 생성물(src/gen)은 커밋하지 않고 빌드마다 만든다. 커밋하는 것은 contracts-client.lock.json 하나.
//   check: 생성하고 잠금 파일과 대조한다 — ① 계약·생성기가 같은데 생성물이 다르면 실패(비결정) ② 계약이 바뀌었는데 잠금이 그대로면 실패
//          ③ 같은 실행에서 두 번 생성한 바이트가 다르면 실패 ④ 잠금에 없는 계약·계약에 없는 잠금 항목 모두 실패.
//   lock:  생성하고 잠금 파일을 다시 쓴다(계약을 바꾼 커밋에서만 — ./gradlew :disclosure-web:clientLock).
import openapiTS, { astToString } from 'openapi-typescript';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, relative, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const web = resolve(here, '..');
const repo = resolve(web, '..');
const LOCK = resolve(web, 'contracts-client.lock.json');

/** 화면이 부르는 계약(닫힌 목록). 내부·엔진 계약은 화면이 부르지 않는다. */
const CLIENTS = [
  { name: 'disclosure-api', contract: 'contracts/api/v1/disclosure-api.openapi.yaml' },
  { name: 'disclosure-public', contract: 'contracts/api/v1/disclosure-public.openapi.yaml' },
  { name: 'demo-oidc', contract: 'contracts/api/v1/demo-oidc.openapi.yaml' },
];

const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');

async function generate(contractPath) {
  const ast = await openapiTS(pathToFileURL(contractPath), { alphabetize: true });
  return astToString(ast);
}

const mode = process.argv[2];
if (mode !== 'check' && mode !== 'lock') {
  console.error('usage: client-lock.mjs check|lock');
  process.exit(2);
}

const generatorPkg = JSON.parse(await readFile(resolve(web, 'node_modules/openapi-typescript/package.json'), 'utf8'));
const generator = `openapi-typescript@${generatorPkg.version}`;
const entries = [];
await mkdir(resolve(web, 'src/gen'), { recursive: true });
for (const c of CLIENTS) {
  const contractPath = resolve(repo, c.contract);
  const contractBytes = await readFile(contractPath);
  const first = await generate(contractPath);
  const second = await generate(contractPath);
  if (first !== second) {
    console.error(`${c.name}: two generations in one run differ — generation is not deterministic`);
    process.exit(1);
  }
  const output = `src/gen/${c.name}.ts`;
  await writeFile(resolve(web, output), first);
  entries.push({ name: c.name, contract: c.contract, contractSha256: sha256(contractBytes), output, outputSha256: sha256(Buffer.from(first, 'utf8')) });
}
const next = { generator, clients: entries };

if (mode === 'lock') {
  await writeFile(LOCK, JSON.stringify(next, null, 2) + '\n');
  console.log(`wrote ${relative(web, LOCK)} (${entries.length} clients, ${generator})`);
  process.exit(0);
}

let locked;
try {
  locked = JSON.parse(await readFile(LOCK, 'utf8'));
} catch {
  console.error('contracts-client.lock.json is missing — run ./gradlew :disclosure-web:clientLock');
  process.exit(1);
}
const failures = [];
if (locked.generator !== generator) failures.push(`generator changed: locked ${locked.generator}, installed ${generator}`);
const byName = new Map(locked.clients.map((e) => [e.name, e]));
for (const e of entries) {
  const l = byName.get(e.name);
  if (!l) {
    failures.push(`${e.name}: not in the lock file`);
    continue;
  }
  byName.delete(e.name);
  if (l.contract !== e.contract) failures.push(`${e.name}: contract path changed`);
  if (l.contractSha256 !== e.contractSha256) {
    failures.push(`${e.name}: contract changed (${e.contract}) but the lock file was not updated — run ./gradlew :disclosure-web:clientLock`);
  } else if (locked.generator === generator && l.outputSha256 !== e.outputSha256) {
    failures.push(`${e.name}: same contract and generator but different generated client — generation is not deterministic`);
  }
}
for (const stale of byName.keys()) failures.push(`${stale}: in the lock file but no longer generated`);
if (failures.length > 0) {
  for (const f of failures) console.error(f);
  process.exit(1);
}
console.log(`client lock ok (${entries.length} clients, ${generator})`);
