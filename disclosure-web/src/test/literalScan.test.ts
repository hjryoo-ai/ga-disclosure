// Phase 7 G1(화면 쪽)·Q4 조건: 화면 문구는 서식 데이터(getDisclosureTemplate) 또는 messages.ko.json 하나에서만 온다.
//  ① 화면 원천(TS·TSX·HTML·CSS)의 한글 = 0 — 문자열·템플릿 리터럴·JSX 텍스트·JSX 속성·정규식 모두(주석 제외). messages.ko.json만 예외.
//  ② 서식 번들의 문구(라벨·제목·섹션·서명 페이지·산출불가 표기)와 같은 리터럴 = 0
//  ③ messages.ko.json의 값 집합 ∩ 서식 문구 집합 = ∅ — 서식 라벨은 메시지 파일에 두지 않는다(Q3에서 온다)
// 서버 쪽 같은 검사는 disclosure-app archTest LabelLiteralScanTest. 판독기는 TypeScript 컴파일러의 구문 트리(T4 교훈 — 템플릿·JSX까지 자기 시험).
import { readdirSync, readFileSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { literals } from './sourceText';

const web = resolve(import.meta.dirname, '../..');
const repo = resolve(web, '..');
const SCANNED = ['src/staff', 'src/sign', 'src/shared'].map((d) => resolve(web, d));
const MESSAGES = resolve(web, 'src/shared/messages.ko.json');
const TEMPLATE_DIRS = ['contracts/rules/bundles/templates', 'disclosure-infra/src/integrationTest/resources/rule-as-data/templates'].map((d) => resolve(repo, d));
const HANGUL = /[가-힣ᄀ-ᇿ㄰-㆏]/;

function walk(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) => (e.isDirectory() ? walk(join(dir, e.name)) : [join(dir, e.name)]));
}

function stringLeaves(node: unknown, out: string[]): string[] {
  if (typeof node === 'string') out.push(node);
  else if (Array.isArray(node)) node.forEach((n) => stringLeaves(n, out));
  else if (node !== null && typeof node === 'object') Object.values(node).forEach((n) => stringLeaves(n, out));
  return out;
}

interface Bundle {
  body: {
    fields: { label: string; render?: { unavailableText?: unknown } }[];
    layout: { title: string; sections: { label?: unknown }[]; signaturePage?: unknown };
  };
}

/** 서식 번들의 사람에게 보이는 문구 전부(LabelLiteralScanTest.templateLabels와 같은 정의). */
function templateLabels(): Set<string> {
  const labels = new Set<string>();
  let bundles = 0;
  for (const dir of TEMPLATE_DIRS) {
    for (const f of walk(dir).filter((p) => p.endsWith('.bundle.json'))) {
      bundles++;
      const { body } = JSON.parse(readFileSync(f, 'utf8')) as Bundle;
      for (const field of body.fields) {
        labels.add(field.label);
        if (typeof field.render?.unavailableText === 'string') labels.add(field.render.unavailableText);
      }
      labels.add(body.layout.title);
      for (const s of body.layout.sections) if (typeof s.label === 'string') labels.add(s.label);
      stringLeaves(body.layout.signaturePage, []).forEach((s) => labels.add(s));
    }
  }
  expect(bundles).toBeGreaterThanOrEqual(3);
  return new Set([...labels].filter((l) => !l.startsWith('TODO(confirm#')));
}

const sources = SCANNED.flatMap(walk).filter((f) => f !== MESSAGES);

describe('screen text comes from template data or messages.ko.json only', () => {
  it('has no Hangul in screen sources outside messages.ko.json', () => {
    const hits: string[] = [];
    for (const f of sources) {
      const text = readFileSync(f, 'utf8');
      const rel = relative(web, f);
      if (/\.tsx?$/.test(f)) {
        if (literals(f, text).some((l) => HANGUL.test(l))) hits.push(rel);
      } else if (/\.(html|css)$/.test(f)) {
        if (HANGUL.test(text.replace(/<!--[\s\S]*?-->|\/\*[\s\S]*?\*\//g, ''))) hits.push(rel);
      } else if (!f.endsWith('.json')) {
        hits.push(`${rel} (unexpected file type)`);
      }
    }
    expect(sources.filter((f) => /\.tsx?$/.test(f)).length).toBeGreaterThan(0);
    expect(hits).toEqual([]);
  });

  it('has no template label written as a screen literal', () => {
    const labels = templateLabels();
    expect(labels.size).toBeGreaterThan(30);
    const hits = sources.filter((f) => /\.tsx?$/.test(f))
      .flatMap((f) => literals(f, readFileSync(f, 'utf8')).filter((l) => labels.has(l.trim())).map((l) => `${relative(web, f)}: ${l}`));
    expect(hits).toEqual([]);
  });

  it('keeps template labels out of messages.ko.json', () => {
    const labels = templateLabels();
    const values = stringLeaves(JSON.parse(readFileSync(MESSAGES, 'utf8')), []);
    expect(values.length).toBeGreaterThan(0);
    expect(values.filter((v) => labels.has(v.trim()))).toEqual([]);
  });

  it('reads strings, template parts, JSX text and attributes, regexes — not comments', () => {
    const src = [
      '// "주석1"',
      '/* "주석2" */',
      "const a = '가';",
      'const b = `나${a}다${a}라`;',
      'const r = /마/;',
      'const e = <p title="바">사 {a} 아</p>;',
    ].join('\n');
    expect(literals('x.tsx', src).map((s) => s.trim())).toEqual(['가', '나', '다', '라', '/마/', '바', '사', '아']);
  });
});
