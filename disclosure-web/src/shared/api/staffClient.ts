import createClient from 'openapi-fetch';
import type { paths } from '../../gen/disclosure-api';

/** 직원 화면의 유일한 서버 호출 경로: disclosure-api 계약에서 생성한 타입의 클라이언트(G2). 토큰은 메모리의 인자로만 받는다. */
export function staffClient(accessToken: string) {
  return createClient<paths>({ baseUrl: '', headers: { Authorization: `Bearer ${accessToken}` } });
}

export type StaffClient = ReturnType<typeof staffClient>;
