// E2E(Phase 7 계획 ⑦): ./gradlew :disclosure-web:e2e → e2e/run.mjs가 환경을 띄우고(env.mjs up) 이 설정으로 돌린 뒤 내린다.
// 서버 하나를 같이 쓰므로 직렬(worker 1). 트레이스·자동 스크린샷·비디오 끔(D-6 — 실패 산출물에 입력값이 실릴 수 있다). (Phase 8) HTML 보고서도 끔 —
// 단계 제목이 키보드 입력값을 그대로 싣는다(`Type "<값>"` — 통과한 시험도). list는 단계를 찍지 않고 junit은 단계가 없다. 실패한 시험의 페이지 스냅샷
// (error-context.md)은 고정 장치가 입력 칸을 지운 뒤 찍히고, run.mjs가 build/e2e 전체를 센티널로 스캔한다.
// 프로젝트: desktop·mobile(같은 흐름, 각자 고객·확인서) → after(재전송 NOOP·누출 합계·안 쓰인 axe 예외 — 둘 다 끝난 뒤).
import { defineConfig, devices } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const out = resolve(import.meta.dirname, 'build/e2e');
interface HarnessEnv { baseUrl?: string; mode?: 'kind'; hosts?: string[]; spki?: string[] }
const harness = ((): HarnessEnv => {
  try {
    return JSON.parse(readFileSync(resolve(out, 'env.json'), 'utf8')) as HarnessEnv;
  } catch {
    return {};
  }
})();
const baseURL = harness.baseUrl ?? undefined;
// (Phase 8) 클러스터 대상: 데모 진입점 이름을 127.0.0.1(kind 노드 포트)로, 인증서 오류 허용은 두 진입점 인증서의 공개키(SPKI)만 — 다른 인증서는 그대로 거부
const launchOptions = harness.mode === 'kind' ? {
  args: [
    `--host-resolver-rules=${(harness.hosts ?? []).map((h) => `MAP ${h} 127.0.0.1`).join(',')}`,
    `--ignore-certificate-errors-spki-list=${(harness.spki ?? []).join(',')}`,
  ],
} : {};

export default defineConfig({
  testDir: 'e2e/specs',
  outputDir: resolve(out, 'test-results'),
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: true,
  timeout: 240_000,
  expect: { timeout: 20_000 },
  reporter: [['list'], ['junit', { outputFile: resolve(out, 'junit.xml') }]],
  use: { baseURL, launchOptions, trace: 'off', screenshot: 'off', video: 'off', locale: 'ko-KR', timezoneId: 'Asia/Seoul', actionTimeout: 20_000 },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'] }, testIgnore: ['after.spec.ts'] },
    // DEMO2 CHAIN_BROKEN은 한 번만 해소할 수 있는 사건이라 데스크톱에서만 돈다(건너뛰기가 아니라 대상 밖).
    { name: 'mobile', use: { ...devices['Pixel 7'] }, testIgnore: ['after.spec.ts', 'chain.spec.ts'] },
    { name: 'after', testMatch: ['after.spec.ts'], dependencies: ['desktop', 'mobile'] },
  ],
});
