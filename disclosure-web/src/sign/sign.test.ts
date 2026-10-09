// 고객 서명 화면 단위·컴포넌트 시험(G4 일부): 토큰은 프래그먼트에서 꺼내 주소에서 지운다, 거부는 사유 무관 한 화면, 저장소 접근 0,
// 본인확인 입력은 제출 즉시 비운다, 스트로크 형식(t = 첫 점부터 ms), 열람 초는 보인 동안만.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { sign } from '../shared/messages.ko.json';
import type { PublicClient } from '../shared/api/publicClient';
import { SignaturePad } from '../shared/pad';
import { takeToken } from './token';
import { ViewTracker } from './viewer';

vi.mock('./viewer', async (importOriginal) => {
  const real = await importOriginal<typeof import('./viewer')>();
  return { ...real, renderPdf: vi.fn(() => Promise.resolve(1)) };
});

const TOKEN = 'DEMO1~abcdefghijklmnopqrstuvwxyz012345';

describe('token', () => {
  it('takes the token from the fragment and removes it from the address', () => {
    const replaceState = vi.fn();
    expect(takeToken({ hash: '#' + TOKEN }, { replaceState })).toBe(TOKEN);
    expect(replaceState).toHaveBeenCalledWith(null, '', '/s');
  });

  it('clears the address even when the fragment is not a token', () => {
    for (const hash of ['', '#', '#not a token', '#a~b', `#${TOKEN}?x=1`]) {
      const replaceState = vi.fn();
      expect(takeToken({ hash }, { replaceState })).toBeNull();
      expect(replaceState).toHaveBeenCalledWith(null, '', '/s');
    }
  });
});

describe('view tracker', () => {
  it('counts monotonic seconds only while visible and completes only when the last page is seen', () => {
    let now = 1000;
    let state: DocumentVisibilityState = 'visible';
    const listeners: (() => void)[] = [];
    const doc = { get visibilityState() { return state; }, addEventListener: (_: string, l: () => void) => { listeners.push(l); } } as unknown as Document;
    const t = new ViewTracker(() => now, doc);
    now += 5_500;
    state = 'hidden';
    listeners.forEach((l) => { l(); });
    now += 60_000;
    state = 'visible';
    listeners.forEach((l) => { l(); });
    now += 2_600;
    expect(t.viewSeconds()).toBe(8);
    expect(t.scrollComplete()).toBe(false);
    t.markLastPageSeen();
    expect(t.scrollComplete()).toBe(true);
  });
});

describe('signature pad', () => {
  it('records strokes as integer pixel points with t in ms from the first point', () => {
    const canvas = document.createElement('canvas');
    canvas.width = 600;
    canvas.height = 240;
    canvas.getBoundingClientRect = () => ({ left: 10, top: 20, width: 300, height: 120, right: 310, bottom: 140, x: 10, y: 20, toJSON: () => ({}) });
    canvas.setPointerCapture = vi.fn();
    vi.spyOn(canvas, 'getContext').mockReturnValue(null);
    const pad = new SignaturePad(canvas);
    const fire = (type: string, x: number, y: number, ts: number) => {
      const e = new MouseEvent(type, { clientX: x, clientY: y, bubbles: true });
      Object.defineProperty(e, 'timeStamp', { value: ts });
      Object.defineProperty(e, 'pointerId', { value: 1 });
      canvas.dispatchEvent(e);
    };
    fire('pointerdown', 20, 30, 500);
    fire('pointermove', 30.4, 40.6, 516.4);
    fire('pointerup', 30, 40, 520);
    fire('pointerdown', 50, 50, 900);
    fire('pointerup', 50, 50, 905);
    expect(pad.strokesSnapshot()).toEqual([
      [{ x: 20, y: 20, t: 0 }, { x: 41, y: 41, t: 16 }],
      [{ x: 80, y: 60, t: 400 }],
    ]);
    pad.clear();
    expect(pad.isEmpty()).toBe(true);
  });
});

type Post = PublicClient['POST'];

