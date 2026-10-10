// 모든 E2E 시험에 자동으로 붙는 감시(G4·G5·G9):
//  - CSP 위반 0: securitypolicyviolation 이벤트(초기화 스크립트가 표식 줄로 콘솔에 올림)와 Chromium의 CSP 콘솔 오류
//  - 외부 출처 요청 0: 모든 요청의 출처 = 앱 출처(blob:·data:만 예외)
//  - 누출 0(WebLeakScanE2E): 센티널(허구 이름·전화·생년월일 + 이 시험이 만난 서명 토큰)이 콘솔·페이지 오류·요청 URL·요청/응답 헤더·
//    페이지 URL·localStorage·sessionStorage·쿠키·IndexedDB 목록에 없다. 토큰은 `X-Sign-Token` 요청 헤더에만 있어도 된다(설계된 전달 경로).
//    먼저 "센티널이 실제로 들어갔다"(요청 본문에 실린 횟수)를 센다(D-5). 실패 메시지는 센티널 번호·위치 종류만(D-6).
//  - 재전송 기록(G11): 멱등 키가 붙은 /api/v1 쓰기와 그 응답(상태·본문 해시)을 build/e2e/run/writes/에 남긴다 — after 프로젝트가 같은 키로 다시 보낸다.
import { test as base, expect, type BrowserContext, type Page, type Request } from '@playwright/test';
import { createHash } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { DEMO_SHOTS, env, OUT, RUN, sentinelForms, sentinels, type Sentinels } from './env';

export interface WriteRecord {
  url: string;
  headers: Record<string, string>;
  body: string | null;
  status: number;
  bodySha256: string;
}

interface Watch {
  consoleTexts: string[];
  urls: string[];
  headerSets: { name: string; value: string }[][];
  violations: string[];
  external: string[];
  writes: WriteRecord[];
  enteredInBodies: number;
  pages: Page[];
  lost: number;
}

export interface Guard {
  /** 이 시험이 만난 서명 토큰(누출 스캔 대상에 더한다). */
  addSecret(secret: string): void;
  /** e2e-demo(GA_E2E_DEMO=1)에서만 화면을 build/demo/phase7/에 남긴다. 이름은 고정 문자열(센티널 0 — after가 파일 이름을 본다). */
  shot(page: Page, name: string): Promise<void>;
  /** 창을 닫기 전에 그 창의 저장소를 읽어 둔다(닫힌 창은 읽을 수 없다). */
  close(page: Page): Promise<void>;
  sentinels: Sentinels;
}

