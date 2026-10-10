// 라이선스 검사(webLicenses, 계획 ③): package-lock.json의 모든 패키지 ↔ THIRD-PARTY.md.
//  ① 직접 의존(package.json) = THIRD-PARTY.md "직접 의존" 표(이름·정확한 버전·라이선스·구분) — 양방향
//  ② 허용 목록 밖 라이선스의 패키지 = "예외" 표(이름·버전·라이선스·사유) — 양방향(폐기 항목이 남아도 실패), 예외는 dev 전용만
//  ③ 운영(비 dev) 의존은 허용 목록 안이어야 한다(예외 불가)
import { readFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const web = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const ALLOWED = new Set(['MIT', 'Apache-2.0', 'BSD-2-Clause', 'BSD-3-Clause', 'ISC', 'OFL-1.1', '0BSD', 'BlueOak-1.0.0']);
const lock = JSON.parse(await readFile(resolve(web, 'package-lock.json'), 'utf8'));
const pkg = JSON.parse(await readFile(resolve(web, 'package.json'), 'utf8'));
const doc = await readFile(resolve(web, 'THIRD-PARTY.md'), 'utf8');
const failures = [];

function table(marker) {
  const m = doc.match(new RegExp(`<!-- ${marker} -->([\\s\\S]*?)<!-- /${marker} -->`));
  if (!m) {
    failures.push(`THIRD-PARTY.md: no ${marker} block`);
    return [];
  }
  return m[1].split('\n').filter((l) => l.startsWith('| `'))
    .map((l) => l.split('|').slice(1, -1).map((c) => c.trim().replace(/^`|`$/g, '')));
}

const installed = new Map();
for (const [path, v] of Object.entries(lock.packages)) {
  if (path === '') continue;
  installed.set(`${path.replace(/^.*node_modules\//, '')}@${v.version}`, { license: v.license ?? '(none)', dev: v.dev === true });
}

const direct = new Map();
for (const [deps, kind] of [[pkg.dependencies, 'prod'], [pkg.devDependencies, 'dev']]) {
  for (const [name, version] of Object.entries(deps ?? {})) {
    if (!/^\d+\.\d+\.\d+$/.test(version)) failures.push(`${name}: version ${version} is not exact`);
    const lic = installed.get(`${name}@${version}`)?.license ?? '(not installed)';
    direct.set(name, `${version} ${lic} ${kind}`);
  }
}
const documented = new Map(table('licenses:direct').map(([name, version, lic, kind]) => [name, `${version} ${lic} ${kind}`]));
for (const [name, row] of direct) {
  if (documented.get(name) !== row) failures.push(`direct ${name}: package.json/lock says "${row}", THIRD-PARTY.md says "${documented.get(name) ?? '(missing)'}"`);
  documented.delete(name);
}
for (const stale of documented.keys()) failures.push(`direct ${stale}: in THIRD-PARTY.md but not a direct dependency`);

const exceptions = new Map(table('licenses:exceptions').map(([name, version, lic, reason]) => [`${name}@${version}`, { lic, reason }]));
for (const [id, p] of installed) {
  if (ALLOWED.has(p.license)) continue;
  const e = exceptions.get(id);
  if (!p.dev) failures.push(`${id}: runtime dependency with license ${p.license} outside the allow list`);
  else if (!e) failures.push(`${id}: license ${p.license} outside the allow list and not in the exceptions table`);
  else if (e.lic !== p.license || !e.reason) failures.push(`${id}: exceptions row does not match (${e.lic})`);
  exceptions.delete(id);
}
for (const stale of exceptions.keys()) failures.push(`exception ${stale}: no longer installed or now allowed — remove the row`);

if (failures.length > 0) {
  for (const f of failures) console.error(f);
  process.exit(1);
}
console.log(`licenses ok (${installed.size} packages, ${direct.size} direct)`);
