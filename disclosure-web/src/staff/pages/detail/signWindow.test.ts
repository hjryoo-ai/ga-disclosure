// 서명 창 주소 판독(Phase 8): 서버의 signUrl만, http·https와 조각 토큰이 있을 때만.
import { describe, expect, it } from 'vitest';
import { signWindowUrl } from './signWindow';

describe('signWindowUrl', () => {
  it('opens the sign host the server named, token in the fragment', () => {
    expect(signWindowUrl('https://sign.ga.example.invalid:18443/s#DEMO1~abc')).toBe('https://sign.ga.example.invalid:18443/s#DEMO1~abc');
    expect(signWindowUrl('http://127.0.0.1:8080/s#DEMO1~abc')).toBe('http://127.0.0.1:8080/s#DEMO1~abc');
  });

  it('opens nothing without a server address, without a token or with another scheme', () => {
    expect(signWindowUrl(null)).toBeNull();
    expect(signWindowUrl(undefined)).toBeNull();
    expect(signWindowUrl('/s#DEMO1~abc')).toBeNull();
    expect(signWindowUrl('https://sign.ga.example.invalid/s#')).toBeNull();
    expect(signWindowUrl('https://sign.ga.example.invalid/s')).toBeNull();
    expect(signWindowUrl('javascript:alert(1)#x')).toBeNull();
  });
});
