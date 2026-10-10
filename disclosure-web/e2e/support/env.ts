// 하네스가 남긴 실행 정보(build/e2e): 환경(env.json — 포트·컨테이너 이름), 허구 센티널(sentinels.json — run.mjs가 실행마다 무작위로 만든다).
// (Phase 8) 클러스터 대상(env.mode = 'kind')과 로컬 하네스의 차이는 이 파일의 도우미가 가린다: 서명 화면의 출처(서명 호스트), 행 수 대조 psql(kubectl exec),
// 서버 로그(앱 파드 로그), 내부 진입점 호출(mTLS 클라이언트 인증서).
import { request as playwrightRequest } from '@playwright/test';
import { spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

export const OUT = resolve(import.meta.dirname, '../../build/e2e');
export const RUN = resolve(OUT, 'run');
/** e2e-demo 스크린샷(git 무시 — build/). */
export const DEMO_SHOTS = resolve(import.meta.dirname, '../../build/demo/phase7');

export interface Env {
  id: string;
  pg: string;
  baseUrl: string;
  internalUrl: string;
  dir: string;
  /** 클러스터 대상에만: 서명 호스트(로컬은 baseUrl과 같은 출처), kubectl(경로·문맥), 데모 PKI(공개 인증서·내부 진입점 클라이언트 키 경로). */
  mode?: 'kind';
  signBase?: string;
  kubectl?: string[];
  pki?: { ca: string; cert: string; key: string };
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

/** 고객 서명 화면의 기준 출처 — 클러스터는 서명 호스트(진입점 B), 로컬은 같은 출처. */
export const signBase = (): string => env().signBase ?? env().baseUrl;
/** 상대 경로(`/s#…`)를 서명 호스트의 주소로. 이미 절대 주소(통지가 준 링크)면 그대로. */
export const signTarget = (target: string): string => (target.startsWith('/') ? `${signBase()}${target}` : target);
/** 시험이 닿아도 되는 출처(직원·서명 — 로컬은 하나). */
export const origins = (): Set<string> => new Set([env().baseUrl, signBase()].map((u) => new URL(u).origin));

/** 슈퍼유저 psql(RLS 밖 — 행 수 대조): 로컬은 컨테이너, 클러스터는 DB 파드. */
export function psql(sql: string): string {
  const e = env();
  const args = ['psql', '-q', '-tA', '-U', 'postgres', '-d', 'disclosure', '-v', 'ON_ERROR_STOP=1'];
  const [cmd, ...pre] = e.mode === 'kind' ? [...(e.kubectl ?? []), '-n', 'ga-disclosure', 'exec', '-i', e.pg, '--'] : ['docker', 'exec', '-i', e.pg];
  const r = spawnSync(cmd, [...pre, ...args], { input: sql, encoding: 'utf8' });
  if (r.status !== 0) throw new Error(`psql failed: ${r.stderr.slice(0, 500)}`);
  return r.stdout.trim();
}

/** 이번 실행의 앱 로그: 로컬은 하네스가 쓰는 server.log, 클러스터는 앱 파드 로그를 모아 같은 자리에 쓴다(소유자 전용). */
export function serverLog(): string {
  const e = env();
  const file = resolve(OUT, 'server.log');
  if (e.mode !== 'kind') return readFileSync(file, 'utf8');
  const [cmd, ...pre] = e.kubectl ?? [];
  const r = spawnSync(cmd ?? 'kubectl', [...pre, '-n', 'ga-disclosure', 'logs', '-l', 'app.kubernetes.io/name=ga-app', '--all-containers', '--tail=-1',
    '--max-log-requests=10'], { encoding: 'utf8', maxBuffer: 256 * 1024 * 1024 });
  if (r.status !== 0) throw new Error(`kubectl logs failed: ${r.stderr.slice(0, 500)}`);
  writeFileSync(file, r.stdout, { mode: 0o600 });
  return r.stdout;
}

/** 내부 경로 POST(통지 작업 접수): 로컬은 내부 포트, 클러스터는 내부 진입점 C(mTLS — 데모 클라이언트 인증서, 이름·CA는 run.mjs가 시험 프로세스에 준다). */
export async function postInternal(path: string, headers: Record<string, string>): Promise<number> {
  const e = env();
  const context = await playwrightRequest.newContext(e.mode === 'kind' && e.pki !== undefined
    ? { clientCertificates: [{ origin: new URL(e.internalUrl).origin, certPath: e.pki.cert, keyPath: e.pki.key }] } : {});
  try {
    return (await context.post(`${e.internalUrl}${path}`, { headers })).status();
  } finally {
    await context.dispose();
  }
}
