// OSV 조회(수동, 네트워크): package-lock.json의 모든 패키지를 https://api.osv.dev/v1/querybatch 로 묻고 취약점 ID를 출력한다.
// 빌드에 넣지 않는다(네트워크·시점 의존) — 결과는 THIRD-PARTY.md의 OSV 절에 날짜와 함께 적는다(엔진 E3.2와 같은 방식).
import { readFile } from 'node:fs/promises';

const lock = JSON.parse(await readFile(new URL('../package-lock.json', import.meta.url), 'utf8'));
const pkgs = Object.entries(lock.packages)
  .filter(([path]) => path !== '')
  .map(([path, v]) => ({ name: path.replace(/^.*node_modules\//, ''), version: v.version, dev: v.dev === true }));
const queries = pkgs.map((p) => ({ package: { ecosystem: 'npm', name: p.name }, version: p.version }));
const hits = [];
for (let i = 0; i < queries.length; i += 500) {
  const res = await fetch('https://api.osv.dev/v1/querybatch', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ queries: queries.slice(i, i + 500) }),
  });
  if (!res.ok) throw new Error(`OSV ${res.status}`);
  const body = await res.json();
  body.results.forEach((r, j) => {
    if (r.vulns && r.vulns.length > 0) hits.push({ ...pkgs[i + j], ids: r.vulns.map((v) => v.id) });
  });
}
console.log(`packages queried: ${pkgs.length}`);
console.log(`packages with advisories: ${hits.length}`);
for (const h of hits) console.log(`${h.name}@${h.version} ${h.dev ? 'dev' : 'prod'} ${h.ids.join(',')}`);
