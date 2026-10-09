// 직원 로그인(Q6, 데모 프로파일): Authorization Code + PKCE. 주체 선택 폼은 새 창으로 연다 — 검증자·토큰은 이 창의 메모리에만 있다
// (브라우저 저장소 금지 — 새로 고침 = 다시 로그인). 콜백 창이 postMessage로 코드를 넘기면 생성 클라이언트로 교환한다.
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { demoAuthClient, formBody } from '../../shared/api/demoAuthClient';
import { challengeOf, claimsOf, randomToken, type Claims } from './pkce';

export const CLIENT_ID = 'ga-disclosure-web';
export const REDIRECT_URI = '/oidc-callback';

interface Session {
  token: string;
  claims: Claims;
}

interface Auth {
  session: Session | null;
  pending: boolean;
  failed: boolean;
  login: () => void;
  logout: () => void;
}

const AuthContext = createContext<Auth | null>(null);

export function useAuth(): Auth {
  const a = useContext(AuthContext);
  if (a === null) throw new Error('AuthProvider missing');
  return a;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);
  const [pending, setPending] = useState(false);
  const [failed, setFailed] = useState(false);
  const flow = useRef<{ verifier: string; state: string } | null>(null);

  useEffect(() => {
    const onMessage = (e: MessageEvent<unknown>) => {
      const f = flow.current;
      const data = e.data as { type?: unknown; code?: unknown; state?: unknown } | null;
      if (e.origin !== location.origin || f === null || data?.type !== 'ga-demo-oidc' || data.state !== f.state || typeof data.code !== 'string') return;
      flow.current = null;
      const code = data.code;
      void (async () => {
        const r = await demoAuthClient().POST('/demo/oidc/token', {
          body: { grant_type: 'authorization_code', code, code_verifier: f.verifier, client_id: CLIENT_ID, redirect_uri: REDIRECT_URI },
          bodySerializer: (b) => formBody(b),
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        });
        const claims = r.data === undefined ? null : claimsOf(r.data.access_token);
        if (r.data === undefined || claims === null) {
          setFailed(true);
        } else {
          setSession({ token: r.data.access_token, claims });
          setFailed(false);
        }
        setPending(false);
      })().catch(() => { setFailed(true); setPending(false); });
    };
    window.addEventListener('message', onMessage);
    return () => { window.removeEventListener('message', onMessage); };
  }, []);

  const login = useCallback(() => {
    const verifier = randomToken(32);
    const state = randomToken(16);
    flow.current = { verifier, state };
    setPending(true);
    setFailed(false);
    void challengeOf(verifier).then((challenge) => {
      const q = new URLSearchParams({ response_type: 'code', client_id: CLIENT_ID, redirect_uri: REDIRECT_URI, code_challenge: challenge,
        code_challenge_method: 'S256', state });
      window.open(`/demo/oidc/authorize?${q.toString()}`, 'ga-demo-login', 'popup,width=520,height=680');
    });
  }, []);

  const logout = useCallback(() => {
    flow.current = null;
    setSession(null);
  }, []);

  const value = useMemo(() => ({ session, pending, failed, login, logout }), [session, pending, failed, login, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
