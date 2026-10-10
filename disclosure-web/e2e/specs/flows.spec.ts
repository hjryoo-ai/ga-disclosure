// 역할별 흐름(G11)·플래그 가시성(G8)·DOM 대조·접근성(G10)·키보드(G10)·공개 서명(G4)·거부 화면(G4) — 한 고객으로 이어지는 직렬 시험.
// ① 설계사 현장(TOUCH_PAD): 고객 등록 → 초안 → 비교·등급·사유 → 검증 → 봉인 → 대면 확인 → 고객 서명 창 → 설계사 서명 → 관리자 확인 = 완료
// ② 원격 링크(REMOTE_LINK): 생년월일 5회 불일치 → 세션 폐기 + IDENTITY_FAILED(설계사에게 안 보임, 관리자에게 보임) → 재발급 → 고객 서명 →
//    설계사 서명 → 관리자가 그 플래그를 체크해 확인(acknowledgedFlags) = 완료
// ③ 거부 화면: 쓰인 토큰·폐기된 토큰·틀린 토큰·쿼리 토큰 → 같은 한 화면, 공개 페이지 헤더
// ④ 준법: 큐·배정·해소, 보류 걸기·해제(4-eyes — 건 사람의 해제는 거부), 보존 재계산 dry-run, 징구율 정의 문구
// 버튼·입력은 키보드만(keyboard.ts) — 서명 패드만 마우스.
import type { Page } from '@playwright/test';
import { axe } from '../support/axe';
import { expect, test } from '../support/fixtures';
import { choose, chooseCode, toggle, type } from '../support/keyboard';
import { expectUnavailable, openLink, readDocument, remoteLink, signAndFinish, tokenOf, verifyBirthDate, draw } from '../support/sign';
import { expectOk, expectStatus, login, logout, nav, press, section, staff } from '../support/staff';

const GROUP = 'PG-HEALTH-SIMPLE-NR';
const PRODUCTS = ['INS-A:PRD-1001', 'INS-B:PRD-2044', 'INS-C:PRD-3120'];
const RULE_VERSION = 'DISC-2026-07';

const shared: { customerRef?: string; first?: { id: string; no: string }; usedLink?: string; revokedLink?: string } = {};

test.describe.configure({ mode: 'serial' });

