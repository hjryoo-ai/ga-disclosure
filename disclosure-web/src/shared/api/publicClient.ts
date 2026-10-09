import createClient from 'openapi-fetch';
import type { paths } from '../../gen/disclosure-public';

/** 고객 서명 화면의 유일한 서버 호출 경로: disclosure-public 계약의 생성 타입. 토큰은 헤더 X-Sign-Token으로만(본문·질의 0, G4). */
export function publicClient(token: string) {
  return createClient<paths>({ baseUrl: '', headers: { 'X-Sign-Token': token } });
}

export type PublicClient = ReturnType<typeof publicClient>;