function client(responses: Record<string, () => Promise<unknown>>): { client: PublicClient; calls: { path: string; body: unknown }[] } {
  const calls: { path: string; body: unknown }[] = [];
  const POST = vi.fn((path: string, init: { body?: unknown }) => {
    calls.push({ path, body: structuredClone(init.body) });
    const r = responses[path];
    return r ? r() : Promise.reject(new Error('unexpected ' + path));
  });
  return { client: { POST } as unknown as PublicClient & { POST: Post }, calls };
}

const ok = (data: unknown) => () => Promise.resolve({ data, response: new Response(null, { status: 200 }) });
const notFound = () => Promise.resolve({ error: { code: 'SIGN_LINK_UNAVAILABLE' }, response: new Response(null, { status: 404 }) });
const STATUS = { sessionStatus: 'OPEN', identityRequired: ['LINK_POSSESSION', 'BIRTH_DATE'], identityPassed: ['LINK_POSSESSION'], viewed: false,
  expiresAt: '2026-10-11T00:00:00Z' };

describe('sign flow', () => {
  let root: HTMLElement;
  const storage = { set: 0, get: 0 };

  beforeEach(() => {
    root = document.createElement('main');
    document.body.replaceChildren(root);
    storage.set = 0;
    storage.get = 0;
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { storage.set++; });
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { storage.get++; return null; });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  async function run(c: PublicClient) {
    const { boot } = await import('./main');
    await boot(root, c);
  }

  it('shows the same single screen for a 404 at any step and for a network failure', async () => {
    for (const responses of [
      { '/public/v1/sign/status': notFound },
      { '/public/v1/sign/status': ok(STATUS), '/public/v1/sign/open': notFound },
      { '/public/v1/sign/status': () => Promise.reject(new TypeError('network')) },
    ]) {
      await run(client(responses).client);
      expect(root.textContent).toBe(sign.problem.SIGN_LINK_UNAVAILABLE);
      expect(root.querySelectorAll('h1')).toHaveLength(1);
    }
  });

  it('sends the birth date once, clears the field at once, and never touches browser storage', async () => {
    const c = client({
      '/public/v1/sign/status': ok(STATUS),
      '/public/v1/sign/open': ok(new ArrayBuffer(8)),
      '/public/v1/sign/verify-identity': ok({ passed: true, missing: [] }),
    });
    await run(c.client);
    const input = root.querySelector<HTMLInputElement>('#birth-date');
    expect(input).not.toBeNull();
    if (input === null) return;
    expect(input.getAttribute('autocomplete')).toBe('off');
    input.value = '1990-01-31';
    root.querySelector('form')?.dispatchEvent(new Event('submit', { cancelable: true }));
    expect(input.value).toBe('');
    await vi.waitFor(() => { expect(root.textContent).toContain(sign.identity.passed); });
    expect(c.calls.filter((x) => x.path === '/public/v1/sign/verify-identity')).toEqual([{ path: '/public/v1/sign/verify-identity', body: { birthDate: '1990-01-31' } }]);
    expect(c.calls.every((x) => !JSON.stringify(x.body ?? {}).includes('~'))).toBe(true);
    expect(storage).toEqual({ set: 0, get: 0 });
    expect(document.cookie).toBe('');
  });

  it('shows a 422 rejection inline with the server codes and keeps the page', async () => {
    const c = client({
      '/public/v1/sign/status': ok({ ...STATUS, identityRequired: ['AGENT_FACE_TO_FACE'], identityPassed: [] }),
      '/public/v1/sign/open': ok(new ArrayBuffer(8)),
      '/public/v1/sign/capture': () => Promise.resolve({ error: { code: 'REJECTED', details: { rejections: [{ code: 'IDENTITY_INCOMPLETE' }] } },
        response: new Response(null, { status: 422 }) }),
    });
    vi.spyOn(HTMLCanvasElement.prototype, 'toDataURL').mockReturnValue('data:image/png;base64,AAAA');
    await run(c.client);
    expect(root.querySelector('#birth-date')).toBeNull();
    const submit = [...root.querySelectorAll('button')].find((b) => b.textContent === sign.pad.submit);
    submit?.click();
    await vi.waitFor(() => { expect(root.querySelector('.rejected')?.textContent).toBe(`${sign.problem.REJECTED} (IDENTITY_INCOMPLETE)`); });
    expect(root.querySelector('h1')?.textContent).toBe(sign.app.title);
  });
});
