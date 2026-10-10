import createClient from 'openapi-fetch';
import type { paths } from '../../gen/demo-oidc';

/** 데모 로그인(Q6, 데모 프로파일만)의 토큰 교환 — demo-oidc 계약의 생성 타입. 본문은 폼 인코딩(RFC 6749). */
export function demoAuthClient() {
  return createClient<paths>({ baseUrl: '' });
}

export const formBody = (body: Record<string, string>): URLSearchParams => new URLSearchParams(body);
