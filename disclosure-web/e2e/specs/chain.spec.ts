// 준법 CHAIN_BROKEN 흐름(지시문 2절 — 해소 버튼이 아니라 "검증 실행 → 일치 확인 → 해소"): 하네스가 DEMO2 감사 행을 잠깐 바꿔 만든 사건.
// 검증 작업을 화면에서 접수·폴링하고 보고서를 본 뒤 그 작업 ID를 해소 증거로 넣는다. 사건이 하나라 데스크톱 프로젝트만(설정의 testIgnore).
import { axe } from '../support/axe';
import { expect, test } from '../support/fixtures';
import { choose, type } from '../support/keyboard';
import { expectOk, login, nav, press, staff } from '../support/staff';

test('compliance resolves CHAIN_BROKEN with a verify run as evidence', async ({ page, guard }, info) => {
  await login(page, 'DEMO2/demo-compliance');
  await nav(page, staff.nav.flags);
  await choose(page, page.locator('#f-status'), staff.flagStatus.OPEN);
  await type(page, page.locator('#f-type'), 'CHAIN_BROKEN');
  await press(page, page.getByRole('button', { name: staff.common.apply }));
  const row = page.locator('tr[data-flag-id]').filter({ hasText: 'CHAIN_BROKEN' });
  await expect(row).toHaveCount(1);
  const flagId = (await row.getAttribute('data-flag-id')) ?? '';

  // 증거 없이 해소하면 서버가 거부한다(룰 requiresEvidence) — 화면은 코드를 보인다
  await type(page, row.getByLabel(staff.flags.resolutionCode), 'VERIFIED_MATCH');
  await press(page, row.getByRole('button', { name: staff.flags.resolve }));
  await expect(page.locator('main .result .problem code').first()).toBeVisible();

  await press(page, page.getByRole('button', { name: staff.flags.verifyRun }));
  const job = page.getByTestId('job');
  await expect(job.getByTestId('job-status')).toHaveText(staff.jobStatus.SUCCEEDED, { timeout: 90_000 });
  await expect(job.locator('pre.report')).toBeVisible();
  await guard.shot(page, 'chain-broken-verify-report');
  await axe(page, 'flags-verify', info);
  const jobId = (await job.getAttribute('data-job-id')) ?? '';
  expect(jobId).toMatch(/^[0-9a-f-]{36}$/);

  const target = page.locator(`tr[data-flag-id="${flagId}"]`);
  await type(page, target.getByLabel(staff.flags.resolutionCode), 'VERIFIED_MATCH');
  await type(page, target.getByLabel(staff.flags.verifyRunJobId), jobId);
  await press(page, target.getByRole('button', { name: staff.flags.resolve }));
  await expectOk(page.locator('main'), staff.flags.resolved);
  await expect(target).toHaveCount(0);                                    // 열린 플래그 필터에서 빠졌다
});
