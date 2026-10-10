// 서명 토큰(계획 ⑤, G4): URL 프래그먼트에서 한 번 읽고 즉시 주소에서 지운다(history.replaceState). 저장소에 두지 않는다 —
// 호출자가 모듈 지역 클로저(생성 클라이언트의 헤더)에만 둔다. 형식은 계약의 `{tenant}~{secret}`. 형식이 틀려도 주소는 지운다.
const TOKEN = /^[A-Za-z0-9_-]{1,64}~[A-Za-z0-9_-]{16,256}$/;

export function takeToken(loc: Pick<Location, 'hash'>, hist: Pick<History, 'replaceState'>): string | null {
  const raw = loc.hash.startsWith('#') ? loc.hash.slice(1) : '';
  hist.replaceState(null, '', '/s');
  return TOKEN.test(raw) ? raw : null;
}