/** 초안 → 봉인(설계사 화면). 상세 화면에서 끝나고 확인서 ID·번호를 돌려준다. */
async function draftToSeal(page: Page, customerRef: string, checkLabels: boolean): Promise<{ id: string; no: string }> {
  await nav(page, staff.nav.newDisclosure);
  const form = page.locator('form').filter({ has: page.locator('#d-group') });
  if ((await page.locator('#d-customer').inputValue()) !== customerRef) await type(page, page.locator('#d-customer'), customerRef);
  await type(page, form.locator('#d-group'), GROUP);
  const template = page.waitForResponse((r) => r.url().endsWith('/template') && r.status() === 200);
  await press(page, page.getByRole('button', { name: staff.create.submit }));
  await expect(page).toHaveURL(/\/staff\/disclosures\/[0-9a-f-]{36}$/);
  const id = /([0-9a-f-]{36})$/.exec(page.url())?.[1] ?? '';
  const labels = (await (await template).json()) as { fields: { code: string; label: string; order: number }[] };
  await expectStatus(page, 'DRAFT');

  const items = section(page, 'items');
  await press(page, items.getByRole('button', { name: staff.items.search }));
  for (const key of PRODUCTS) await press(page, items.getByRole('button', { name: `${staff.items.add}: ${key}` }));
  for (const key of [PRODUCTS[0], PRODUCTS[2]]) {
    await toggle(page, items.locator('li').filter({ has: page.locator('code', { hasText: key ?? '' }) }).getByLabel(staff.items.recommended));
  }
  await press(page, items.getByRole('button', { name: staff.items.save }));
  await expectOk(items, staff.items.saved);

  const steps = section(page, 'steps');
  await press(page, steps.getByRole('button', { name: staff.steps.compare }));
  await expectOk(steps, staff.steps.compared);
  await press(page, steps.getByRole('button', { name: staff.steps.grades }));
  await expectOk(steps, staff.steps.graded);
  await expectStatus(page, 'GRADED');

  const reasons = section(page, 'reasons');
  await type(page, reasons.locator('#codes-1'), 'PREMIUM');
  await type(page, reasons.locator('#codes-3'), 'COVERAGE');
  await press(page, reasons.getByRole('button', { name: staff.reasons.save }));
  await expectOk(reasons, staff.reasons.saved);
  await expectStatus(page, 'REASONED');

  await choose(page, steps.locator('#v-stage'), staff.stage.SEAL);
  await press(page, steps.getByRole('button', { name: staff.steps.validate }));
  await expectOk(steps, staff.steps.validated);
  await expect(steps.getByRole('table')).toBeVisible();
  await press(page, steps.getByRole('button', { name: staff.steps.seal }));
  await expectOk(steps, staff.steps.sealed);
  await expectStatus(page, 'SEALED');

  if (checkLabels) {
    // DOM 대조: 라벨 노드 = 고정 서식 응답(getDisclosureTemplate)의 라벨, 비교표 열 순서 = 서식 항목 순서
    const byCode = new Map(labels.fields.map((f) => [f.code, f.label]));
    const nodes = page.locator('[data-field-code]');
    expect(await nodes.count()).toBeGreaterThan(4);
    for (const node of await nodes.all()) {
      const code = (await node.getAttribute('data-field-code')) ?? '';
      expect(byCode.has(code), code).toBe(true);
      await expect(node).toHaveText(byCode.get(code) ?? '');
    }
    const columns = await page.locator('th[data-field-code]').evaluateAll((ths) => ths.map((t) => t.getAttribute('data-field-code')));
    const order = [...labels.fields].sort((a, b) => a.order - b.order).map((f) => f.code).filter((c) => columns.includes(c));
    expect(columns).toEqual(order);
  }
  const no = (await page.locator('dt[data-field-code="DISCLOSURE_NO"] + dd').textContent()) ?? '';
  expect(no).toMatch(/^DEMO1-/);
  return { id, no };
}

async function openFromList(page: Page, no: string): Promise<void> {
  await nav(page, staff.nav.disclosures);
  await press(page, page.getByRole('link', { name: no, exact: true }));
  await expect(page.locator('dt[data-field-code="DISCLOSURE_NO"] + dd')).toHaveText(no);
}

async function issue(page: Page, channel: 'TOUCH_PAD' | 'REMOTE_LINK') {
  const signing = section(page, 'signing');
  await choose(page, signing.locator('#s-channel'), staff.channel[channel]);
  const response = page.waitForResponse((r) => r.url().endsWith('/sign-sessions') && r.request().method() === 'POST');
  await press(page, signing.getByRole('button', { name: staff.signing.issue }));
  const body = (await (await response).json()) as { deviceToken: string | null };
  await expectOk(signing, staff.signing.issued);
  return body.deviceToken;
}

async function agentSigns(page: Page): Promise<void> {
  const signing = section(page, 'signing');
  await draw(page, `canvas[aria-label="${staff.signing.padLabel}"]`);
  await press(page, signing.getByRole('button', { name: staff.signing.agentSign }));
  await expectOk(signing, staff.signing.signed);
}

