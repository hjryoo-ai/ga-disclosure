// 직원 화면 조작(키보드만 — keyboard.ts). 문구는 messages.ko.json에서 읽는다(시험에 화면 문구를 다시 쓰지 않는다).
import { expect, type Locator, type Page } from '@playwright/test';
import messages from '../../src/shared/messages.ko.json' with { type: 'json' };
import { press, tabTo } from './keyboard';

export const { staff, sign } = messages;

/** 데모 로그인: 버튼 → 새 창의 주체 선택 폼(라디오 화살표) → Sign in → 콜백 창이 닫히고 직원 화면이 로그인된다. */
export async function login(page: Page, account: string): Promise<void> {
  await page.goto('/staff');
  const popupPromise = page.waitForEvent('popup');
  await press(page, page.getByRole('button', { name: staff.login.button }));
  const popup = await popupPromise;
  await popup.waitForLoadState();
  const radios = popup.getByRole('radio');
  const target = popup.getByLabel(account.replace('/', ' · '), { exact: true });
  await expect(target).toBeVisible();
  for (let i = 0; i < 40 && !(await radios.first().evaluate((el) => el === document.activeElement)); i++) await popup.keyboard.press('Tab');
  for (let i = 0; i < 20 && !(await target.isChecked()); i++) await popup.keyboard.press('ArrowDown');
  await expect(target).toBeChecked();
  const closed = popup.waitForEvent('close');
  await press(popup, popup.getByRole('button', { name: 'Sign in' }));
  await closed;
  await expect(page.getByRole('navigation', { name: staff.nav.label })).toBeVisible();
}

export async function logout(page: Page): Promise<void> {
  await press(page, page.getByRole('button', { name: staff.app.logout }));
  await expect(page.getByRole('button', { name: staff.login.button })).toBeVisible();
}

export async function nav(page: Page, name: string): Promise<void> {
  await press(page, page.getByRole('navigation', { name: staff.nav.label }).getByRole('link', { name, exact: true }));
}

export const section = (page: Page, id: string): Locator => page.locator(`section[aria-labelledby="h-${id}"]`);

/** 결과 줄이 이 문구로 성공했는지(실패면 Problem 코드를 메시지에 — 코드는 개인정보가 아니다). */
export async function expectOk(scope: Locator, text: string): Promise<void> {
  const result = scope.locator('.result').last();
  await expect(result.locator('.ok, .problem').first()).toBeVisible();
  const problem = await result.locator('.problem code').allTextContents();
  expect(problem, 'server rejected the action').toEqual([]);
  await expect(result.locator('.ok')).toContainText(text);
}

/** 상세 화면의 상태 표기(서버 값의 라벨). */
export async function expectStatus(page: Page, code: keyof typeof staff.status): Promise<void> {
  await expect(page.getByTestId('status')).toContainText(staff.status[code]);
}

export { press, tabTo };
