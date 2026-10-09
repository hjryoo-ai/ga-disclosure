/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react';
import { mkdirSync, writeFileSync } from 'node:fs';
import { relative, resolve } from 'node:path';
import { defineConfig, type Plugin } from 'vite';

const web = import.meta.dirname;
const repo = resolve(web, '..');

/**
 * 진입점마다 실제로 묶인 모듈 목록을 build/web/bundle-modules.json으로 남긴다(배포 산출물 밖).
 * scripts/scan-dist.mjs가 고객 서명 번들의 의존 그래프에 react·라우터·직원 화면 코드가 없음을 이것으로 단언한다(계획 ⑤).
 */
function bundleModules(): Plugin {
  return {
    name: 'ga-bundle-modules',
    apply: 'build',
    generateBundle(_options, bundle) {
      const chunks: Record<string, { entry: string | null; imports: string[]; modules: string[] }> = {};
      for (const [fileName, out] of Object.entries(bundle)) {
        if (out.type === 'chunk') {
          chunks[fileName] = {
            entry: out.isEntry ? out.name : null,
            imports: [...out.imports, ...out.dynamicImports],
            modules: out.moduleIds.map((id) => relative(repo, id.replace(/\?.*$/, '').replace(/^\0/, ''))).sort(),
          };
        }
      }
      mkdirSync(resolve(web, 'build/web'), { recursive: true });
      writeFileSync(resolve(web, 'build/web/bundle-modules.json'), JSON.stringify(chunks, null, 2) + '\n');
    },
  };
}

export default defineConfig({
  root: resolve(web, 'src'),
  base: '/',
  publicDir: false,
  plugins: [react(), bundleModules()],
  server: { fs: { allow: [repo] } },
  build: {
    outDir: resolve(web, 'build/web/dist'),
    emptyOutDir: true,
    assetsDir: 'assets',
    // 폰트·이미지를 data: URI로 넣지 않는다(CSP font-src 'self', 같은 폰트 바이트를 해시 이름으로 서빙).
    assetsInlineLimit: 0,
    // modulepreload 폴리필은 인라인 스크립트가 될 수 있어 끈다(CSP script-src 'self').
    modulePreload: { polyfill: false },
    sourcemap: false,
    manifest: true,
    rolldownOptions: {
      input: {
        staff: resolve(web, 'src/staff/index.html'),
        sign: resolve(web, 'src/sign/index.html'),
      },
    },
  },
  test: {
    root: web,
    include: ['src/**/*.test.{ts,tsx}'],
    environment: 'jsdom',
    reporters: ['default', 'junit'],
    outputFile: { junit: resolve(web, 'build/reports/vitest/junit.xml') },
  },
});
