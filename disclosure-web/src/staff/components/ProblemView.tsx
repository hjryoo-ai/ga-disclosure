// Problem.code → 사전 문구(없으면 코드 그대로), 거부 코드마다 문구 + 코드, 형식 오류의 필드 이름. 고객 정보를 넣을 자리가 없다.
import { staff } from '../../shared/messages.ko.json';
import { problemText, rejectionText } from '../../shared/problem';
import type { ProblemBody } from '../api/useApi';

export function ProblemView({ status, problem }: { status: number; problem: ProblemBody | null }) {
  if (problem === null) {
    return <p role="alert" className="problem">{staff.common.networkFailed} ({status})</p>;
  }
  const rejections = problem.details?.rejections ?? [];
  return (
    <div role="alert" className="problem">
      <p>{problemText(problem.code)} <code>{problem.code}</code></p>
      {problem.details?.field !== undefined && <p>{staff.common.field}: <code>{problem.details.field}</code></p>}
      {rejections.length > 0 && (
        <ul>
          {rejections.map((r, i) => (
            <li key={i}>{rejectionText(r.code)} <code>{r.code}</code>{r.ruleId ? <> · <code>{r.ruleId}</code></> : null}</li>
          ))}
        </ul>
      )}
    </div>
  );
}
