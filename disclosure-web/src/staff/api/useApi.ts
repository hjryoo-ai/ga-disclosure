// 직원 화면의 서버 호출: 생성 클라이언트 하나(staffClient) + 쓰기의 멱등 키.
// 멱등 키는 "동작 하나(의도)"마다 새로 만들고, 응답을 못 받은 재시도(네트워크 실패)는 같은 키로 보낸다 — 서버가 재생한다(6A 규약).
// 키는 요청 본문에서 만들지 않는다: 같은 빈 본문 명령(compare 등)의 두 번째 의도가 낡은 응답 재생이 되고, 등록 본문(개인정보)의 해시는
// 되돌릴 수 있다(6B D-1과 같은 문제). 업무 판단은 하지 않는다 — 결과는 서버 응답 그대로(성공 데이터 또는 Problem).
import { useMemo } from 'react';
import { staffClient, type StaffClient } from '../../shared/api/staffClient';
import { useAuth } from '../auth/AuthProvider';

export interface ProblemBody {
  code: string;
  details?: { field?: string; rejections?: { code: string; ruleId?: string | null }[] };
}

export type Outcome<T> =
  | { ok: true; data: T; replayed: boolean }
  | { ok: false; status: number; problem: ProblemBody | null };

interface Raw<T> {
  data?: T;
  error?: unknown;
  response: Response;
}

export function outcome<T>(r: Raw<T>): Outcome<T> {
  if (r.response.ok) {
    return { ok: true, data: r.data as T, replayed: r.response.headers.get('Idempotency-Replayed') === 'true' };
  }
  const e = r.error as Partial<ProblemBody> | undefined;
  return { ok: false, status: r.response.status, problem: typeof e?.code === 'string' ? (e as ProblemBody) : null };
}

/** 의도 하나의 멱등 키(서버 형식 [A-Za-z0-9_-]{16,128}). */
export function intentKey(): string {
  return 'ui-' + crypto.randomUUID().replace(/-/g, '');
}

/** 쓰기 한 번: 같은 키로 네트워크 실패만 두 번까지 다시 보낸다. 서버의 거부(4xx·5xx 응답)는 다시 보내지 않는다. */
export async function write<T>(send: (key: string) => Promise<Raw<T>>, attempts = 3): Promise<Outcome<T>> {
  const key = intentKey();
  for (let i = 0; i < attempts; i++) {
    try {
      return outcome(await send(key));
    } catch {
      // 응답을 못 받았다(네트워크) — 같은 키로 다시 보낸다. 서버가 이미 처리했으면 재생이다.
    }
  }
  return { ok: false, status: 0, problem: null };
}

export function useApi(): StaffClient {
  const { session } = useAuth();
  const token = session?.token ?? '';
  return useMemo(() => staffClient(token), [token]);
}
