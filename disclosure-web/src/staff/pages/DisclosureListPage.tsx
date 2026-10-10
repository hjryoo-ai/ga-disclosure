// 확인서 목록(listDisclosures): 범위는 서버가 건다(설계사 자기 것·관리자 조직·준법 테넌트). 상태는 서버 값의 표기만.
import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import type { components } from '../../gen/disclosure-api';
import { staff } from '../../shared/messages.ko.json';
import { outcome, useApi, type Outcome } from '../api/useApi';
import { ProblemView } from '../components/ProblemView';
import { label } from '../components/ui';

type Page = components['schemas']['DisclosurePage'];

export function DisclosureListPage() {
  const api = useApi();
  const [page, setPage] = useState<Outcome<Page> | null>(null);
  useEffect(() => {
    let live = true;
    void api.GET('/api/v1/disclosures', { params: { query: { limit: 50 } } }).then((r) => { if (live) setPage(outcome(r)); });
    return () => { live = false; };
  }, [api]);
  return (
    <>
      <h1>{staff.list.title}</h1>
      {page === null ? <p>{staff.common.loading}</p> : !page.ok ? <ProblemView status={page.status} problem={page.problem} /> : page.data.items.length === 0
        ? <p>{staff.list.empty}</p>
        : (
          <div className="table-wrap">
            <table>
              <caption>{staff.list.caption}</caption>
              <thead>
                <tr>
                  <th scope="col">{staff.list.number}</th>
                  <th scope="col">{staff.list.status}</th>
                  <th scope="col">{staff.list.consultDate}</th>
                  <th scope="col">{staff.list.group}</th>
                  <th scope="col">{staff.list.agent}</th>
                </tr>
              </thead>
              <tbody>
                {page.data.items.map((d) => (
                  <tr key={d.disclosureId}>
                    <td><Link to={`/staff/disclosures/${d.disclosureId}`}>{d.disclosureNo ?? `${staff.list.draftOf} ${d.disclosureId.slice(0, 8)}`}</Link></td>
                    <td>{label(staff.status, d.status)}</td>
                    <td>{d.consultDate}</td>
                    <td><code>{d.groupCode}</code></td>
                    <td><code>{d.agentId}</code></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
    </>
  );
}
