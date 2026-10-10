// 작업(준법): 목록·접수(종류 — 계약 enum 전수, 매개변수는 비운 칸을 보내지 않는다)·진행·보고서. 적용(apply)은 기본 꺼짐(dry-run).
// 종류별 허용 키는 서버가 판정한다(모르는 키 400) — 화면은 칸을 숨기지 않는다.
import { useCallback, useEffect, useState, type SubmitEvent } from 'react';
import type { components } from '../../gen/disclosure-api';
import { staff } from '../../shared/messages.ko.json';
import { outcome, useApi, write, type Outcome } from '../api/useApi';
import { JobPanel } from '../components/JobPanel';
import { ProblemView } from '../components/ProblemView';
import { formText, label, ResultLine, useShown } from '../components/ui';

type Job = components['schemas']['Job'];
type JobKind = components['schemas']['JobKind'];
type Params = components['schemas']['JobParams'];

// 계약 enum 전수(빠지거나 남으면 타입 오류).
const KIND_SET = {
  VERIFY_TENANT: 1, DESTROY_DRY_RUN: 1, DESTROY: 1, EXPIRE: 1, RECONCILE: 1, NOTIFY: 1, IDEMPOTENCY_PURGE: 1, FLAG_SLA_SWEEP: 1,
  CONTRACT_LINK_UNMATCHED_PURGE: 1, ABANDON_DRAFTS: 1, COLLECTION_RATE_SNAPSHOT: 1, RETENTION_RECOMPUTE: 1,
} as const satisfies Record<JobKind, 1>;
const KINDS = Object.keys(KIND_SET) as JobKind[];

export function JobsPage() {
  const api = useApi();
  const [rows, setRows] = useState<Job[]>([]);
  const [page, setPage] = useState<Outcome<components['schemas']['JobPage']> | null>(null);
  const [selected, setSelected] = useState<Job | null>(null);
  const [shown, show] = useShown();

  const load = useCallback((after: string | null) => {
    void api.GET('/api/v1/jobs', { params: { query: after === null ? { limit: 50 } : { limit: 50, after } } }).then((r) => {
      const o = outcome(r);
      setPage(o);
      if (o.ok) setRows((prev) => after === null ? o.data.items : [...prev, ...o.data.items]);
    });
  }, [api]);
  useEffect(() => { load(null); }, [load]);

  const submit = (e: SubmitEvent<HTMLFormElement>) => {
    e.preventDefault();
    const data = new FormData(e.currentTarget);
    const kind = KINDS.find((k) => k === data.get('kind')) ?? 'VERIFY_TENANT';
    const pick = (k: string) => formText(data, k).trim();
    const limit = pick('limit');
    const params: Params = {
      ...(limit === '' ? {} : { limit: Number.parseInt(limit, 10) }),
      ...(pick('asOf') === '' ? {} : { asOf: pick('asOf') }),
      ...(pick('periodMonth') === '' ? {} : { periodMonth: pick('periodMonth') }),
      ...(pick('ruleVersionId') === '' ? {} : { ruleVersionId: pick('ruleVersionId') }),
      ...(data.get('apply') === 'on' ? { apply: true } : {}),
    };
    void write((key) => api.POST('/api/v1/jobs/{kind}', { params: { path: { kind }, header: { 'Idempotency-Key': key } }, body: params }))
      .then((o) => { if (show(o, staff.jobs.submitted) && o.ok) { setSelected(o.data); load(null); } });
  };

  return (
    <>
      <h1>{staff.jobs.title}</h1>
      <form onSubmit={submit} autoComplete="off" className="panel">
        <fieldset>
          <legend>{staff.jobs.submit}</legend>
          <label htmlFor="j-kind">{staff.jobs.kind}</label>
          <select id="j-kind" name="kind" defaultValue="VERIFY_TENANT">
            {KINDS.map((k) => <option key={k} value={k}>{k}</option>)}
          </select>
          <label htmlFor="j-limit">{staff.jobs.limit}</label>
          <input id="j-limit" name="limit" inputMode="numeric" pattern="[0-9]*" autoComplete="off" />
          <label htmlFor="j-asof">{staff.jobs.asOf}</label>
          <input id="j-asof" name="asOf" autoComplete="off" spellCheck={false} />
          <label htmlFor="j-month">{staff.jobs.periodMonth}</label>
          <input id="j-month" name="periodMonth" inputMode="numeric" pattern="[0-9]{4}-[0-9]{2}" autoComplete="off" spellCheck={false} />
          <label htmlFor="j-rule">{staff.jobs.ruleVersionId}</label>
          <input id="j-rule" name="ruleVersionId" autoComplete="off" spellCheck={false} />
          <div className="inline">
            <input id="j-apply" name="apply" type="checkbox" />
            <label htmlFor="j-apply">{staff.jobs.apply}</label>
          </div>
          <button type="submit">{staff.jobs.submitButton}</button>
        </fieldset>
      </form>
      <ResultLine shown={shown} />
      {selected === null ? null : <JobPanel key={selected.jobId} job={selected} />}
      {page === null ? <p>{staff.common.loading}</p> : !page.ok ? <ProblemView status={page.status} problem={page.problem} /> : rows.length === 0 ? <p>{staff.jobs.empty}</p> : (
        <div className="table-wrap">
          <table>
            <caption>{staff.jobs.caption}</caption>
            <thead>
              <tr><th scope="col">{staff.jobs.kind}</th><th scope="col">{staff.jobs.status}</th><th scope="col">{staff.jobs.requestedAt}</th><th scope="col">{staff.jobs.open}</th></tr>
            </thead>
            <tbody>
              {rows.map((j) => (
                <tr key={j.jobId}>
                  <td><code>{j.kind}</code></td>
                  <td>{label(staff.jobStatus, j.status)}</td>
                  <td>{j.requestedAt} · <code>{j.requestedBy}</code></td>
                  <td><button type="button" onClick={() => { setSelected(j); }}>{staff.jobs.open}</button></td>
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
