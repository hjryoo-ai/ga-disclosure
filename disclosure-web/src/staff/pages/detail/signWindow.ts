// 고객 서명 창 주소(Phase 8): 서버가 세션 발급 응답에 준 signUrl(서명 호스트 + #토큰)만 연다 — 서명 호스트가 직원 호스트와 다른 배포에서 상대 경로 `/s#`는
// 공개 서명 API가 없는 직원 호스트에 닿는다. http·https 주소이고 조각에 토큰이 있을 때만(그 밖 — 다른 스킴·조각 없음 — 은 열지 않는다).
export function signWindowUrl(signUrl: string | null | undefined): string | null {
  if (signUrl === null || signUrl === undefined) return null;
  let url: URL;
  try {
    url = new URL(signUrl);
  } catch {
    return null;
  }
  if ((url.protocol !== 'https:' && url.protocol !== 'http:') || url.hash.length < 2) return null;
  return url.href;
}