test('agent on site with TOUCH_PAD, customer signs in the sign window, manager confirms', async ({ page, context, guard }, info) => {
  const s = guard.sentinels;
  await login(page, 'DEMO1/demo-agent');
  await guard.shot(page, 'agent-list');
  await axe(page, 'staff-list', info);

  await nav(page, staff.nav.registerCustomer);
  await axe(page, 'customer-register', info);
  await type(page, page.locator('#c-name'), s.name);
  await type(page, page.locator('#c-phone'), s.phone);
  await type(page, page.locator('#c-birth'), s.birthDate);
  await press(page, page.getByRole('button', { name: staff.customer.submit }));
  await expectOk(page.locator('main'), staff.customer.registered);
  await expect(page.locator('#c-name')).toHaveValue('');                   // 제출 즉시 비운다
  await expect(page.locator('#c-phone')).toHaveValue('');
  await expect(page.locator('#c-birth')).toHaveValue('');
  const customerRef = (await page.getByTestId('customer-ref').textContent()) ?? '';
  expect(customerRef).not.toBe('');
  shared.customerRef = customerRef;
  await guard.shot(page, 'customer-registered');
  await press(page, page.getByRole('link', { name: staff.customer.createWith }));
  await expect(page.locator('#d-customer')).toHaveValue(customerRef);
  await axe(page, 'create-disclosure', info);

  const first = await draftToSeal(page, customerRef, true);
  shared.first = first;
  await guard.shot(page, 'sealed-detail');
  await axe(page, 'disclosure-detail', info);

  const documents = section(page, 'documents');
  const preview = page.waitForResponse((r) => r.url().endsWith('/preview.pdf'));
  await press(page, documents.getByRole('button', { name: staff.documents.preview }));
  expect((await preview).headers()['content-type']).toContain('application/pdf');
  await expectOk(documents, staff.documents.previewOpened);

  const token = await issue(page, 'TOUCH_PAD');
  expect(token).not.toBeNull();
  guard.addSecret(token ?? '');
  const signing = section(page, 'signing');
  await press(page, signing.getByRole('button', { name: staff.signing.faceToFace }));
  await expectOk(signing, staff.signing.confirmed);
  await guard.shot(page, 'touch-pad-issued');

  const customerPagePromise = context.waitForEvent('page', (p) => p.url().includes('/s'));
  await press(page, signing.getByRole('button', { name: staff.signing.openSignWindow }));
  const customer = await customerPagePromise;
  await expect(customer).toHaveURL(/\/s$/);                                // 조각이 주소에서 지워졌다
  await readDocument(customer);
  await expect(customer.locator('#birth-date')).toHaveCount(0);            // 현장 채널은 고객 입력 본인확인이 없다(룰 데이터)
  await guard.shot(customer, 'customer-sign-view');
  await axe(customer, 'public-sign', info);
  await signAndFinish(customer);
  await guard.shot(customer, 'customer-sign-done');
  await axe(customer, 'public-sign-done', info);
  await guard.close(customer);

  await agentSigns(page);
  await expectStatus(page, 'PARTIALLY_SIGNED');

  const manager = await context.newPage();
  await login(manager, 'DEMO1/demo-manager');
  await openFromList(manager, first.no);
  const mgr = section(manager, 'manager');
  const confirm = manager.waitForRequest((r) => r.url().endsWith('/manager-confirmation'));
  await press(manager, mgr.getByRole('button', { name: staff.manager.confirm }));
  expect((await confirm).postDataJSON()).toEqual({ acknowledgedFlags: [] });
  await expectOk(mgr, staff.manager.confirmed);
  await expectStatus(manager, 'COMPLETED');
  await guard.shot(manager, 'manager-completed');
  await guard.close(manager);
});

