// 화면 코드 린트(Phase 7 G2·G4·G5): any 0, 수기 네트워크 호출 0, 브라우저 저장소 0, 콘솔 0.
// 예외는 두지 않는다 — 네트워크는 생성 클라이언트(openapi-fetch)만, 그 생성은 src/shared/api/의 두 파일만(no-restricted-imports).
import js from '@eslint/js';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';
import tseslint from 'typescript-eslint';

const NETWORK = 'Network calls go through the generated contract client only (Phase 7 G2).';
const STORAGE = 'Browser storage is forbidden — tokens and personal data stay in memory (Phase 7 G4·G5).';

const restrictedGlobals = [
  { name: 'fetch', message: NETWORK },
  { name: 'XMLHttpRequest', message: NETWORK },
  { name: 'WebSocket', message: NETWORK },
  { name: 'EventSource', message: NETWORK },
  { name: 'localStorage', message: STORAGE },
  { name: 'sessionStorage', message: STORAGE },
  { name: 'indexedDB', message: STORAGE },
  { name: 'caches', message: STORAGE },
  { name: 'cookieStore', message: STORAGE },
];
const restrictedProperties = [
  ...['window', 'globalThis', 'self'].flatMap((object) => [
    ...['fetch', 'XMLHttpRequest', 'WebSocket', 'EventSource'].map((property) => ({ object, property, message: NETWORK })),
    ...['localStorage', 'sessionStorage', 'indexedDB', 'caches', 'cookieStore'].map((property) => ({ object, property, message: STORAGE })),
  ]),
  { object: 'navigator', property: 'sendBeacon', message: NETWORK },
  { object: 'navigator', property: 'serviceWorker', message: NETWORK },
  { object: 'document', property: 'cookie', message: STORAGE },
];
const clientOnly = { name: 'openapi-fetch', message: 'Create clients only in src/shared/api/ (Phase 7 G2).' };

export default tseslint.config(
  { ignores: ['build/**', 'node_modules/**', 'src/gen/**', 'test-results/**', 'playwright-report/**'] },
  js.configs.recommended,
  ...tseslint.configs.strictTypeChecked,
  {
    languageOptions: {
      parserOptions: { projectService: true, tsconfigRootDir: import.meta.dirname },
    },
    rules: {
      '@typescript-eslint/no-explicit-any': 'error',
      '@typescript-eslint/restrict-template-expressions': ['error', { allowNumber: true }],
    },
  },
  {
    files: ['eslint.config.js', 'scripts/**/*.mjs'],
    extends: [tseslint.configs.disableTypeChecked],
    languageOptions: { globals: globals.node },
  },
  {
    files: ['src/**/*.{ts,tsx}'],
    // 시험 원천은 브라우저 금지 규칙 밖이다(저장소·쿠키가 비었는지 직접 들여다본다) — 배포 번들에 들어가지 않는다(scan-dist가 모듈 목록으로 본다).
    ignores: ['src/test/**', 'src/**/*.test.{ts,tsx}'],
    languageOptions: { globals: globals.browser },
    plugins: { 'react-hooks': reactHooks },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'no-console': 'error',
      'no-restricted-globals': ['error', ...restrictedGlobals],
      'no-restricted-properties': ['error', ...restrictedProperties],
      'no-restricted-imports': ['error', { paths: [clientOnly], patterns: [{ group: ['node:*'], message: 'Browser code.' }] }],
    },
  },
  {
    // 고객 서명 번들: 프레임워크·라우터·상태 관리·인증 라이브러리 0, 직원 화면 코드 0(계획 ⑤).
    files: ['src/sign/**/*.ts', 'src/oidc-callback/**/*.ts', 'src/shared/**/*.ts'],
    rules: {
      'no-restricted-imports': ['error', {
        paths: [clientOnly],
        patterns: [
          { group: ['react', 'react-*', 'react/*', 'scheduler'], message: 'The public sign bundle has no framework (plan ⑤).' },
          { group: ['**/staff/**', '../staff/*'], message: 'The public sign bundle does not import staff code.' },
          { group: ['node:*'], message: 'Browser code.' },
        ],
      }],
    },
  },
  // 생성 클라이언트를 만드는 두 파일만 openapi-fetch를 가져온다(마지막 블록 — 위 금지를 이 파일들에서만 덮는다).
  {
    files: ['src/shared/api/*.ts'],
    rules: {
      'no-restricted-imports': ['error', {
        patterns: [
          { group: ['react', 'react-*', 'react/*', 'scheduler'], message: 'The public sign bundle has no framework (plan ⑤).' },
          { group: ['node:*'], message: 'Browser code.' },
        ],
      }],
    },
  },
);
