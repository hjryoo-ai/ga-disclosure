// 직원 화면 공통 조각: 절·결과 줄. 결과 줄은 서버 응답을 그대로 보인다(성공 문구 또는 Problem) — 판단하지 않는다.
import { useState, type ReactNode } from 'react';
import { staff } from '../../shared/messages.ko.json';
import type { Outcome } from '../api/useApi';
import { ProblemView } from './ProblemView';

export function Section({ id, title, children }: { id: string; title: string; children: ReactNode }) {
  return (
    <section aria-labelledby={`h-${id}`} className="panel">
      <h2 id={`h-${id}`}>{title}</h2>
      {children}
    </section>
  );
}

export type Shown = { ok: true; text: string; replayed: boolean } | { ok: false; status: number; problem: Outcome<unknown> & { ok: false } };

export function useShown(): [Shown | null, (o: Outcome<unknown>, okText: string) => boolean] {
  const [shown, setShown] = useState<Shown | null>(null);
  const show = (o: Outcome<unknown>, okText: string): boolean => {
    setShown(o.ok ? { ok: true, text: okText, replayed: o.replayed } : { ok: false, status: o.status, problem: o });
    return o.ok;
  };
  return [shown, show];
}

export function ResultLine({ shown }: { shown: Shown | null }) {
  return (
    <div aria-live="polite" className="result">
      {shown === null ? null : shown.ok
        ? <p className="ok">{shown.text}{shown.replayed ? ` (${staff.common.replayed})` : ''}</p>
        : <ProblemView status={shown.problem.status} problem={shown.problem.problem} />}
    </div>
  );
}

/** 서버가 준 코드의 표기: 사전(messages 묶음)에 있으면 문구, 없으면 코드 그대로. */
export function label(dict: Readonly<Record<string, string>>, code: string): string {
  return dict[code] ?? code;
}

/** 폼 칸의 문자열 값(파일 칸·없는 칸은 빈 문자열). */
export function formText(data: FormData, name: string): string {
  const v = data.get(name);
  return typeof v === 'string' ? v : '';
}
