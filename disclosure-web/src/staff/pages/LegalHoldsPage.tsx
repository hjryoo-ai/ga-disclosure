// 법적 보존(준법): 목록·걸기(확인서 ID 또는 고객 가명 참조 중 정확히 하나 — 서버가 판정)·해제(사유 코드 — 둘 다 룰 어휘에서 고른다, G9).
// 해제는 건 사람과 다른 준법 담당자만 할 수 있다(4-eyes, 서버 판정) — 화면은 버튼을 막지 않고 거부 코드를 보인다.
import { useCallback, useEffect, useState, type SubmitEvent } from 'react';
import type { components } from '../../gen/disclosure-api';
import { staff } from '../../shared/messages.ko.json';
import { outcome, useApi, write, type Outcome } from '../api/useApi';
import { ProblemView } from '../components/ProblemView';
import { useCodes } from '../api/vocabulary';
import { CodeChoice, formText, ResultLine, useShown } from '../components/ui';

type Hold = components['schemas']['LegalHold'];
type Receipt = components['schemas']['LegalHoldReceipt'];

export function LegalHoldsPage() {
  const api = useApi();
  const [rows, setRows] = useState<Hold[]>([]);
  const [page, setPage] = useState<Outcome<components['schemas']['LegalHoldPage']> | null>(null);
  const [receipt, setReceipt] = useState<Receipt | null>(null);
  const [shown, show] = useShown();
  const holdReasons = useCodes((v) => v.legalHoldReasons);
  const releaseReasons = useCodes((v) => v.legalHoldReleaseReasons);

  const load = useCallback((after: string | null) => {
    void api.GET('/api/v1/legal-holds', { params: { query: after === null ? { limit: 50 } : { limit: 50, after } } }).then((r) => {
      const o = outcome(r);
      setPage(o);
      if (o.ok) setRows((prev) => after === null ? o.data.items : [...prev, ...o.data.items]);
    });
  }, [api]);
  useEffect(() => { load(null); }, [load]);

  const place = (e: SubmitEvent<HTMLFormElement>) => {
    e.preventDefault();
    const form = e.currentTarget;
    const data = new FormData(form);
    const pick = (k: string) => formText(data, k).trim();
    const body = {
      reasonCode: pick('reasonCode'),
      ...(pick('disclosureId') === '' ? {} : { disclosureId: pick('disclosureId') }),
      ...(pick('customerRef') === '' ? {} : { customerRef: pick('customerRef') }),
      ...(pick('reasonText') === '' ? {} : { reasonText: pick('reasonText') }),
    };
    void write((key) => api.POST('/api/v1/legal-holds', { params: { header: { 'Idempotency-Key': key } }, body })).then((o) => {
      if (show(o, staff.holds.placed)) { setReceipt(o.ok ? o.data : null); form.reset(); load(null); }
    });
  };
  const release = (e: SubmitEvent<HTMLFormElement>, holdId: string) => {
    e.preventDefault();
    const reasonCode = formText(new FormData(e.currentTarget), 'reasonCode');
    void write((key) => api.POST('/api/v1/legal-holds/{holdId}/release', { params: { path: { holdId }, header: { 'Idempotency-Key': key } }, body: { reasonCode } }))
      .then((o) => { if (show(o, staff.holds.released)) { setReceipt(o.ok ? o.data : null); load(null); } });
  };

  return (
    <>
      <h1>{staff.holds.title}</h1>
      <p>{staff.holds.fourEyes}</p>
      <form onSubmit={place} autoComplete="off" className="panel">
        <fieldset>
          <legend>{staff.holds.place}</legend>
          <label htmlFor="h-disc">{staff.holds.disclosureId}</label>
          <input id="h-disc" name="disclosureId" autoComplete="off" spellCheck={false} />
          <label htmlFor="h-cref">{staff.holds.customerRef}</label>
          <input id="h-cref" name="customerRef" autoComplete="off" spellCheck={false} />
          <CodeChoice id="h-code" name="reasonCode" legend={staff.holds.reasonCode} codes={holdReasons} />
          <label htmlFor="h-text">{staff.holds.reasonText}</label>
          <textarea id="h-text" name="reasonText" autoComplete="off" />
          <button type="submit">{staff.holds.placeSubmit}</button>
        </fieldset>
      </form>
      <ResultLine shown={shown} />
      {receipt === null ? null : (
        <p data-testid="hold-receipt">
          <code>{receipt.holdId}</code> · {staff.holds.storageApplied} {receipt.storageApplied} · {staff.holds.storageFailed} {receipt.storageFailed}
          {receipt.storageUnsupported ? ` · ${staff.holds.storageUnsupported}` : ''}
        </p>
      )}
      {page === null ? <p>{staff.common.loading}</p> : !page.ok ? <ProblemView status={page.status} problem={page.problem} /> : rows.length === 0 ? <p>{staff.holds.empty}</p> : (
        <div className="table-wrap">
          <table>
            <caption>{staff.holds.caption}</caption>
            <thead>
              <tr>
                <th scope="col">{staff.holds.target}</th><th scope="col">{staff.holds.reasonCode}</th><th scope="col">{staff.holds.placedAt}</th>
                <th scope="col">{staff.holds.releasedAt}</th><th scope="col">{staff.holds.release}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((h) => (
                <tr key={h.holdId}>
                  <td><code>{h.disclosureId ?? h.customerRef ?? '—'}</code></td>
                  <td><code>{h.reasonCode}</code></td>
                  <td>{h.placedAt} · <code>{h.placedBy}</code></td>
                  <td>{h.releasedAt === null ? '—' : <>{h.releasedAt} · <code>{h.releasedBy}</code> · <code>{h.releaseReasonCode}</code></>}</td>
                  <td>
                    <form onSubmit={(e) => { release(e, h.holdId); }} autoComplete="off">
                      <CodeChoice id={`rl-${h.holdId}`} name="reasonCode" legend={staff.holds.releaseCode} codes={releaseReasons} />
                      <button type="submit">{staff.holds.release}</button>
                    </form>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {page?.ok && page.data.next !== null ? (
        <button type="button" onClick={() => { load(page.data.next); }}>{staff.common.more}</button>
      ) : null}
    </>
  );
}