test('remote link: identity failures raise a flag only the manager sees, and the manager acknowledges it', async ({ page, context, request, guard }) => {
  const s = guard.sentinels;
  const customerRef = shared.customerRef ?? '';
  expect(customerRef).not.toBe('');
  await login(page, 'DEMO1/demo-agent');
  const second = await draftToSeal(page, customerRef, false);
  expect(await issue(page, 'REMOTE_LINK')).toBeNull();                     // 원격 링크 토큰은 화면에 오지 않는다
  await expect(section(page, 'signing').getByTestId('issued')).toContainText(staff.signing.awaitingDispatch);

  const revoked = await remoteLink(request);
  guard.addSecret(tokenOf(revoked));
  const customer = await context.newPage();
  await openLink(customer, revoked);
  await expect(customer).toHaveURL(/\/s$/);
  await readDocument(customer);
  const wrong = s.birthDate.startsWith('1999') ? '1998-01-01' : '1999-01-01';
  for (let i = 0; i < 5; i++) await verifyBirthDate(customer, wrong, 'failed');
  await openLink(customer, revoked);                                       // 폐기된 세션(REVOKED) — 같은 거부 화면
  await expectUnavailable(customer);
  shared.revokedLink = revoked;

  await issue(page, 'REMOTE_LINK');                                        // 재발급(열린 세션을 닫는다 — REISSUE)
  const link = await remoteLink(request);
  guard.addSecret(tokenOf(link));
  await openLink(customer, link);
  await readDocument(customer);
  await verifyBirthDate(customer, s.birthDate, 'passed');
  await signAndFinish(customer);
  shared.usedLink = link;
  await guard.close(customer);

  await page.reload();                                                     // 메모리 토큰 — 새로 고침 = 다시 로그인
  await login(page, 'DEMO1/demo-agent');
  await openFromList(page, second.no);
  await expect(section(page, 'manager').getByTestId('no-flags')).toBeVisible();   // G8: 설계사에게 비가시 플래그 0
  await agentSigns(page);

  const manager = await context.newPage();
  await login(manager, 'DEMO1/demo-manager');
  await openFromList(manager, second.no);
  const mgr = section(manager, 'manager');
  const list = mgr.getByTestId('disclosure-flags');
  await expect(list).toContainText('IDENTITY_FAILED');                    // 설계사에게 안 보인 그 플래그
  await guard.shot(manager, 'manager-flags');
  const flags = list.getByRole('checkbox');
  const ids: string[] = [];
  for (const box of await flags.all()) {
    ids.push((await box.getAttribute('value')) ?? '');
    await toggle(manager, box);
  }
  expect(ids.length).toBeGreaterThan(0);
  const confirm = manager.waitForRequest((r) => r.url().endsWith('/manager-confirmation'));
  await press(manager, mgr.getByRole('button', { name: staff.manager.confirm }));
  expect((await confirm).postDataJSON()).toEqual({ acknowledgedFlags: ids });       // G8: 확인 흐름이 acknowledgedFlags로 기록
  await expectOk(mgr, staff.manager.confirmed);
  await expectStatus(manager, 'COMPLETED');
  await guard.close(manager);
});

test('every unusable link shows the same single screen, and public pages are not cached', async ({ page, request, guard }, info) => {
  const used = shared.usedLink ?? '';
  const revoked = shared.revokedLink ?? '';
  expect(used).not.toBe('');
  guard.addSecret(tokenOf(used));
  guard.addSecret(tokenOf(revoked));
  const wrong = `DEMO1~${'A'.repeat(43)}`;                                 // 형식만 맞는 가짜(쿼리 칸에도 이것 — 진짜 토큰을 시험이 주소에 싣지 않는다)
  for (const target of [used, revoked, `/s#${wrong}`, `/s?token=${encodeURIComponent(wrong)}`, '/s']) {
    await openLink(page, target);
    await expectUnavailable(page);
    await expect(page).toHaveURL(/\/s(\?.*)?$/);
  }
  await guard.shot(page, 'link-unavailable');
  await axe(page, 'public-unavailable', info);
  const headers = (await request.get('/s')).headers();
  expect(headers['cache-control']).toContain('no-store');
  expect(headers['content-security-policy']).toContain("script-src 'self'");
  expect(headers['content-security-policy']).not.toContain('unsafe');
  expect(headers['referrer-policy']).toBe('no-referrer');
  for (const path of ['/staff', '/oidc-callback']) expect((await request.get(path)).headers()['cache-control']).toContain('no-store');
});

