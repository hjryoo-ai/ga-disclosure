// desktop·mobile이 끝난 뒤(after 프로젝트):
//  ① G11 2회 실행 NOOP — 화면 흐름의 멱등 쓰기(완료로 저장되는 2xx·409·422)를 같은 키·같은 본문으로 다시 보낸다: 응답은 재생(`Idempotency-Replayed: true`,
//     같은 상태·같은 본문 해시)이거나, 현장 기기 토큰을 담은 세션 발급이면 409 IDEMPOTENCY_NOT_REPLAYABLE(일회용 자격은 저장하지 않는다 — 설계서 §7).
//     전후 테이블별 행 수가 같다(슈퍼유저 psql — RLS 밖에서 전부 센다).
//  ② G5 누출 합계 — 시험마다의 스캔 결과를 모아 적중 0, 프로젝트마다 센티널이 실제로 입력됐음(요청 본문 횟수 > 0), 산출 파일·데모 스크린샷 이름에 센티널 0,
//     앱 로그(server.log)에 개인정보 센티널 0.
//  ③ G10 — axe 예외 목록의 항목이 한 번 이상 쓰였다(안 쓰인 예외는 지운다).
import { expect, test } from '@playwright/test';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { exceptions } from '../support/axe';
import { DEMO_SHOTS, env, OUT, RUN, sentinelForms, sentinels } from '../support/env';
import type { WriteRecord } from '../support/fixtures';

const readAll = <T>(dir: string): T[] => readdirSync(dir).filter((f) => f.endsWith('.json')).map((f) => JSON.parse(readFileSync(resolve(dir, f), 'utf8')) as T);

function rowCounts(): Record<string, number> {
  const psql = (sql: string) => {
    const r = spawnSync('docker', ['exec', '-i', env().pg, 'psql', '-q', '-tA', '-U', 'postgres', '-d', 'disclosure', '-v', 'ON_ERROR_STOP=1'],
      { input: sql, encoding: 'utf8' });
    if (r.status !== 0) throw new Error(`psql failed: ${r.stderr.slice(0, 500)}`);
    return r.stdout.trim();
  };
  const tables = psql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE' ORDER BY 1;").split('\n');
  expect(tables.length).toBeGreaterThan(20);
  const counts = psql(tables.map((t) => `SELECT '${t}', count(*) FROM public."${t}"`).join(' UNION ALL ') + ';');
  return Object.fromEntries(counts.split('\n').map((l) => { const [t, n] = l.split('|'); return [t ?? '', Number(n)]; }));
}

test('replaying every write of the screen flows changes nothing on the server (G11)', async ({ request }) => {
  const writes = readAll<WriteRecord[]>(resolve(RUN, 'writes')).flat().filter((w) => (w.status >= 200 && w.status < 300) || w.status === 409 || w.status === 422);
  expect(writes.length).toBeGreaterThan(40);
  const before = rowCounts();
  let replayed = 0;
  let notReplayable = 0;
  for (const w of writes) {
    const r = await request.post(`${env().baseUrl}${w.url}`, { headers: w.headers, ...(w.body === null ? {} : { data: w.body }) });
    const body = await r.body();
    if (r.status() === 409 && w.url.endsWith('/sign-sessions') && (JSON.parse(body.toString('utf8')) as { code?: string }).code === 'IDEMPOTENCY_NOT_REPLAYABLE') {
      notReplayable++;
      continue;
    }
    expect(r.headers()['idempotency-replayed'], w.url.replace(/[0-9a-f-]{36}/g, '{id}')).toBe('true');
    expect(r.status()).toBe(w.status);
    expect(createHash('sha256').update(body).digest('hex')).toBe(w.bodySha256);
    replayed++;
  }
  const after = rowCounts();
  expect(after).toEqual(before);
  writeFileSync(resolve(OUT, 'replay-summary.json'), JSON.stringify({ writes: writes.length, replayed, notReplayable, tables: Object.keys(before).length,
    rows: Object.values(before).reduce((a, b) => a + b, 0) }, null, 2));
  expect(notReplayable).toBeGreaterThan(0);                                // 현장 기기 세션 발급이 흐름에 있다
});

test('browser-side leak scan found nothing, after the sentinels were really entered (G5)', () => {
  interface Scan { project: string; hits: number; enteredInBodies: number; cspViolations: number; externalRequests: number; scanned: Record<string, number> }
  const scans = readAll<Scan>(resolve(OUT, 'leak-scan'));
  expect(scans.length).toBeGreaterThanOrEqual(7);
  for (const project of ['desktop', 'mobile']) {
    const mine = scans.filter((s) => s.project === project);
    expect(mine.reduce((a, s) => a + s.enteredInBodies, 0), project).toBeGreaterThan(0);
  }
  expect(scans.reduce((a, s) => a + s.hits + s.cspViolations + s.externalRequests, 0)).toBe(0);
  const walk = (dir: string): string[] => readdirSync(dir, { withFileTypes: true })
    .flatMap((e) => (e.isDirectory() ? [e.name, ...walk(join(dir, e.name))] : [e.name]));
  const names = [...walk(OUT), ...(existsSync(DEMO_SHOTS) ? walk(DEMO_SHOTS) : [])];
  const forms = sentinelForms(sentinels());
  expect(forms.map((f, i) => (names.some((n) => n.includes(f)) ? i : -1)).filter((i) => i >= 0), 'sentinel numbers in file names').toEqual([]);
  // 서버 쪽 보충(서버 평문 스캔은 6A·6B 시험 그대로 돈다): 이번 실행의 앱 로그에 허구 개인정보 0 — 데모 통지 포트의 SIGN LINK 줄(토큰)은 설계된 데모 출력
  const serverLog = readFileSync(resolve(OUT, 'server.log'), 'utf8');
  expect(forms.map((f, i) => (serverLog.includes(f) ? i : -1)).filter((i) => i >= 0), 'sentinel numbers in server.log').toEqual([]);
  writeFileSync(resolve(OUT, 'leak-scan-summary.json'), JSON.stringify({
    tests: scans.length, hits: 0, files: names.length,
    scanned: scans.reduce<Record<string, number>>((a, s) => { for (const [k, v] of Object.entries(s.scanned)) a[k] = (a[k] ?? 0) + v; return a; }, {}),
    enteredInBodies: scans.reduce((a, s) => a + s.enteredInBodies, 0),
  }, null, 2));
});

test('every axe exception was used (G10)', () => {
  const runs = readAll<{ used: string[]; screen: string }>(resolve(OUT, 'axe'));
  expect(new Set(runs.map((r) => r.screen)).size).toBeGreaterThanOrEqual(10);
  const used = new Set(runs.flatMap((r) => r.used));
  expect(exceptions().map((e) => `${e.rule} @ ${e.target}`).filter((k) => !used.has(k)), 'unused axe exceptions').toEqual([]);
});
