// 작업 하나의 진행: 접수 영수증 → getJob 폴링(서버 상태 그대로) → 끝나면 보고서 바이트를 텍스트로 보인다(변환·판단 없음).
import { useEffect, useState } from 'react';
import type { components } from '../../gen/disclosure-api';
import { staff } from '../../shared/messages.ko.json';
import { outcome, useApi, type Outcome } from '../api/useApi';
import { ProblemView } from './ProblemView';
import { label } from './ui';

type Job = components['schemas']['Job'];
const POLL_MS = 1000;

export function JobPanel({ job }: { job: Job }) {
  const api = useApi();
  const [current, setCurrent] = useState<Job>(job);
  const [report, setReport] = useState<Outcome<string> | null>(null);
  const finished = current.finishedAt !== null;

  useEffect(() => {
    if (finished) return undefined;
    const timer = setTimeout(() => {
      void api.GET('/api/v1/jobs/{jobId}', { params: { path: { jobId: current.jobId } } }).then((r) => {
        const o = outcome(r);
        if (o.ok) setCurrent(o.data);
      });
    }, POLL_MS);
    return () => { clearTimeout(timer); };
  }, [api, current, finished]);

  useEffect(() => {
    if (!finished || current.reportSha256 === null) return;
    void api.GET('/api/v1/jobs/{jobId}/report', { params: { path: { jobId: current.jobId } }, parseAs: 'text' }).then((r) => { setReport(outcome(r)); });
  }, [api, current.jobId, current.reportSha256, finished]);

  return (
    <div className="job" data-testid="job" data-job-id={current.jobId}>
      <p>
        <code>{current.kind}</code> · <code>{current.jobId}</code> · <span data-testid="job-status">{label(staff.jobStatus, current.status)}</span>
        {current.errorCode === null ? null : <> · <code>{current.errorCode}</code></>}
      </p>
      {report === null ? null : report.ok
        ? <pre className="report" tabIndex={0} aria-label={staff.jobs.report}>{report.data}</pre>
        : <ProblemView status={report.status} problem={report.problem} />}
    </div>
  );
}
