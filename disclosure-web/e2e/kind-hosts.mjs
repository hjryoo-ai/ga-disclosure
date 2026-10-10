// 클러스터 대상 E2E(GA_E2E_KIND)에서만 사전 적재(NODE_OPTIONS=--import): 데모 진입점 이름(.invalid — 실제 DNS에 없다)을 127.0.0.1로 푼다. 이름은
// GA_E2E_KIND_HOSTS(쉼표 — 비밀 아님)에 있는 것만이고, 그 밖 이름은 원래 해석기로. 브라우저는 --host-resolver-rules로 같은 일을 한다(playwright.config.ts).
// Playwright 요청 문맥은 호출 때마다 dns.promises.lookup을 부르므로 이 덮어쓰기를 본다.
import dns from 'node:dns';

const hosts = new Set((process.env.GA_E2E_KIND_HOSTS || '').split(',').filter(Boolean));
if (hosts.size > 0) {
  const answer = (options) => {
    const family = typeof options === 'number' ? options : options && options.family;
    const all = typeof options === 'object' && options !== null && options.all === true;
    if (family === 6) return null;                                         // IPv6 질의는 "없음" — IPv4 답만 쓴다
    return all ? [{ address: '127.0.0.1', family: 4 }] : { address: '127.0.0.1', family: 4 };
  };
  const notFound = (hostname) => Object.assign(new Error(`getaddrinfo ENOTFOUND ${hostname}`), { code: 'ENOTFOUND', hostname });
  const lookupAsync = dns.promises.lookup;
  dns.promises.lookup = function lookup(hostname, options) {
    if (!hosts.has(hostname)) return lookupAsync.apply(this, arguments);
    const a = answer(options);
    return a === null ? Promise.reject(notFound(hostname)) : Promise.resolve(a);
  };
  const lookupCb = dns.lookup;
  dns.lookup = function lookup(hostname, options, callback) {
    if (!hosts.has(hostname)) return lookupCb.apply(this, arguments);
    const cb = typeof options === 'function' ? options : callback;
    const a = answer(typeof options === 'function' ? {} : options);
    process.nextTick(() => {
      if (a === null) cb(notFound(hostname));
      else if (Array.isArray(a)) cb(null, a);
      else cb(null, a.address, a.family);
    });
  };
}
