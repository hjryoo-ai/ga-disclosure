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

/**
 * 작은 닫힌 선택지(계약 enum): 라디오 묶음. 선택 상자 대신 쓴다 — 화살표로 고르는 동작이 플랫폼마다 같고(macOS Chromium의 닫힌 select는
 * 화살표가 팝업을 연다) 한 화면에 선택지가 다 보인다. 폼 값은 선택 상자와 같다(name = 고른 value).
 */
export function Choice({ id, name, legend, options, defaultValue }: {
  id: string; name: string; legend: string; options: readonly { value: string; label: string }[]; defaultValue: string;
}) {
  return (
    <fieldset className="choice" id={id}>
      <legend>{legend}</legend>
      {options.map((o) => (
        <div className="inline" key={o.value}>
          <input type="radio" id={`${id}-${o.value === '' ? 'any' : o.value}`} name={name} value={o.value} defaultChecked={o.value === defaultValue} />
          <label htmlFor={`${id}-${o.value === '' ? 'any' : o.value}`}>{o.label}</label>
        </div>
      ))}
    </fieldset>
  );
}

/** 룰 어휘의 닫힌 목록 하나(Phase 8 G9): 라디오 묶음, 기본 선택 없음(어느 칸도 미리 고르지 않는다). 폼 값은 고른 코드. */
export function CodeChoice({ id, name, legend, codes }: { id: string; name: string; legend: string; codes: readonly { code: string; label: string }[] }) {
  return <Choice id={id} name={name} legend={legend} defaultValue="" options={codes.map((c) => ({ value: c.code, label: c.label }))} />;
}

/** 여러 코드를 고르는 목록(추천사유 — 절대 규칙 7): 체크 상자, 미리 체크된 칸 없음. 폼 값은 고른 코드들(같은 name). */
export function CodeChecks({ id, name, legend, codes }: { id: string; name: string; legend: string; codes: readonly { code: string; label: string }[] }) {
  return (
    <fieldset className="choice" id={id}>
      <legend>{legend}</legend>
      {codes.map((c) => (
        <div className="inline" key={c.code}>
          <input type="checkbox" id={`${id}-${c.code}`} name={name} value={c.code} />
          <label htmlFor={`${id}-${c.code}`}>{c.label}</label>
        </div>
      ))}
    </fieldset>
  );
}