const slug = (s: string) => s.replace(/[^A-Za-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 80);

async function storageOf(page: Page): Promise<string[]> {
  if (page.isClosed() || !page.url().startsWith('http')) return [];
  try {
    return await page.evaluate(async () => {
      const out: string[] = [];
      for (const s of [localStorage, sessionStorage]) {
        for (let i = 0; i < s.length; i++) {
          const k = s.key(i) ?? '';
          out.push(`${k}=${s.getItem(k) ?? ''}`);
        }
      }
      out.push(`cookie=${document.cookie}`);
      const dbs = await indexedDB.databases();
      for (const d of dbs) out.push(`idb=${d.name ?? ''}`);
      return out;
    });
  } catch {
    return [];                                                             // 닫히는 중인 창
  }
}

function attach(context: BrowserContext, w: Watch, origin: string) {
  const onPage = (page: Page) => {
    w.pages.push(page);
    page.on('console', (m) => {
      const text = `${m.text()} @ ${m.location().url}`;
      w.consoleTexts.push(text);
      if (m.text().startsWith('CSP-VIOLATION') || /Content Security Policy/i.test(m.text())) w.violations.push(m.text().slice(0, 200));
    });
    page.on('pageerror', (e) => { w.consoleTexts.push(String(e)); });
    page.on('framenavigated', (f) => { if (f === page.mainFrame()) w.urls.push(f.url().replace(/#.*$/, '#<fragment>')); });
  };
  context.pages().forEach(onPage);
  context.on('page', onPage);
  context.on('request', (r: Request) => {
    const url = r.url();
    w.urls.push(url);
    if (!url.startsWith('blob:') && !url.startsWith('data:') && new URL(url).origin !== origin) w.external.push(new URL(url).origin);
    r.allHeaders().then((h) => { w.headerSets.push(Object.entries(h).map(([name, value]) => ({ name, value }))); }, () => { w.lost++; });
  });
  context.on('requestfinished', (r: Request) => {
    void (async () => {
      const response = await r.response();
      if (response === null) return;
      w.headerSets.push(Object.entries(await response.allHeaders()).map(([name, value]) => ({ name, value })));
      const h = await r.allHeaders();
      const path = new URL(r.url()).pathname;
      if (r.method() === 'POST' && path.startsWith('/api/v1/') && h['idempotency-key'] !== undefined) {
        const body = await response.body().catch(() => Buffer.alloc(0));
        w.writes.push({
          url: path,
          headers: { authorization: h['authorization'] ?? '', 'idempotency-key': h['idempotency-key'], 'content-type': h['content-type'] ?? 'application/json' },
          body: r.postData(),
          status: response.status(),
          bodySha256: createHash('sha256').update(body).digest('hex'),
        });
      }
    })().catch(() => { w.lost++; });                                       // 창이 닫혀 응답을 못 읽음 — 센다
  });
}

export const test = base.extend<{ guard: Guard }>({
  guard: [async ({ context }, use, info) => {
    const e = env();
    const s = sentinels();
    const origin = new URL(e.baseUrl).origin;
    const secrets: string[] = [];
    const w: Watch = { consoleTexts: [], urls: [], headerSets: [], violations: [], external: [], writes: [], enteredInBodies: 0, pages: [], lost: 0 };
    await context.addInitScript(() => {
      document.addEventListener('securitypolicyviolation', (ev) => {
        console.log(`CSP-VIOLATION ${ev.violatedDirective} ${ev.blockedURI}`);
      });
    });
    context.on('request', (r) => {
      const body = r.postData() ?? '';
      for (const f of sentinelForms(s)) if (body.includes(f)) w.enteredInBodies++;
    });
    attach(context, w, origin);

    const closedStorage: string[] = [];
    let shots = 0;
    await use({
      addSecret: (x) => { secrets.push(x); },
      close: async (p) => { closedStorage.push(...await storageOf(p)); await p.close(); },
      shot: async (p, shotName) => {
        if (process.env['GA_E2E_DEMO'] !== '1') return;
        shots++;
        await p.screenshot({ path: resolve(DEMO_SHOTS, `${info.project.name}-${slug(info.title).slice(0, 24)}-${String(shots).padStart(2, '0')}-${shotName}.png`), fullPage: true });
      },
      sentinels: s,
    });

    // 실패한 시험: Playwright가 컨텍스트를 닫으며 error-context.md에 쓰는 페이지 스냅샷보다 먼저(이 고정 장치는 context에 의존하므로 정리가 먼저 돈다)
    // 입력 칸 값을 지운다 — 시험이 넣은 허구 개인정보가 실패 산출물에 남지 않게(Phase 8 계획 ③-x-4). 남은 것은 run.mjs 산출물 스캔이 잡는다.
    if (info.status !== info.expectedStatus) {
      for (const p of w.pages) {
        if (p.isClosed()) continue;
        await p.evaluate(() => {
          for (const el of document.querySelectorAll('input, textarea')) {
            if (el instanceof HTMLInputElement && ['checkbox', 'radio', 'file', 'submit', 'button', 'hidden'].includes(el.type)) continue;
            (el as HTMLInputElement | HTMLTextAreaElement).value = '';
          }
        }).catch(() => undefined);
      }
    }
    const storage = [...closedStorage, ...(await Promise.all(w.pages.map(storageOf))).flat()];
    const cookies = (await context.cookies()).map((c) => `${c.name}=${c.value}`);
    await new Promise((r) => setTimeout(r, 300));                          // 마지막 응답 헤더 수집
    const forms = [...sentinelForms(s), ...secrets];
    const where: Record<string, string[]> = {
      console: w.consoleTexts,
      url: w.urls,
      storage: [...storage, ...cookies],
      header: w.headerSets.flatMap((set) => set.filter((h) => h.name !== 'x-sign-token' || !secrets.includes(h.value)).map((h) => `${h.name}: ${h.value}`)),
    };
    const hits: string[] = [];
    forms.forEach((f, i) => {
      for (const [kind, texts] of Object.entries(where)) {
        if (texts.some((t) => t.includes(f))) hits.push(`sentinel #${i} in ${kind}`);
      }
    });

    const dir = resolve(OUT, 'leak-scan');
    mkdirSync(dir, { recursive: true });
    const name = `${info.project.name}-${slug(info.title)}`;
    writeFileSync(resolve(dir, `${name}.json`), JSON.stringify({
      test: info.title, project: info.project.name, sentinels: forms.length, enteredInBodies: w.enteredInBodies, hits: hits.length,
      scanned: Object.fromEntries(Object.entries(where).map(([k, v]) => [k, v.length])), cspViolations: w.violations.length, externalRequests: w.external.length,
    }, null, 2));
    const writesDir = resolve(RUN, 'writes');
    mkdirSync(writesDir, { recursive: true, mode: 0o700 });
    writeFileSync(resolve(writesDir, `${name}.json`), JSON.stringify(w.writes), { mode: 0o600 });

    expect(hits, 'browser-side leak (sentinel numbers only)').toEqual([]);
    expect(w.violations, 'CSP violations').toEqual([]);
    expect([...new Set(w.external)], 'requests to other origins').toEqual([]);
  }, { auto: true }],
});

export { expect };
