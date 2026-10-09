// 고객 공개 서명 화면 조작(/s#토큰): 열람(키보드 End로 끝까지) → 본인확인(원격 링크만 — 생년월일) → 서명 패드(마우스 — 지시문의 키보드 예외) → 완료.
// 원격 링크 토큰은 데모 통지 포트(콘솔)가 server.log에 쓰는 SIGN LINK 줄에서 받는다(하네스의 스케줄러 토큰으로 NOTIFY 작업을 접수).
import { expect, type APIRequestContext, type Page } from '@playwright/test';
import { readFileSync, statSync } from 'node:fs';
import { resolve } from 'node:path';
import { OUT, RUN } from './env';
import { press, tabTo, type } from './keyboard';
import { sign } from './staff';

export async function readDocument(page: Page): Promise<void> {
  const pages = page.locator('canvas.pdf-page');
  await expect(pages.first()).toBeVisible();
  const total = Number(/(\d+)\s*$/.exec((await pages.first().getAttribute('aria-label')) ?? '')?.[1] ?? '0');
  expect(total).toBeGreaterThan(0);
  await expect(pages).toHaveCount(total);
  await tabTo(page, page.locator('.pdf'));
  await page.keyboard.press('End');
  await page.waitForTimeout(700);                                          // 마지막 쪽 교차 관찰 + 열람 초가 0을 넘게
  await press(page, page.getByRole('button', { name: sign.view.done }));
  await expect(page.locator('.live')).toHaveText(sign.view.recorded);
}

export async function verifyBirthDate(page: Page, value: string, expected: 'passed' | 'failed'): Promise<void> {
  const input = page.locator('#birth-date');
  await type(page, input, value.replaceAll('-', ''));                       // 휴대폰 자판처럼 숫자 8자리
  await press(page, page.getByRole('button', { name: sign.identity.submit }));
  await expect(page.locator('.live')).toHaveText(sign.identity[expected]);
  await expect(input).toHaveValue('');                                     // 제출 즉시 비운다
}

/** 캔버스에 획 두 개(마우스 — 패드만 키보드 예외). */
export async function draw(page: Page, canvasSelector: string): Promise<void> {
  const canvas = page.locator(canvasSelector);
  await canvas.scrollIntoViewIfNeeded();
  const box = await canvas.boundingBox();
  if (box === null) throw new Error('pad not visible');
  for (const [x0, y0, x1, y1] of [[0.1, 0.3, 0.6, 0.6], [0.3, 0.7, 0.8, 0.2]] as const) {
    await page.mouse.move(box.x + box.width * x0, box.y + box.height * y0);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width * x1, box.y + box.height * y1, { steps: 10 });
    await page.mouse.up();
  }
}

export async function signAndFinish(page: Page): Promise<void> {
  await draw(page, 'canvas.pad');
  await press(page, page.getByRole('button', { name: sign.pad.submit }));
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(sign.done.heading);
}

export async function expectUnavailable(page: Page): Promise<void> {
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(sign.problem.SIGN_LINK_UNAVAILABLE);
  await expect(page.locator('#root > *')).toHaveCount(1);                 // 사유를 나누는 다른 요소 없음
}

/** 통지 작업을 돌려 새로 쓰인 SIGN LINK 줄의 링크를 받는다. */
export async function remoteLink(request: APIRequestContext): Promise<string> {
  const log = resolve(OUT, 'server.log');
  const offset = statSync(log).size;
  const jwt = readFileSync(resolve(RUN, 'scheduler.jwt'), 'utf8').trim();
  const r = await request.post('/internal/v1/jobs/NOTIFY', {
    headers: { Authorization: `Bearer ${jwt}`, 'Idempotency-Key': `e2e-notify-${Date.now()}-${Math.random().toString(36).slice(2, 10)}` },
  });
  expect(r.status()).toBe(202);
  for (let i = 0; i < 60; i++) {
    const tail = readFileSync(log, 'utf8').slice(offset);
    const m = [...tail.matchAll(/^SIGN LINK (\S+)$/gm)].pop();
    if (m?.[1] !== undefined) return m[1];
    await new Promise((res) => setTimeout(res, 500));
  }
  throw new Error('no SIGN LINK line after NOTIFY');
}

/** 링크 열기: 같은 문서의 조각만 바뀌면 다시 읽지 않으므로 빈 문서를 거친다. */
export async function openLink(page: Page, link: string): Promise<void> {
  await page.goto('about:blank');
  await page.goto(link);
}

export const tokenOf = (link: string): string => link.slice(link.indexOf('#') + 1);
