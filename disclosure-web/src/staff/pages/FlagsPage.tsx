// 준법 플래그 큐(listFlags): 필터(상태·유형·담당 역할 — 계약 enum)·쪽 넘김, 행마다 배정·해소(해소 코드 + 선택 검증 실행 ID).
// 검증 실행(VERIFY_TENANT)을 이 화면에서 접수·폴링하고 보고서를 보인 뒤 그 작업 ID를 해소 증거로 넣을 수 있다. 판단은 서버가 한다.
import { useCallback, useEffect, useState, type SubmitEvent } from 'react';
import type { components, operations } from '../../gen/disclosure-api';
import { staff } from '../../shared/messages.ko.json';
import { outcome, useApi, write, type Outcome } from '../api/useApi';
import { JobPanel } from '../components/JobPanel';
import { ProblemView } from '../components/ProblemView';
import { Choice, formText, label, ResultLine, useShown } from '../components/ui';

type Flag = components['schemas']['Flag'];
type Job = components['schemas']['Job'];
type Query = NonNullable<operations['listFlags']['parameters']['query']>;
const STATUSES: readonly components['schemas']['FlagStatus'][] = ['OPEN', 'RESOLVED'];
const ROLES: readonly components['schemas']['FlagAssignedRole'][] = ['COMPLIANCE', 'MANAGER'];

export function FlagsPage() {
  const api = useApi();
  const [query, setQuery] = useState<Query>({ limit: 50 });
  const [rows, setRows] = useState<Flag[]>([]);
  const [page, setPage] = useState<Outcome<components['schemas']['FlagPage']> | null>(null);
  const [verifyJob, setVerifyJob] = useState<Job | null>(null);
  const [shown, show] = useShown();

  const load = useCallback((q: Query, append: boolean) => {
    void api.GET('/api/v1/flags', { params: { query: q } }).then((r) => {
      const o = outcome(r);
      setPage(o);
      if (o.ok) setRows((prev) => append ? [...prev, ...o.data.items] : o.data.items);
    });
  }, [api]);
  useEffect(() => { load(query, false); }, [load, query]);

  const filter = (e: SubmitEvent<HTMLFormElement>) => {
    e.preventDefault();
    const data = new FormData(e.currentTarget);
    const status = STATUSES.find((s) => s === data.get('status'));
    const assignedRole = ROLES.find((s) => s === data.get('assignedRole'));
    const type = formText(data, 'type').trim();
    setQuery({ limit: 50, ...(status ? { status } : {}), ...(assignedRole ? { assignedRole } : {}), ...(type === '' ? {} : { type }) });
  };
  const assign = (e: SubmitEvent<HTMLFormElement>, flagId: string) => {
    e.preventDefault();
    const assignee = formText(new FormData(e.currentTarget), 'assignee');
    void write((key) => api.POST('/api/v1/flags/{flagId}/assign', { params: { path: { flagId }, header: { 'Idempotency-Key': key } }, body: { assignee } }))
      .then((o) => { if (show(o, staff.flags.assigned)) load(query, false); });
  };
  const resolve = (e: SubmitEvent<HTMLFormElement>, flagId: string) => {
    e.preventDefault();
    const data = new FormData(e.currentTarget);
    const resolutionCode = formText(data, 'resolutionCode');
    const verifyRunJobId = formText(data, 'verifyRunJobId').trim();
    void write((key) => api.POST('/api/v1/flags/{flagId}/resolve', {
      params: { path: { flagId }, header: { 'Idempotency-Key': key } },
      body: verifyRunJobId === '' ? { resolutionCode } : { resolutionCode, evidence: { verifyRunJobId } },
    })).then((o) => { if (show(o, staff.flags.resolved)) load(query, false); });
  };
  const runVerify = () => {
    void write((key) => api.POST('/api/v1/jobs/{kind}', { params: { path: { kind: 'VERIFY_TENANT' }, header: { 'Idempotency-Key': key } }, body: {} }))
      .then((o) => { if (show(o, staff.jobs.submitted) && o.ok) setVerifyJob(o.data); });
  };

  return (
    <>
      <h1>{staff.flags.title}</h1>
      <form onSubmit={filter} className="filters">
        <Choice id="f-status" name="status" legend={staff.flags.status} defaultValue=""
          options={[{ value: '', label: staff.common.any }, ...STATUSES.map((s) => ({ value: s, label: label(staff.flagStatus, s) }))]} />
        <Choice id="f-role" name="assignedRole" legend={staff.flags.assignedRole} defaultValue=""
          options={[{ value: '', label: staff.common.any }, ...ROLES.map((s) => ({ value: s, label: label(staff.role, s) }))]} />
        <label htmlFor="f-type">{staff.flags.type}</label>
        <input id="f-type" name="type" autoComplete="off" spellCheck={false} />
        <button type="submit">{staff.common.apply}</button>
      </form>
      <section aria-labelledby="h-verify" className="panel">
        <h2 id="h-verify">{staff.flags.verifyTitle}</h2>
        <p>{staff.flags.verifyHint}</p>
        <button type="button" onClick={runVerify}>{staff.flags.verifyRun}</button>
        {verifyJob === null ? null : <JobPanel key={verifyJob.jobId} job={verifyJob} />}
      </section>
      <ResultLine shown={shown} />
      {page === null ? <p>{staff.common.loading}</p> : !page.ok ? <ProblemView status={page.status} problem={page.problem} /> : rows.length === 0 ? <p>{staff.flags.empty}</p> : (
        <div className="table-wrap">
          <table>
            <caption>{staff.flags.caption}</caption>
            <thead>
              <tr>
                <th scope="col">{staff.flags.type}</th><th scope="col">{staff.flags.status}</th><th scope="col">{staff.flags.raisedAt}</th>
                <th scope="col">{staff.flags.disclosure}</th><th scope="col">{staff.flags.assign}</th><th scope="col">{staff.flags.resolve}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((f) => (
                <tr key={f.flagId} data-flag-id={f.flagId}>
                  <td><code>{f.type}</code></td>
                  <td>{label(staff.flagStatus, f.status)}</td>
                  <td>{f.raisedAt}</td>
                  <td>{f.disclosureNo ?? (f.disclosureId === null ? '—' : <code>{f.disclosureId.slice(0, 8)}</code>)}</td>
                  <td>
                    <form onSubmit={(e) => { assign(e, f.flagId); }} autoComplete="off">
                      <label htmlFor={`as-${f.flagId}`}>{staff.flags.assignee}</label>
                      <input id={`as-${f.flagId}`} name="assignee" autoComplete="off" spellCheck={false} />
                      <button type="submit">{staff.flags.assign}</button>
                    </form>
                  </td>
                  <td>
                    <form onSubmit={(e) => { resolve(e, f.flagId); }} autoComplete="off">
                      <label htmlFor={`rc-${f.flagId}`}>{staff.flags.resolutionCode}</label>
                      <input id={`rc-${f.flagId}`} name="resolutionCode" autoComplete="off" spellCheck={false} />
                      <label htmlFor={`vj-${f.flagId}`}>{staff.flags.verifyRunJobId}</label>
                      <input id={`vj-${f.flagId}`} name="verifyRunJobId" autoComplete="off" spellCheck={false} />
                      <button type="submit">{staff.flags.resolve}</button>
                    </form>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {page?.ok && page.data.next !== null ? (
        <button type="button" onClick={() => { const after = page.data.next; if (after !== null) load({ ...query, after }, true); }}>{staff.common.more}</button>
      ) : null}
    </>
  );
}
