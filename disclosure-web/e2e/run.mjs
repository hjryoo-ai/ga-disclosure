// E2E 실행(./gradlew :disclosure-web:e2e): 허구 센티널 생성 → 환경(env.mjs up) → playwright test → 산출물 센티널 스캔(Phase 8 — build/e2e 전체, 보고서
// 내장 zip 포함; 있으면 실패) → 환경 내림(down) → 실행 디렉터리(키·토큰·재전송 기록) 삭제.
// 센티널은 실행마다 무작위 허구 값이고 파일(build/e2e/run/sentinels.json, 소유자 전용)로만 시험에 간다 — CLI 인자·환경변수에 없다(절대 규칙 6).
// 내림은 실패해도 한다. 남긴 컨테이너가 있으면 출력한다(6B D-8).
import { spawnSync } from 'node:child_process';
import { randomInt } from 'node:crypto';
import { rmSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { scan } from './artifact-scan.mjs';
import { down, up } from './env.mjs';

const web = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const run = resolve(web, 'build/e2e/run');

function fictional() {
  const syllables = [...'가나다라마바사아자차카타파하거너더러머버서어저처커터퍼허고노도로모보소오조초코토포호'];
  const pick = () => syllables[randomInt(syllables.length)];
  const digits = (n) => Array.from({ length: n }, () => String(randomInt(10))).join('');
  const day = new Date(Date.UTC(1950, 0, 1) + randomInt(0, 50 * 365) * 86_400_000).toISOString().slice(0, 10);
  return { name: `가상${pick()}${pick()}${pick()}`, phone: `010-${digits(4)}-${digits(4)}`, birthDate: day };
}

let status = 1;
try {
  if (process.env.GA_E2E_DEMO === '1') rmSync(resolve(web, 'build/demo/phase7'), { recursive: true, force: true });
  await up();
  const sentinels = fictional();
  writeFileSync(resolve(run, 'sentinels.json'), JSON.stringify(sentinels), { mode: 0o600 });
  const r = spawnSync('npx', ['playwright', 'test', ...process.argv.slice(2)], { cwd: web, stdio: 'inherit' });
  status = r.status ?? 1;
  // 실패 산출물(error-context.md·보고서)에 입력값이 남지 않았는지 — 시험 결과와 별개로 판정한다
  const found = scan(resolve(web, 'build/e2e'), run, sentinels);
  console.log(`E2E ARTIFACT SCAN files=${String(found.scanned)} hits=${String(found.hits.length)}`);
  if (found.hits.length > 0) {
    console.error(`sentinels in run artifacts: ${found.hits.map((h) => `#${String(h.sentinel)} ${h.file}`).join(', ')}`);
    status = status || 4;
  }
} catch (e) {
  console.error(String(e));
} finally {
  const left = down();
  rmSync(run, { recursive: true, force: true });
  console.log(left.length === 0 ? 'E2E DOWN (containers removed)' : `E2E DOWN — left behind: ${left.join(', ')}`);
  if (left.length > 0) status = status || 3;
}
process.exit(status);
