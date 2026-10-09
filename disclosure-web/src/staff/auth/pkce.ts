// 데모 로그인(Q6) PKCE: 검증자·state는 WebCrypto 난수, 챌린지 = base64url(SHA-256(검증자)). 인증 라이브러리 없이.
export function base64url(bytes: Uint8Array): string {
  let s = '';
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function randomToken(bytes: number): string {
  return base64url(crypto.getRandomValues(new Uint8Array(bytes)));
}

export async function challengeOf(verifier: string): Promise<string> {
  return base64url(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))));
}

/** 화면 표시용 클레임(검증하지 않는다 — 검증은 서버). 역할 클레임은 없다(역할은 identity_link). */
export interface Claims {
  sub: string;
  tenant: string;
  exp: number;
}

export function claimsOf(jwt: string): Claims | null {
  const part = jwt.split('.')[1];
  if (part === undefined) return null;
  try {
    const json = JSON.parse(atob(part.replace(/-/g, '+').replace(/_/g, '/'))) as { sub?: unknown; tenant_id?: unknown; exp?: unknown };
    return typeof json.sub === 'string' && typeof json.tenant_id === 'string' && typeof json.exp === 'number'
      ? { sub: json.sub, tenant: json.tenant_id, exp: json.exp } : null;
  } catch {
    return null;
  }
}
