// Phase 7 G3(화면 쪽): 화면은 상태·검증·게이트를 계산하지 않는다 — 서버 값을 표기만 한다.
//  ① 화면 원천에 확인서 상태 enum 값(계약 DisclosureStatus) 리터럴 = 0 — 상태로 분기할 재료가 없다
//  ② `.status`를 비교(===·!==·==·!=·switch)하는 식 = 0 — 작업·플래그 상태도 표기만 한다. 허용은 닫힌 파일 목록(HTTP 응답 코드 판독 한 곳)이고,
//     허용 항목에 비교가 없으면(폐기된 항목) 실패한다
//  ③ 표기 사전 ↔ 계약 enum 양방향: staff.status·staff.channel·staff.jobStatus·staff.flagStatus·staff.signerRole
// 판독기는 TypeScript 구문 트리(자기 시험 포함).
import { readdirSync, readFileSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import ts from 'typescript';
import { describe, expect, it } from 'vitest';
import messages from '../shared/messages.ko.json';
import { literals } from './sourceText';

const web = resolve(import.meta.dirname, '../..');
const SCANNED = ['src/staff', 'src/sign', 'src/shared'].map((d) => resolve(web, d));
const GEN = readFileSync(resolve(web, 'src/gen/disclosure-api.ts'), 'utf8');

function walk(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) => (e.isDirectory() ? walk(join(dir, e.name)) : [join(dir, e.name)]));
}

/** 생성 타입에서 `anchor` 뒤 처음 나오는 `<field>"A" | "B";` 꼴의 enum 값들. */
function enumOf(field: string, anchor = ''): string[] {
  const from = GEN.indexOf(anchor);
  const at = GEN.indexOf(field, from);
  expect(from, anchor).toBeGreaterThanOrEqual(0);
  expect(at, field).toBeGreaterThan(0);
  const line = GEN.slice(at + field.length, GEN.indexOf(';', at));
  return [...line.matchAll(/"([A-Z_]+)"/g)].map((m) => m[1] ?? '');
}

/** `.status`를 비교하는 식(이항 비교의 한쪽, switch 판별식). */
export function statusComparisons(fileName: string, text: string): string[] {
  const kind = fileName.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS;
  const source = ts.createSourceFile(fileName, text, ts.ScriptTarget.Latest, true, kind);
  const isStatus = (n: ts.Node) => ts.isPropertyAccessExpression(n) && n.name.text === 'status';
  const COMPARE = new Set([ts.SyntaxKind.EqualsEqualsEqualsToken, ts.SyntaxKind.ExclamationEqualsEqualsToken, ts.SyntaxKind.EqualsEqualsToken,
    ts.SyntaxKind.ExclamationEqualsToken]);
  const out: string[] = [];
  const visit = (node: ts.Node): void => {
    if (ts.isBinaryExpression(node) && COMPARE.has(node.operatorToken.kind) && (isStatus(node.left) || isStatus(node.right))) out.push(node.getText(source));
    if (ts.isSwitchStatement(node) && isStatus(node.expression)) out.push(`switch (${node.expression.getText(source)})`);
    ts.forEachChild(node, visit);
  };
  visit(source);
  return out;
}

/** 전송 계층 응답 코드의 유일한 판독 지점(업무 상태가 아니다). */
const STATUS_COMPARISON_ALLOWED = ['src/shared/http.ts'];

const sources = SCANNED.flatMap(walk).filter((f) => /\.tsx?$/.test(f) && !/\.test\.tsx?$/.test(f));
const sorted = (s: Iterable<string>) => [...s].sort();

describe('screens show state but never compute it (G3)', () => {
  const statuses = enumOf('DisclosureStatus: ');

  it('has no disclosure status value written as a screen literal', () => {
    expect(statuses).toHaveLength(11);
    const hits = sources.flatMap((f) => literals(f, readFileSync(f, 'utf8')).filter((l) => statuses.includes(l)).map((l) => `${relative(web, f)}: ${l}`));
    expect(hits).toEqual([]);
  });

  it('never compares a status field', () => {
    expect(sources.length).toBeGreaterThan(20);
    const hits = sources.flatMap((f) => statusComparisons(f, readFileSync(f, 'utf8')).map((c) => `${relative(web, f)}: ${c}`));
    expect(hits.filter((h) => !STATUS_COMPARISON_ALLOWED.some((a) => h.startsWith(`${a}: `)))).toEqual([]);
    for (const a of STATUS_COMPARISON_ALLOWED) expect(hits.some((h) => h.startsWith(`${a}: `)), `stale allow-list entry ${a}`).toBe(true);
  });

  it('labels every contract enum value it shows, and nothing else', () => {
    expect(sorted(Object.keys(messages.staff.status))).toEqual(sorted(statuses));
    expect(sorted(Object.keys(messages.staff.channel))).toEqual(sorted(enumOf('channel: ', '        SessionIssueRequest: {')));
    expect(sorted(Object.keys(messages.staff.flagStatus))).toEqual(sorted(enumOf('FlagStatus: ')));
    expect(sorted(Object.keys(messages.staff.signerRole))).toEqual(sorted(enumOf('role: ', '            signatures: {')));
    expect(sorted(Object.keys(messages.staff.jobStatus))).toEqual(sorted(enumOf('status: ', '        Job: {')));
    expect(sorted(Object.keys(messages.staff.stage))).toEqual(sorted(enumOf('stage: ', '        ValidateRequest: {')));
    expect(sorted(Object.keys(messages.staff.templateType))).toEqual(sorted(enumOf('templateType: ', '        CreateDisclosureRequest: {')));
    expect(sorted(Object.keys(messages.staff.rateFormula))).toEqual(sorted(enumOf('formula: ', '        CollectionRate: {')));
    expect(sorted(Object.keys(messages.staff.role))).toEqual(sorted(enumOf('FlagAssignedRole: ')));
  });

  it('finds comparisons and switches on .status (self-test)', () => {
    const src = [
      "if (d.status === x) f();",
      "const k = 'a' !== r.data.status;",
      "switch (job.status) { default: }",
      "const ok = d.statusText === x;",
      "const s = d.status;",
    ].join('\n');
    expect(statusComparisons('x.ts', src)).toEqual(['d.status === x', "'a' !== r.data.status", 'switch (job.status)']);
  });
});
