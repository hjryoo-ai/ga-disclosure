// 확인서 상세(getDisclosure + getDisclosureTemplate + listDisclosureFlags): 라벨은 고정 서식의 응답에서만 온다(코드에 라벨 문자열 없음).
// 모든 동작 버튼은 상태와 무관하게 보인다 — 할 수 없는 동작은 서버가 거부하고 화면은 그 코드를 보인다(G3).
import { Fragment, useCallback, useEffect, useState } from 'react';
import { useParams } from 'react-router';
import type { components } from '../../gen/disclosure-api';
import { staff } from '../../shared/messages.ko.json';
import { outcome, useApi, type Outcome } from '../api/useApi';
import { ProblemView } from '../components/ProblemView';
import { label } from '../components/ui';
import { DocumentSection } from './detail/DocumentSection';
import { documentValue, ItemsTable } from './detail/fields';
import { ItemsSection } from './detail/ItemsSection';
import { ManagerSection } from './detail/ManagerSection';
import { RecommendationsSection } from './detail/RecommendationsSection';
import { SigningSection } from './detail/SigningSection';
import { StepsSection } from './detail/StepsSection';

export type Detail = components['schemas']['DisclosureDetail'];
export type Template = components['schemas']['DisclosureTemplate'];
export type FlagList = components['schemas']['FlagList'];

export function DisclosureDetailPage() {
  const { id = '' } = useParams();
  const api = useApi();
  const [detail, setDetail] = useState<Outcome<Detail> | null>(null);
  const [template, setTemplate] = useState<Outcome<Template> | null>(null);
  const [flags, setFlags] = useState<Outcome<FlagList> | null>(null);
  const reload = useCallback(() => {
    const path = { params: { path: { disclosureId: id } } };
    void api.GET('/api/v1/disclosures/{disclosureId}', path).then((r) => { setDetail(outcome(r)); });
    void api.GET('/api/v1/disclosures/{disclosureId}/template', path).then((r) => { setTemplate(outcome(r)); });
    void api.GET('/api/v1/disclosures/{disclosureId}/flags', path).then((r) => { setFlags(outcome(r)); });
  }, [api, id]);
  useEffect(() => { reload(); }, [reload]);

  if (detail === null || template === null) return <p>{staff.common.loading}</p>;
  if (!detail.ok) return <ProblemView status={detail.status} problem={detail.problem} />;
  if (!template.ok) return <ProblemView status={template.status} problem={template.problem} />;
  const d = detail.data;
  const t = template.data;
  const documentFields = t.fields.flatMap((f) => { const v = documentValue(f.code, d); return v === undefined ? [] : [{ f, v }]; });
  return (
    <>
      <h1 data-template-title>{t.title}</h1>
      <dl className="facts" aria-label={staff.detail.identification}>
        {documentFields.map(({ f, v }) => (
          <Fragment key={f.code}>
            <dt data-field-code={f.code}>{f.label}</dt>
            <dd>{v}</dd>
          </Fragment>
        ))}
        <dt>{staff.detail.status}</dt><dd data-testid="status">{label(staff.status, d.status)} <code>{d.status}</code></dd>
        <dt>{staff.detail.template}</dt><dd><code>{t.templateId} v{t.version}</code></dd>
      </dl>
      <ItemsTable detail={d} template={t} />
      <ItemsSection id={id} detail={d} reload={reload} />
      <StepsSection id={id} reload={reload} />
      <RecommendationsSection id={id} detail={d} template={t} reload={reload} />
      <DocumentSection id={id} />
      <SigningSection id={id} detail={d} reload={reload} />
      <ManagerSection id={id} flags={flags} reload={reload} />
    </>
  );
}
