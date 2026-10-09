// 기기 지문: 브라우저·화면·시간대 표기의 SHA-256(대리 서명 탐지 입력 — 개인정보 아님, 원문은 보내지 않는다). 공개 서명·직원 서명이 같이 쓴다.
export async function fingerprint(): Promise<string> {
  const raw = [navigator.userAgent, `${screen.width}x${screen.height}`, String(devicePixelRatio), Intl.DateTimeFormat().resolvedOptions().timeZone].join('|');
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(raw));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}
