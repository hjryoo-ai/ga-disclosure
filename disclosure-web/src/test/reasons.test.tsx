// Phase 7 G6(절대 규칙 7): 추천사유는 설계사 입력만 — 화면은 채우지 않고 제안하지 않는다.
//  ① 입력 칸은 서버에 저장된 사유가 있어도 비어 있다(미리 채움 0) — Phase 8부터 코드는 룰 어휘의 체크 상자이고, 저장된 코드가 목록에 있어도
//     체크되어 있지 않다(사전 체크 0)
//  ② 자동완성 끔(폼·텍스트 칸), placeholder·datalist·list 속성·기본값 0
//  ③ 아무것도 고르거나 쓰지 않고 저장하면 빈 목록을 보낸다(시스템이 만든 사유 0), 고른 것·쓴 것은 그대로 보낸다
//  ④ 사유 절의 화면 문구에 예시 표기(예:·예시·e.g.) 0
//  ⑤ (Phase 8 G9) 선택지는 어휘가 준 코드 그대로·그 순서 — 화면이 더하거나 빼거나 정렬하지 않는다
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { components } from '../gen/disclosure-api';
import messages from '../shared/messages.ko.json';
import { RecommendationsSection } from '../staff/pages/detail/RecommendationsSection';

type Detail = components['schemas']['DisclosureDetail'];
type Template = components['schemas']['DisclosureTemplate'];

const post = vi.fn<(path: string, init: unknown) => Promise<unknown>>(() => Promise.resolve({ data: {}, response: new Response('{}', { status: 200 }) }));
vi.mock('../staff/api/useApi', async (importOriginal) => {
  const real = await importOriginal<typeof import('../staff/api/useApi')>();
  return { ...real, useApi: () => ({ POST: post }) };
});

// 어휘(서버가 시스템 부가 코드를 이미 뺀 목록) — 표기는 일부러 코드 순이 아니다(화면이 정렬하지 않음을 본다)
const VOCABULARY = [{ code: 'C3', label: 'third' }, { code: 'A1', label: 'first' }, { code: 'B2', label: 'second' }];
vi.mock('../staff/api/vocabulary', () => ({ useCodes: () => VOCABULARY }));

const SAVED_TEXT = 'previously-saved-reason-text';
const item = (itemNo: number, recommended: boolean): Detail['items'][number] => ({
  itemNo, insurerCode: `INS${itemNo}`, productName: `Product ${itemNo}`, productKey: `PK-${itemNo}`, groupCode: 'PG', quoteDocNo: null,
  grade: null, recommended, requestedByCustomer: false, tempProduct: false,
  recommendation: recommended ? { reasonCodes: ['A1'], text: SAVED_TEXT } : null,
});
const detail = { items: [item(1, true), item(2, false)] } as unknown as Detail;
const template = { fields: [{ code: 'RECOMMENDATION_REASON', label: 'L', order: 1, required: true, section: 'S', unavailableText: null }] } as unknown as Template;

let root: Root;
let host: HTMLDivElement;
beforeEach(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  act(() => { root.render(<RecommendationsSection id="d-1" detail={detail} template={template} reload={() => undefined} />); });
  post.mockClear();
});
afterEach(() => {
  act(() => { root.unmount(); });
  host.remove();
});

const controls = () => [...host.querySelectorAll<HTMLInputElement | HTMLTextAreaElement>('input, textarea')];
const boxes = (itemNo: number) => [...host.querySelectorAll<HTMLInputElement>(`input[type="checkbox"][name="codes-${String(itemNo)}"]`)];
const texts = () => [...host.querySelectorAll<HTMLTextAreaElement>('textarea')];
const submit = async () => {
  await act(async () => {
    host.querySelector('form')?.requestSubmit();
    await Promise.resolve();
  });
};
const sentBody = () => (post.mock.calls[0]?.[1] as { body: unknown } | undefined)?.body;

describe('recommendation reasons are written by the agent only (G6)', () => {
  it('starts every reason input empty even when reasons are already saved', () => {
    expect(controls()).toHaveLength(2 * (VOCABULARY.length + 1));
    expect(controls().filter((c) => c instanceof HTMLInputElement && c.type !== 'checkbox')).toEqual([]);
    expect(controls().filter((c) => c instanceof HTMLInputElement && (c.checked || c.defaultChecked))).toEqual([]);
    expect(boxes(1).find((b) => b.value === 'A1')?.checked).toBe(false);          // 저장된 코드도 체크하지 않는다
    expect(texts().map((t) => t.value)).toEqual(['', '']);
    expect(host.textContent).not.toContain(SAVED_TEXT);
  });

  it('turns autocomplete off and offers no placeholder, suggestion list or default', () => {
    expect(host.querySelector('form')?.getAttribute('autocomplete')).toBe('off');
    for (const t of texts()) {
      expect(t.getAttribute('autocomplete')).toBe('off');
      expect(t.defaultValue).toBe('');
    }
    for (const c of controls()) {
      expect(c.hasAttribute('placeholder')).toBe(false);
      expect(c.hasAttribute('list')).toBe(false);
    }
    expect(host.querySelectorAll('datalist, option')).toHaveLength(0);
  });

  it('sends an empty list when nothing was chosen or typed, and exactly what was chosen and typed otherwise', async () => {
    await submit();
    expect(sentBody()).toEqual({ reasons: [] });

    post.mockClear();
    for (const b of boxes(1).filter((x) => x.value === 'B2' || x.value === 'A1')) b.checked = true;
    const [text] = texts();
    if (text === undefined) throw new Error('controls');
    text.value = 'typed by the agent';
    await submit();
    // 고른 코드는 어휘 순서대로(화면에 놓인 순서), 텍스트는 쓴 그대로
    expect(sentBody()).toEqual({ reasons: [{ itemNo: 1, codes: ['A1', 'B2'], text: 'typed by the agent' }] });
  });

  it('offers exactly the vocabulary codes, in vocabulary order, for every item (G9)', () => {
    for (const itemNo of [1, 2]) {
      expect(boxes(itemNo).map((b) => b.value)).toEqual(VOCABULARY.map((v) => v.code));
      expect(boxes(itemNo).map((b) => host.querySelector(`label[for="${b.id}"]`)?.textContent)).toEqual(VOCABULARY.map((v) => v.label));
    }
  });

  it('has no example wording in the reason texts', () => {
    const texts = Object.values(messages.staff.reasons);
    expect(texts.length).toBeGreaterThan(3);
    expect(texts.filter((t) => /예:|예시|e\.g\.|example/i.test(t))).toEqual([]);
    expect(host.textContent).not.toMatch(/예:|예시|e\.g\./);
  });
});
