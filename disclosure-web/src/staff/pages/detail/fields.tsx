// 서식 항목 코드 → 상세 응답의 값(화면 표시용 결속). 라벨은 고정 서식 응답의 것이고, 여기에는 코드만 있다.
// 결속이 없는 항목(보험료 등 — 상세 응답에 없는 값)은 보이지 않는다. 비율(ratioToAvg)은 보이지 않는다(문서와 같다, 설계서 §6.3).
import type { components } from '../../../gen/disclosure-api';
import { staff } from '../../../shared/messages.ko.json';

type Detail = components['schemas']['DisclosureDetail'];
type Template = components['schemas']['DisclosureTemplate'];
type Item = Detail['items'][number];
type Field = Template['fields'][number];

const DOCUMENT: Readonly<Record<string, (d: Detail) => string>> = {
  DISCLOSURE_NO: (d) => d.disclosureNo ?? staff.detail.notSealed,
  CONSULT_DATE: (d) => d.consultDate,
  AGENT: (d) => d.agentId,
  CUSTOMER_NAME: (d) => `${d.customerRef} (${staff.detail.pseudonym})`,
  PRODUCT_GROUP: (d) => d.groupCode,
};

const ITEM: Readonly<Record<string, (i: Item, f: Field) => string>> = {
  INSURER_NAME: (i) => i.insurerCode,
  PRODUCT_NAME: (i) => i.productName,
  COMMISSION_GRADE: (i, f) => i.grade === null ? '—' : i.grade.gradeLabel ?? f.unavailableText ?? '—',
  COMMISSION_RANK: (i, f) => i.grade === null ? '—' : i.grade.rankInSet === null ? f.unavailableText ?? '—' : String(i.grade.rankInSet),
  RECOMMENDATION_REASON: (i) => i.recommendation === null ? '—' : [i.recommendation.reasonCodes.join(', '), i.recommendation.text ?? ''].filter((x) => x !== '').join(' · '),
};

/** 문서 수준 항목의 표시 값. 결속이 없으면 undefined(보이지 않는다). */
export function documentValue(code: string, d: Detail): string | undefined {
  return DOCUMENT[code]?.(d);
}

export function ItemsTable({ detail, template }: { detail: Detail; template: Template }) {
  const columns = template.fields.filter((f) => f.code in ITEM);
  if (detail.items.length === 0) return <p>{staff.detail.noItems}</p>;
  return (
    <div className="table-wrap">
      <table>
        <caption>{staff.detail.itemsCaption}</caption>
        <thead>
          <tr>
            <th scope="col">{staff.detail.itemNo}</th>
            {columns.map((f) => <th scope="col" key={f.code} data-field-code={f.code}>{f.label}</th>)}
            <th scope="col">{staff.detail.recommended}</th>
          </tr>
        </thead>
        <tbody>
          {detail.items.map((i) => (
            <tr key={i.itemNo}>
              <td>{i.itemNo}</td>
              {columns.map((f) => <td key={f.code}>{ITEM[f.code]?.(i, f)}</td>)}
              <td>{i.recommended ? staff.common.yes : staff.common.no}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
