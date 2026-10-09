// Problem.code → 사람 문구(지시문 §2 "단일 사전", 승인 Q4). 사전에 없는 코드는 코드 그대로 보인다 — 숨기지 않는다(G3).
// 문구에 치환자가 없다 — 거부 문구에 고객 정보를 넣을 자리가 없다.
import { problem } from './messages.ko.json';

const codes: Readonly<Record<string, string>> = problem.code;
const rejections: Readonly<Record<string, string>> = problem.rejection;

export function problemText(code: string): string {
  return codes[code] ?? code;
}

export function rejectionText(code: string): string {
  return rejections[code] ?? code;
}
