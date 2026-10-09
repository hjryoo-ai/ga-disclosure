// 회수율 스냅샷(준법·관리자, listCollectionRates): 기간(달)·조직 경로. 정의 문구는 서버 응답의 definitionText 그대로(내부 지표 — 규제 정의 없음).
// 만분율(rateBp)은 서버 정수이고, 표시는 정수 몫·나머지로만 쓴다(소수 연산 없음).
import { useState, type SubmitEvent } from 'react';
import type { components } from '../../gen/disclosure-api';
import { staff } from '../../shared/messages.ko.json';
import { outcome, useApi, type Outcome } from '../api/useApi';
import { ProblemView } from '../components/ProblemView';
import { formText, label } from '../components/ui';

type Rates = components['schemas']['CollectionRateList'];

/** 만분율 정수 → "12.34%" (정수 몫·나머지). */
export function bpText(bp: number | null): string {
  if (bp === null) return '—';
  return `${Math.trunc(bp / 100)}.${String(bp % 100).padStart(2, '0')}%`;
}

export function CollectionRatesPage() {
  const api = useApi();
  const [rates, setRates] = useState<Outcome<Rates> | null>(null);

  const search = (e: SubmitEvent<HTMLFormElement>) => {
    e.preventDefault();
    const data = new FormData(e.currentTarget);
    const from = formText(data, 'from');
    const to = formText(data, 'to');
    const orgPath = formText(data, 'orgPath').trim();
    void api.GET('/api/v1/collection-rates', { params: { query: orgPath === '' ? { from, to } : { from, to, orgPath } } }).then((r) => { setRates(outcome(r)); });
  };

  return (
    <>
      <h1>{staff.rates.title}</h1>
      <form onSubmit={search} className="filters" autoComplete="off">
        <label htmlFor="r-from">{staff.rates.from}</label>
        <input id="r-from" name="from" type="month" required />
        <label htmlFor="r-to">{staff.rates.to}</label>
        <input id="r-to" name="to" type="month" required />
        <label htmlFor="r-org">{staff.rates.orgPath}</label>
        <input id="r-org" name="orgPath" autoComplete="off" spellCheck={false} />
        <button type="submit">{staff.common.apply}</button>
      </form>
      {rates === null ? null : !rates.ok ? <ProblemView status={rates.status} problem={rates.problem} /> : (
        <>
          <p className="note" data-testid="rate-definition">{rates.data.definitionText} <code>{rates.data.definition}</code></p>
          {rates.data.items.length === 0 ? <p>{staff.rates.empty}</p> : (
            <div className="table-wrap">
              <table>
                <caption>{staff.rates.caption}</caption>
                <thead>
                  <tr>
                    <th scope="col">{staff.rates.month}</th><th scope="col">{staff.rates.orgPath}</th><th scope="col">{staff.rates.formula}</th>
                    <th scope="col">{staff.rates.numerator}</th><th scope="col">{staff.rates.denominator}</th><th scope="col">{staff.rates.rate}</th>
                  </tr>
                </thead>
                <tbody>
                  {rates.data.items.map((r) => (
                    <tr key={r.snapshotId}>
                      <td>{r.periodMonth}</td><td><code>{r.orgPath}</code></td><td>{label(staff.rateFormula, r.formula)}</td>
                      <td>{r.numerator}</td><td>{r.denominator}</td><td>{bpText(r.rateBp)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </>
  );
}
