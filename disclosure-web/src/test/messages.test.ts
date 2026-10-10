// Phase 7 G3 사전 커버리지(계획 ④ "Problem.code 사전"): 서버가 내는 코드 전수 ↔ messages.ko.json, 양방향(빠진 코드·남은 키 모두 실패).
//  ① 최상위 17(Phase 8: TENANT_RULES_NOT_ACTIVE) = disclosure-api 계약 Problem.code enum(생성 타입에서 읽는다) ↔ problem.code
//  ② 거부 코드 = 설계서 rejection-categories 블록(Categorized enum 전수 — 6B D-3과 같은 목록) ↔ problem.rejection
//  ③ 공개 2 = disclosure-public 계약 Problem.code ↔ sign.problem
//  ④ 사전에 없는 코드는 코드 그대로, 문구에 치환자 0(고객 정보를 넣을 자리가 없다)
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import messages from '../shared/messages.ko.json';
import { problemText, rejectionText } from '../shared/problem';

const web = resolve(import.meta.dirname, '../..');
const repo = resolve(web, '..');

/** 생성 타입 파일의 `Problem` 스키마 안 `code` 리터럴들(계약 enum·const 그대로). */
function problemCodes(genFile: string): Set<string> {
  const text = readFileSync(resolve(web, genFile), 'utf8');
  const start = text.indexOf('        Problem: ');
  expect(start).toBeGreaterThan(0);
  const end = text.indexOf('\n        };', start);
  const block = text.slice(start, end);
  const codes = new Set<string>();
  for (const m of block.matchAll(/code: ((?:"[A-Z_]+"(?: \| )?)+);/g)) {
    for (const c of (m[1] ?? '').matchAll(/"([A-Z_]+)"/g)) codes.add(c[1] ?? '');
  }
  return codes;
}

function rejectionCodes(): Set<string> {
  const doc = readFileSync(resolve(repo, 'docs/설계서.md'), 'utf8');
  const block = /```rejection-categories\n([\s\S]*?)\n```/.exec(doc)?.[1] ?? '';
  const rows = block.split('\n').slice(1).map((l) => l.split(','));
  expect(rows.length).toBeGreaterThanOrEqual(76);
  return new Set(rows.map((r) => r[1] ?? ''));
}

const sorted = (s: Iterable<string>) => [...s].sort();

describe('Problem.code dictionary covers every server code both ways', () => {
  it('has a text for each of the 17 top-level codes and no other', () => {
    const contract = problemCodes('src/gen/disclosure-api.ts');
    expect(contract.size).toBe(17);
    expect(sorted(Object.keys(messages.problem.code))).toEqual(sorted(contract));
  });

  it('has a text for each rejection code of the rejection-categories block and no other', () => {
    const block = rejectionCodes();
    expect(block.size).toBe(75);
    expect(sorted(Object.keys(messages.problem.rejection))).toEqual(sorted(block));
  });

  it('has a text for the two public codes in the sign catalog and no other', () => {
    const contract = problemCodes('src/gen/disclosure-public.ts');
    expect(sorted(contract)).toEqual(['REJECTED', 'SIGN_LINK_UNAVAILABLE']);
    expect(sorted(Object.keys(messages.sign.problem))).toEqual(sorted(contract));
  });

  it('shows an unknown code as the code itself and has no placeholder in any problem text', () => {
    expect(problemText('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(rejectionText('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(problemText('NOT_FOUND')).toBe(messages.problem.code.NOT_FOUND);
    const values = [...Object.values(messages.problem.code), ...Object.values(messages.problem.rejection), ...Object.values(messages.sign.problem)];
    expect(values.filter((v) => /[{}<>$%]/.test(v))).toEqual([]);
  });
});
