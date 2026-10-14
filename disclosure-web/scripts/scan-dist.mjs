// Vite 산출물 검사(webBuild 마지막 단계, 계획 ③·⑤, G9 빌드 쪽):
//  ① HTML: 인라인 스크립트·인라인 스타일·이벤트 속성 0, 참조는 전부 같은 출처 /assets/, 외부 출처 URL 0
//  ② CSS: url()은 /assets/만, @import 0
//  ③ 폰트: 번들된 폰트 바이트 = PDF 렌더러 폰트(disclosure-seal 자원) 바이트 — 같은 폰트(Q8). (Phase 8 ③-x-3) 폰트 바이트 합계 상한 = 측정값
//     4,128,612 + 여유 5%. 화면 폰트 서브셋(한글 완성형 11,172 + ASCII + 문장부호, FontBox TTFSubsetter)은 측정 결과 Regular 4.1%·Bold 3.9% 감소로
//     계획의 기준(10%) 미만이라 하지 않았다 — 화면 폰트는 렌더러 폰트 그대로(위 검사 불변). 원본 폰트에 한자 글리프가 없어 줄일 것이 거의 없다.
//  ④ 고객 서명 번들의 모듈 그래프에 react·라우터·직원 화면 코드 0, 소스맵 0
import { createHash } from 'node:crypto';
import { readdir, readFile } from 'node:fs/promises';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const web = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const dist = resolve(web, 'build/web/dist');
const fonts = resolve(web, '../disclosure-seal/src/main/resources/render/fonts');
const failures = [];
const sha256 = (b) => createHash('sha256').update(b).digest('hex');

async function walk(dir) {
  const out = [];
  for (const e of await readdir(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) out.push(...(await walk(p)));
    else out.push(p);
  }
  return out;
}

const files = await walk(dist);
const rel = (f) => relative(dist, f);
for (const html of ['staff/index.html', 'sign/index.html', 'oidc-callback/index.html']) {
  if (!files.some((f) => rel(f) === html)) failures.push(`missing ${html}`);
}
for (const f of files) {
  const name = rel(f);
  if (name.endsWith('.map')) failures.push(`${name}: source map in the output`);
  if (name.endsWith('.html')) {
    const text = await readFile(f, 'utf8');
    for (const m of text.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)) {
      if (!/\bsrc=/.test(m[1] ?? '') || (m[2] ?? '').trim() !== '') failures.push(`${name}: inline script`);
    }
    if (/<style\b/i.test(text)) failures.push(`${name}: inline <style>`);
    if (/\sstyle\s*=/i.test(text)) failures.push(`${name}: style attribute`);
    if (/\son[a-z]+\s*=/i.test(text)) failures.push(`${name}: event handler attribute`);
    if (/(https?:)?\/\/[a-z0-9.-]+\.[a-z]{2,}/i.test(text.replace(/<!--[\s\S]*?-->/g, ''))) failures.push(`${name}: external URL`);
    for (const m of text.matchAll(/\b(?:src|href)="([^"]*)"/g)) {
      if (!(m[1] ?? '').startsWith('/assets/')) failures.push(`${name}: reference outside /assets/: ${m[1]}`);
    }
  }
  if (name.endsWith('.css')) {
    const text = await readFile(f, 'utf8');
    if (/@import/i.test(text)) failures.push(`${name}: @import`);
    for (const m of text.matchAll(/url\(\s*['"]?([^'")]+)/g)) {
      if (!(m[1] ?? '').startsWith('/assets/')) failures.push(`${name}: url() outside /assets/: ${m[1]}`);
    }
  }
}

const bundledFonts = new Set();
for (const f of files.filter((f) => f.endsWith('.ttf'))) bundledFonts.add(sha256(await readFile(f)));
for (const font of ['NanumGothic-Regular.ttf', 'NanumGothic-Bold.ttf']) {
  if (!bundledFonts.has(sha256(await readFile(join(fonts, font))))) failures.push(`font ${font} is not bundled byte for byte`);
}
if (bundledFonts.size !== 2) failures.push(`expected exactly the 2 renderer fonts, found ${bundledFonts.size} .ttf files`);
const FONT_BYTES_CAP = 4_335_043;                                          // 4,128,612 × 1.05 (올림)
let fontBytes = 0;
for (const f of files.filter((f) => /\.(ttf|otf|woff2?)$/.test(f))) fontBytes += (await readFile(f)).length;
if (fontBytes > FONT_BYTES_CAP) failures.push(`font bytes ${fontBytes} exceed the cap ${FONT_BYTES_CAP}`);

const chunks = JSON.parse(await readFile(resolve(web, 'build/web/bundle-modules.json'), 'utf8'));
const signEntry = Object.entries(chunks).find(([, c]) => c.entry === 'sign');
if (!signEntry) {
  failures.push('no sign entry chunk');
} else {
  const seen = new Set();
  const queue = [signEntry[0]];
  const modules = [];
  while (queue.length > 0) {
    const name = queue.shift();
    if (seen.has(name)) continue;
    seen.add(name);
    const c = chunks[name];
    if (!c) continue;
    modules.push(...c.modules);
    queue.push(...c.imports);
  }
  const banned = /node_modules\/(react|react-dom|react-router|scheduler|@remix-run|cookie-es)\/|disclosure-web\/src\/staff\//;
  const tests = Object.values(chunks).flatMap((c) => c.modules).filter((m) => /\.test\.tsx?$|\/src\/test\//.test(m));
  for (const m of tests) failures.push(`test source in a bundle: ${m}`);
  for (const m of modules.filter((m) => banned.test(m))) failures.push(`sign bundle contains ${m}`);
  if (!modules.some((m) => m.includes('disclosure-web/src/sign/'))) failures.push('sign bundle module list is empty — the scan would pass vacuously');
}

if (failures.length > 0) {
  for (const f of failures) console.error(f);
  process.exit(1);
}
console.log(`dist scan ok (${files.length} files)`);
