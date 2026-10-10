// 단계 명령(설계사): 비교·등급 요청·검증(단계 선택 — 계약 enum)·봉인·재기준·초안 폐기. 결과는 서버 응답 그대로(검증 결과 목록 포함).
// 관리자는 같은 검증 결과 행에서 예외 승인을 보낸다(대상 해시가 있는 행 — 해시가 없으면 요청을 만들 수 없다).
import { useState, type SubmitEvent } from 'react';
import type { components } from '../../../gen/disclosure-api';
import { staff } from '../../../shared/messages.ko.json';
import { useApi, write } from '../../api/useApi';
import { Choice, formText, label, ResultLine, Section, useShown } from '../../components/ui';

const STAGES = ['COMPARE', 'GRADE', 'REASON', 'SEAL'] as const;
type Results = components['schemas']['ValidationReceipt']['results'];

export function StepsSection({ id, reload }: { id: string; reload: () => void }) {
  const api = useApi();
  const [shown, show] = useShown();
  const [results, setResults] = useState<Results | null>(null);
  const opts = (key: string) => ({ params: { path: { disclosureId: id }, header: { 'Idempotency-Key': key } } });
  const done = (text: string) => (o: Parameters<typeof show>[0]) => { if (show(o, text)) reload(); };

  const validate = (e: SubmitEvent<HTMLFormElement>) => {
    e.preventDefault();
    const stage = STAGES.find((s) => s === formText(new FormData(e.currentTarget), 'stage')) ?? STAGES[0];
    void write((key) => api.POST('/api/v1/disclosures/{disclosureId}/validate', { ...opts(key), body: { stage } })).then((o) => {
      show(o, staff.steps.validated);
      setResults(o.ok ? o.data.results : null);
    });
  };
  // 예외 승인(관리자): 검증 결과 행의 규칙·대상 해시 그대로 + 사유. 승인 가능 여부(오버라이드 가능·실패)는 서버가 판단한다.
  const approve = (e: SubmitEvent<HTMLFormElement>, ruleId: string, subjectHash: string) => {
    e.preventDefault();
    const reason = formText(new FormData(e.currentTarget), 'reason');
    void write((key) => api.POST('/api/v1/disclosures/{disclosureId}/exception-approvals', { ...opts(key), body: { ruleId, subjectHash, reason } }))
      .then(done(staff.steps.exceptionApproved));
  };
  const abandon = (e: SubmitEvent<HTMLFormElement>) => {
    e.preventDefault();
    const reasonCode = formText(new FormData(e.currentTarget), 'reasonCode');
    void write((key) => api.POST('/api/v1/disclosures/{disclosureId}/abandon', { ...opts(key), body: { reasonCode } })).then(done(staff.steps.abandoned));
  };

  return (
    <Section id="steps" title={staff.steps.title}>
      <button type="button" onClick={() => { void write((k) => api.POST('/api/v1/disclosures/{disclosureId}/compare', opts(k))).then(done(staff.steps.compared)); }}>
        {staff.steps.compare}</button>
      <button type="button" onClick={() => { void write((k) => api.POST('/api/v1/disclosures/{disclosureId}/grades', opts(k))).then(done(staff.steps.graded)); }}>
        {staff.steps.grades}</button>
      <button type="button" onClick={() => { void write((k) => api.POST('/api/v1/disclosures/{disclosureId}/seal', opts(k))).then(done(staff.steps.sealed)); }}>
        {staff.steps.seal}</button>
      <button type="button" onClick={() => { void write((k) => api.POST('/api/v1/disclosures/{disclosureId}/rebase', opts(k))).then(done(staff.steps.rebased)); }}>
        {staff.steps.rebase}</button>
      <form onSubmit={validate}>
        <Choice id="v-stage" name="stage" legend={staff.steps.stage} defaultValue="SEAL"
          options={STAGES.map((s) => ({ value: s, label: label(staff.stage, s) }))} />
        <button type="submit">{staff.steps.validate}</button>
      </form>
      {results !== null && (
        <div className="table-wrap">
          <table>
            <caption>{staff.steps.resultsCaption}</caption>
            <thead><tr><th scope="col">{staff.steps.rule}</th><th scope="col">{staff.steps.passed}</th><th scope="col">{staff.steps.overridable}</th><th scope="col">{staff.steps.exception}</th></tr></thead>
            <tbody>
              {results.map((r) => (
                <tr key={r.ruleId}>
                  <td><code>{r.ruleId}</code></td><td>{r.passed ? staff.common.yes : staff.common.no}</td><td>{r.overridable ? staff.common.yes : staff.common.no}</td>
                  <td>{r.subjectHash === null ? '—' : (
                    <form onSubmit={(e) => { approve(e, r.ruleId, r.subjectHash ?? ''); }} autoComplete="off">
                      <label htmlFor={`ex-${r.ruleId}`}>{staff.steps.exceptionReason}</label>
                      <textarea id={`ex-${r.ruleId}`} name="reason" autoComplete="off" />
                      <button type="submit">{staff.steps.exceptionApprove}</button>
                    </form>
                  )}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <form onSubmit={abandon} autoComplete="off">
        <label htmlFor="ab-code">{staff.steps.abandonCode}</label>
        <input id="ab-code" name="reasonCode" autoComplete="off" />
        <button type="submit">{staff.steps.abandon}</button>
      </form>
      <ResultLine shown={shown} />
    </Section>
  );
}
