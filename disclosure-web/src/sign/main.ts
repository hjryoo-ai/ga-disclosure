// 고객 공개 서명 화면(/s#{token}, 계획 ⑤): 상태 → 열람 → 본인확인 → 서명 → 완료. 라우터·상태 관리·인증 라이브러리 0, DOM API만.
// 업무 판단은 서버가 한다: 버튼은 막지 않고, 서버가 거부하면 그 코드를 보인다. 거부 응답(404)·네트워크 실패는 사유를 나누지 않는 한 화면(G4).
// 토큰·생년월일은 메모리에만 — 저장소·콘솔·URL에 남기지 않는다(G4·G5). 본인확인 입력은 제출 직후 요소와 변수를 비운다.
import '../shared/fonts.css';
import './sign.css';
import { publicClient, type PublicClient } from '../shared/api/publicClient';
import { sign } from '../shared/messages.ko.json';
import { SignaturePad } from './pad';
import { takeToken } from './token';
import { renderPdf, ViewTracker } from './viewer';

/** 고객이 직접 입력하는 본인확인 수단 → 계약 VerifyRequest의 필드(계약 모양 그대로 — 판단이 아니다). */
const CUSTOMER_INPUT_METHODS: Readonly<Record<string, 'birthDate'>> = { BIRTH_DATE: 'birthDate' };

class Unavailable extends Error {}

function el<K extends keyof HTMLElementTagNameMap>(tag: K, props: Partial<Record<string, string>> = {}, ...children: (Node | string)[]): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (v !== undefined) {
      if (k === 'text') node.textContent = v;
      else node.setAttribute(k, v);
    }
  }
  node.append(...children);
  return node;
}

/** 사유를 구별하지 않는 거부 한 화면. 앞서 그린 것은 모두 지운다. */
export function showUnavailable(root: HTMLElement): void {
  root.replaceChildren(el('h1', { text: sign.problem.SIGN_LINK_UNAVAILABLE, tabindex: '-1', id: 'unavailable' }));
  root.querySelector<HTMLElement>('#unavailable')?.focus();
}

/** 422 업무 거부: 고정 문구 + 서버의 거부 코드 그대로(코드 → 문구 사전은 직원 화면 것 — 여기서는 코드를 숨기지 않고 보인다). */
function rejectedLine(codes: string[]): HTMLElement {
  return el('p', { class: 'rejected', role: 'alert', text: `${sign.problem.REJECTED} (${codes.join(', ')})` });
}

interface Problem {
  status: number;
  codes: string[];
}

/** openapi-fetch 결과를 화면 분기로: 성공 데이터, 422 거부 코드, 나머지는 거부 한 화면. */
function outcome<T>(r: { data?: T; error?: unknown; response: Response }): T | Problem {
  if (r.data !== undefined && r.response.ok) return r.data;
  if (r.response.status === 422) {
    const body = r.error as { details?: { rejections?: { code: string }[] } } | undefined;
    return { status: 422, codes: (body?.details?.rejections ?? []).map((x) => x.code) };
  }
  throw new Unavailable();
}

const isProblem = (v: unknown): v is Problem => typeof v === 'object' && v !== null && 'status' in v && 'codes' in v;

