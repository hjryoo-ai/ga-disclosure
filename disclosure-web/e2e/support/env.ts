// 하네스가 남긴 실행 정보(build/e2e): 환경(env.json — 포트·컨테이너 이름), 허구 센티널(sentinels.json — run.mjs가 실행마다 무작위로 만든다).
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

export const OUT = resolve(import.meta.dirname, '../../build/e2e');
export const RUN = resolve(OUT, 'run');
/** e2e-demo 스크린샷(git 무시 — build/). */
export const DEMO_SHOTS = resolve(import.meta.dirname, '../../build/demo/phase7');

export interface Env {
  id: string;
  pg: string;
  baseUrl: string;
  dir: string;
}

/** 실행마다 새로 만든 허구 개인정보(이름·전화·생년월일). 시험은 이 값을 화면에 입력하고, 누출 스캔은 이 값과 서명 토큰을 찾는다. */
export interface Sentinels {
  name: string;
  phone: string;
  birthDate: string;
}

export const env = (): Env => JSON.parse(readFileSync(resolve(OUT, 'env.json'), 'utf8')) as Env;
export const sentinels = (): Sentinels => JSON.parse(readFileSync(resolve(RUN, 'sentinels.json'), 'utf8')) as Sentinels;

/** 누출 스캔이 찾는 형태들: 이름, 전화(하이픈 있음·없음), 생년월일(두 형식). 실패 메시지에는 번호만 싣는다(D-6). */
export function sentinelForms(s: Sentinels): string[] {
  return [s.name, s.phone, s.phone.replaceAll('-', ''), s.birthDate, s.birthDate.replaceAll('-', '')];
}
