// 접근성(G10): 화면마다 axe(wcag2a·wcag2aa·wcag21a·wcag21aa) 위반 0. 예외는 e2e/axe-exceptions.json(규칙·대상·사유)뿐이고,
// 목록 밖 위반은 그 자리에서 실패, 한 번도 쓰이지 않은 예외는 after 프로젝트가 실패시킨다(쓰인 예외를 build/e2e/axe/에 남긴다).
import AxeBuilder from '@axe-core/playwright';
import { expect, type Page, type TestInfo } from '@playwright/test';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { OUT } from './env';

export interface AxeException {
  rule: string;
  target: string;
  reason: string;
}

export const AXE_EXCEPTIONS = resolve(import.meta.dirname, '../axe-exceptions.json');
export const exceptions = (): AxeException[] => JSON.parse(readFileSync(AXE_EXCEPTIONS, 'utf8')) as AxeException[];

export async function axe(page: Page, screen: string, info: TestInfo): Promise<void> {
  const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  const allowed = exceptions();
  const used = new Set<string>();
  const unexpected: string[] = [];
  for (const v of result.violations) {
    for (const node of v.nodes) {
      const target = node.target.join(' ');
      const hit = allowed.find((e) => e.rule === v.id && e.target === target);
      if (hit === undefined) unexpected.push(`${v.id} @ ${target}`);
      else used.add(`${hit.rule} @ ${hit.target}`);
    }
  }
  const dir = resolve(OUT, 'axe');
  mkdirSync(dir, { recursive: true });
  writeFileSync(resolve(dir, `${info.project.name}-${screen}.json`), JSON.stringify({
    screen, project: info.project.name, passes: result.passes.length, violations: result.violations.length, used: [...used], unexpected,
  }, null, 2));
  expect(unexpected, `axe violations on ${screen}`).toEqual([]);
}
