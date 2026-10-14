// 키보드만(G10): 대상에 Tab으로 닿고(닿지 못하면 실패) Enter·Space·문자 입력으로 조작한다. 마우스·focus() 호출 없음 — 서명 패드만 예외(지시문).
import { expect, type Locator, type Page } from '@playwright/test';

const isFocused = (l: Locator) => l.evaluate((el) => el === document.activeElement).catch(() => false);

/** Tab(필요하면 Shift+Tab)을 눌러 대상에 초점을 옮긴다. */
export async function tabTo(page: Page, target: Locator, max = 300): Promise<void> {
  await expect(target).toBeVisible();
  for (let i = 0; i < max; i++) {
    if (await isFocused(target)) return;
    await page.keyboard.press('Tab');
  }
  throw new Error(`not reachable with Tab: ${target.toString()}`);
}

export async function press(page: Page, target: Locator): Promise<void> {
  await tabTo(page, target);
  await page.keyboard.press('Enter');
}

export async function toggle(page: Page, target: Locator): Promise<void> {
  await tabTo(page, target);
  await page.keyboard.press('Space');
}

/** 칸을 비우고 입력한다(키 입력만). 날짜 칸은 Chromium의 세그먼트 입력(숫자만)으로 쓴다. */
export async function type(page: Page, target: Locator, text: string): Promise<void> {
  await tabTo(page, target);
  const kind = await target.getAttribute('type');
  if (kind === 'date') {
    // 세그먼트 순서는 로캘을 따른다(ko-KR: 연·월·일). 연도 칸은 6자리까지 받으므로 세그먼트마다 → 로 넘어간다
    const parts = text.split('-');
    for (const [i, part] of parts.entries()) {
      await page.keyboard.type(part);
      if (i < parts.length - 1) await page.keyboard.press('ArrowRight');
    }
  } else {
    await page.keyboard.press('ControlOrMeta+A');
    await page.keyboard.press('Delete');
    await page.keyboard.type(text);
  }
}

/**
 * 라디오 묶음(Choice): Tab은 묶음의 선택된 칸에 닿고, ↓ 로 다음 칸을 고른다(끝에서 처음으로 돈다). 아무 칸도 선택되지 않은 묶음(룰 어휘 — 기본 선택
 * 없음, Phase 8)은 Tab이 첫 칸에 닿고 Space가 그 칸을 고른다.
 */
export async function choose(page: Page, group: Locator, optionLabel: string): Promise<void> {
  await chooseIn(page, group, group.getByLabel(optionLabel, { exact: true }));
}

/** 같은 묶음에서 값(코드)으로 고른다 — 표기가 룰 데이터인 어휘 선택지(시험에 표기 리터럴을 두지 않는다). */
export async function chooseValue(page: Page, group: Locator, value: string): Promise<void> {
  await chooseIn(page, group, group.locator(`input[type="radio"][value="${value}"]`));
}

async function chooseIn(page: Page, group: Locator, target: Locator): Promise<void> {
  const radios = group.locator('input[type="radio"]');
  await expect(radios.first()).toBeVisible();          // 묶음이 그려진 뒤(어휘 선택지는 어휘를 읽은 뒤) 센다
  const count = await radios.count();
  if ((await group.locator('input[type="radio"]:checked').count()) === 0) {
    await tabTo(page, radios.first());
    await page.keyboard.press('Space');
  } else {
    await tabTo(page, group.locator('input[type="radio"]:checked'));
  }
  for (let i = 0; i <= count && !(await target.isChecked()); i++) await page.keyboard.press('ArrowDown');
  await expect(target).toBeChecked();
}

/** 선택 상자(값이 코드인 긴 목록 — 작업 종류): 초점을 옮긴 뒤 코드를 입력해 고른다(Chromium 닫힌 select의 입력 탐색, ASCII). */
export async function chooseCode(page: Page, select: Locator, code: string): Promise<void> {
  await expect(select.locator(`option[value="${code}"]`)).toHaveCount(1);     // 선택지가 그려진 뒤(어휘 선택지는 어휘를 읽은 뒤) 입력한다
  await tabTo(page, select);
  await page.keyboard.type(code);
  await expect(select).toHaveValue(code);
}
