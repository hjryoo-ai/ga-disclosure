// 데모 로그인 콜백(Q6, 데모 프로파일만): 질의의 코드·state를 읽어 주소에서 지우고, 같은 출처의 직원 화면 창(opener)에 넘긴 뒤 닫는다.
// 코드 검증자·토큰은 직원 화면 창의 메모리에만 있다 — 이 창은 아무것도 저장하지 않고 서버도 부르지 않는다.
import { oidcCallback } from '../shared/messages.ko.json';

const params = new URLSearchParams(location.search);
const code = params.get('code');
const state = params.get('state');
history.replaceState(null, '', '/oidc-callback');
const root = document.getElementById('root');
document.title = oidcCallback.title;
if (root) {
  const p = document.createElement('p');
  p.textContent = window.opener !== null && code !== null && state !== null ? oidcCallback.closing : oidcCallback.noOpener;
  root.replaceChildren(p);
}
if (window.opener !== null && code !== null && state !== null) {
  (window.opener as Window).postMessage({ type: 'ga-demo-oidc', code, state }, location.origin);
  window.close();
}