export async function start(root: HTMLElement, client: PublicClient): Promise<void> {
  const live = el('p', { class: 'live', 'aria-live': 'polite' });
  const status = outcome(await client.POST('/public/v1/sign/status', { body: {} }));
  if (isProblem(status)) throw new Unavailable();

  const viewSection = el('section', { 'aria-labelledby': 'h-view' }, el('h2', { id: 'h-view', text: sign.view.heading }), el('p', { text: sign.view.hint }));
  const pages = el('div', { class: 'pdf', tabindex: '0', 'aria-label': sign.view.documentLabel });
  const viewedButton = el('button', { type: 'button', text: sign.view.done });
  viewSection.append(pages, viewedButton);

  const sections: HTMLElement[] = [el('h1', { text: sign.app.title }), live, viewSection];

  const inputs = status.identityRequired.filter((m) => m in CUSTOMER_INPUT_METHODS && !status.identityPassed.includes(m));
  let identityForm: HTMLFormElement | null = null;
  if (inputs.length > 0) {
    const input = el('input', { id: 'birth-date', type: 'date', name: 'birthDate', autocomplete: 'off', required: '' });
    identityForm = el('form', { 'aria-labelledby': 'h-identity', novalidate: '' },
      el('h2', { id: 'h-identity', text: sign.identity.heading }),
      el('label', { for: 'birth-date', text: sign.identity.birthDate }), input,
      el('button', { type: 'submit', text: sign.identity.submit }));
    sections.push(identityForm);
  }

  const canvas = el('canvas', { width: '600', height: '240', class: 'pad', role: 'img', 'aria-label': sign.pad.canvasLabel });
  const clearButton = el('button', { type: 'button', text: sign.pad.clear });
  const submitButton = el('button', { type: 'button', text: sign.pad.submit });
  const padSection = el('section', { 'aria-labelledby': 'h-pad' }, el('h2', { id: 'h-pad', text: sign.pad.heading }), el('p', { text: sign.pad.hint }),
    canvas, el('div', { class: 'actions' }, clearButton, submitButton));
  sections.push(padSection);
  root.replaceChildren(...sections);

  const tracker = new ViewTracker(() => performance.now(), document);
  const opened = await client.POST('/public/v1/sign/open', { body: {}, parseAs: 'arrayBuffer' });
  if (!opened.response.ok || opened.data === undefined) throw new Unavailable();
  await renderPdf(opened.data, pages, tracker, (n, total) => `${sign.view.pageLabel} ${n} / ${total}`);

  viewedButton.addEventListener('click', () => {
    void (async () => {
      const r = outcome(await client.POST('/public/v1/sign/view', { body: { scrollComplete: tracker.scrollComplete(), viewSeconds: tracker.viewSeconds() } }));
      live.replaceChildren(isProblem(r) ? rejectedLine(r.codes) : sign.view.recorded);
    })().catch(() => { showUnavailable(root); });
  });

  if (identityForm !== null) {
    const form = identityForm;
    form.addEventListener('submit', (e) => {
      e.preventDefault();
      const input = form.querySelector<HTMLInputElement>('#birth-date');
      if (input === null) return;
      const body = { birthDate: input.value };                           // 값은 요청 본문 하나에만
      input.value = '';                                                    // 제출 즉시 요소를 비운다
      void (async () => {
        const r = outcome(await client.POST('/public/v1/sign/verify-identity', { body }));
        body.birthDate = '';                                               // 보낸 뒤 본문의 값도 비운다
        live.replaceChildren(isProblem(r) ? rejectedLine(r.codes) : r.passed ? sign.identity.passed : sign.identity.failed);
      })().catch(() => { showUnavailable(root); });
    });
  }

  const pad = new SignaturePad(canvas);
  clearButton.addEventListener('click', () => { pad.clear(); });
  submitButton.addEventListener('click', () => {
    void (async () => {
      const r = outcome(await client.POST('/public/v1/sign/capture', {
        body: { strokes: pad.strokesSnapshot(), imagePngBase64: pad.pngBase64(), deviceFingerprint: await fingerprint() },
      }));
      if (isProblem(r)) {
        live.replaceChildren(rejectedLine(r.codes));
        return;
      }
      root.replaceChildren(el('h1', { text: sign.done.heading, tabindex: '-1', id: 'done' }), el('p', { text: sign.done.text }));
      root.querySelector<HTMLElement>('#done')?.focus();
    })().catch(() => { showUnavailable(root); });
  });
}

/** 화면 시작: 어떤 실패든(404·네트워크·예상 밖 응답) 사유를 나누지 않는 거부 한 화면(G4). */
export function boot(root: HTMLElement, client: PublicClient): Promise<void> {
  return start(root, client).catch(() => { showUnavailable(root); });
}

/** 기기 지문: 브라우저·화면·시간대 표기의 SHA-256(대리 서명 탐지 입력 — 개인정보 아님, 원문은 보내지 않는다). */
async function fingerprint(): Promise<string> {
  const raw = [navigator.userAgent, `${screen.width}x${screen.height}`, String(devicePixelRatio), Intl.DateTimeFormat().resolvedOptions().timeZone].join('|');
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(raw));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

const root = document.getElementById('root');
if (root !== null) {
  document.title = sign.app.title;
  root.replaceChildren(el('p', { text: sign.app.loading }));
  const token = takeToken(location, history);
  if (token === null) {
    showUnavailable(root);
  } else {
    void boot(root, publicClient(token));
  }
}