test('compliance: queue, assign and resolve, legal hold with four eyes, retention dry-run, collection rates', async ({ page, context, guard }, info) => {
  const first = shared.first;
  expect(first).toBeDefined();
  await login(page, 'DEMO1/demo-compliance');
  await nav(page, staff.nav.flags);
  await choose(page, page.locator('#f-status'), staff.flagStatus.OPEN);
  await type(page, page.locator('#f-type'), 'IDENTITY_FAILED');
  await press(page, page.getByRole('button', { name: staff.common.apply }));
  const row = page.locator('tr[data-flag-id]').first();
  await expect(row).toContainText('IDENTITY_FAILED');
  await guard.shot(page, 'compliance-flags');
  await axe(page, 'flags', info);
  await type(page, row.getByLabel(staff.flags.assignee), 'demo-compliance');
  await press(page, row.getByRole('button', { name: staff.flags.assign }));
  await expectOk(page.locator('main'), staff.flags.assigned);
  const flagId = (await row.getAttribute('data-flag-id')) ?? '';
  const target = page.locator(`tr[data-flag-id="${flagId}"]`);
  await type(page, target.getByLabel(staff.flags.resolutionCode), 'CUSTOMER_CONTACTED');
  await press(page, target.getByRole('button', { name: staff.flags.resolve }));
  await expectOk(page.locator('main'), staff.flags.resolved);

  await nav(page, staff.nav.legalHolds);
  await axe(page, 'legal-holds', info);
  await type(page, page.locator('#h-disc'), first?.id ?? '');
  await type(page, page.locator('#h-code'), 'CUSTOMER_COMPLAINT');
  await press(page, page.getByRole('button', { name: staff.holds.placeSubmit }));
  await expectOk(page.locator('main'), staff.holds.placed);
  const hold = page.locator('tr').filter({ hasText: first?.id ?? '' }).first();
  await type(page, hold.getByLabel(staff.holds.releaseCode), 'CASE_CLOSED');
  await press(page, hold.getByRole('button', { name: staff.holds.release }));
  await expect(page.locator('main .result .problem')).toContainText('FOUR_EYES_REQUIRED');   // 건 사람의 해제는 거부(4-eyes) — 코드를 보인다

  const other = await context.newPage();
  await login(other, 'DEMO1/demo-compliance-2');
  await nav(other, staff.nav.legalHolds);
  const holdRow = other.locator('tr').filter({ hasText: first?.id ?? '' }).first();
  await type(other, holdRow.getByLabel(staff.holds.releaseCode), 'CASE_CLOSED');
  await press(other, holdRow.getByRole('button', { name: staff.holds.release }));
  await expectOk(other.locator('main'), staff.holds.released);
  await guard.shot(other, 'legal-hold-released');
  await guard.close(other);

  await nav(page, staff.nav.jobs);
  await axe(page, 'jobs', info);
  await chooseCode(page, page.locator('#j-kind'), 'RETENTION_RECOMPUTE');
  await type(page, page.locator('#j-rule'), RULE_VERSION);
  await expect(page.locator('#j-apply')).not.toBeChecked();               // dry-run 기본
  await press(page, page.getByRole('button', { name: staff.jobs.submitButton }));
  await expectOk(page.locator('main'), staff.jobs.submitted);
  await expect(page.getByTestId('job-status').first()).toHaveText(staff.jobStatus.SUCCEEDED, { timeout: 60_000 });
  await expect(page.locator('pre.report')).toContainText(/"apply":\s*false/);
  await guard.shot(page, 'retention-dry-run');

  await nav(page, staff.nav.collectionRates);
  await type(page, page.locator('#r-from'), '2026-01');
  await type(page, page.locator('#r-to'), '2026-09');
  await press(page, page.getByRole('button', { name: staff.common.apply }));
  await expect(page.getByTestId('rate-definition')).toContainText('INTERNAL_METRIC_NO_REGULATORY_DEFINITION');
  await guard.shot(page, 'collection-rates');
  await axe(page, 'collection-rates', info);
  await logout(page);
});
