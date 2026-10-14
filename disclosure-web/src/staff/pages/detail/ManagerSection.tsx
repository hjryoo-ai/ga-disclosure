// 관리자: 확인서의 플래그(listDisclosureFlags — 설계사에게는 서버가 설계사 가시 행만 준다, Q1)를 체크해 관리자 확인(서명), 종이 스캔 검토,
// 무효(사유 코드 — 룰 어휘에서 고른다 + 선택 텍스트), 완료. 무엇을 확인해야 하는지·완료 가능한지는 서버가 판단한다(G3) — 체크 목록은 서버가 준 행 그대로다.
import { type SubmitEvent } from 'react';
import type { components } from '../../../gen/disclosure-api';
import { staff } from '../../../shared/messages.ko.json';
import { useApi, write, type Outcome } from '../../api/useApi';
import { ProblemView } from '../../components/ProblemView';
import { useCodes } from '../../api/vocabulary';
import { CodeChoice, formText, label, ResultLine, Section, useShown } from '../../components/ui';

type FlagList = components['schemas']['FlagList'];

export function ManagerSection({ id, flags, reload }: { id: string; flags: Outcome<FlagList> | null; reload: () => void }) {
  const api = useApi();
  const [shown, show] = useShown();
  const voidReasons = useCodes((v) => v.voidReasons);
  const opts = (key: string) => ({ params: { path: { disclosureId: id }, header: { 'Idempotency-Key': key } } });
  const done = (text: string) => (o: Outcome<unknown>) => { if (show(o, text)) reload(); };

  const confirm = (e: SubmitEvent<HTMLFormElement>) => {
    e.preventDefault();
    const acknowledgedFlags = new FormData(e.currentTarget).getAll('ack').map(String);
    void write((key) => api.POST('/api/v1/disclosures/{disclosureId}/manager-confirmation', { ...opts(key), body: { acknowledgedFlags } }))
      .then(done(staff.manager.confirmed));
  };
  const voidIt = (e: SubmitEvent<HTMLFormElement>) => {
    e.preventDefault();
    const data = new FormData(e.currentTarget);
    const reasonCode = formText(data, 'reasonCode');
    const reasonText = formText(data, 'reasonText');
    void write((key) => api.POST('/api/v1/disclosures/{disclosureId}/void', { ...opts(key), body: reasonText === '' ? { reasonCode } : { reasonCode, reasonText } }))
      .then(done(staff.manager.voided));
  };

  return (
    <Section id="manager" title={staff.manager.title}>
      <form onSubmit={confirm}>
        <fieldset>
          <legend>{staff.manager.flags}</legend>
          {flags === null ? <p>{staff.common.loading}</p> : !flags.ok ? <ProblemView status={flags.status} problem={flags.problem} />
            : flags.data.items.length === 0 ? <p data-testid="no-flags">{staff.manager.noFlags}</p>
              : (
                <ul className="checks" data-testid="disclosure-flags">
                  {flags.data.items.map((f) => (
                    <li key={f.flagId}>
                      <input type="checkbox" id={`ack-${f.flagId}`} name="ack" value={f.flagId} />
                      <label htmlFor={`ack-${f.flagId}`}><code>{f.type}</code> · {label(staff.flagStatus, f.status)} · {f.raisedAt}</label>
                    </li>
                  ))}
                </ul>
              )}
        </fieldset>
        <button type="submit">{staff.manager.confirm}</button>
      </form>
      <button type="button" onClick={() => { void write((k) => api.POST('/api/v1/disclosures/{disclosureId}/paper-scan-review', opts(k))).then(done(staff.manager.reviewed)); }}>
        {staff.manager.paperScanReview}</button>
      <button type="button" onClick={() => { void write((k) => api.POST('/api/v1/disclosures/{disclosureId}/complete', opts(k))).then(done(staff.manager.completed)); }}>
        {staff.manager.complete}</button>
      <form onSubmit={voidIt} autoComplete="off">
        <fieldset>
          <legend>{staff.manager.void}</legend>
          <CodeChoice id="void-code" name="reasonCode" legend={staff.manager.reasonCode} codes={voidReasons} />
          <label htmlFor="void-text">{staff.manager.reasonText}</label>
          <textarea id="void-text" name="reasonText" autoComplete="off" />
          <button type="submit">{staff.manager.voidSubmit}</button>
        </fieldset>
      </form>
      <ResultLine shown={shown} />
    </Section>
  );
}
