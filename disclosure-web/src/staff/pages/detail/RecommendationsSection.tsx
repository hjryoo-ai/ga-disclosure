// 추천사유(설계사 입력만 — 절대 규칙 7, G6): 항목마다 사유 코드(룰 어휘의 닫힌 목록에서 체크 — Phase 8 G9, 시스템 부가 코드는 목록에 없다)와 설명.
// 시스템은 채우지 않는다 — 미리 체크된 칸·자동완성·예시·placeholder·기본값 없음, 서버 값을 미리 채우지도 않는다(다시 골라 덮어쓴다). 텍스트 필수
// 여부·코드 판정은 서버가 한다.
import { type SubmitEvent } from 'react';
import type { components } from '../../../gen/disclosure-api';
import { staff } from '../../../shared/messages.ko.json';
import { useApi, write } from '../../api/useApi';
import { useCodes } from '../../api/vocabulary';
import { CodeChecks, formText, ResultLine, Section, useShown } from '../../components/ui';

type Detail = components['schemas']['DisclosureDetail'];
type Template = components['schemas']['DisclosureTemplate'];

export function RecommendationsSection({ id, detail, template, reload }: { id: string; detail: Detail; template: Template; reload: () => void }) {
  const api = useApi();
  const [shown, show] = useShown();
  const codeList = useCodes((v) => v.reasonCodes);
  const reasonLabel = template.fields.find((f) => f.code === 'RECOMMENDATION_REASON')?.label ?? staff.reasons.title;

  const submit = (e: SubmitEvent<HTMLFormElement>) => {
    e.preventDefault();
    const data = new FormData(e.currentTarget);
    const reasons = detail.items.flatMap((i) => {
      const codes = data.getAll(`codes-${i.itemNo}`).map(String);
      const text = formText(data, `text-${i.itemNo}`);
      if (codes.length === 0 && text === '') return [];
      return [text === '' ? { itemNo: i.itemNo, codes } : { itemNo: i.itemNo, codes, text }];
    });
    void write((key) => api.POST('/api/v1/disclosures/{disclosureId}/recommendations', {
      params: { path: { disclosureId: id }, header: { 'Idempotency-Key': key } }, body: { reasons },
    })).then((o) => { if (show(o, staff.reasons.saved)) reload(); });
  };

  return (
    <Section id="reasons" title={reasonLabel}>
      <p>{staff.reasons.intro}</p>
      <form onSubmit={submit} autoComplete="off" data-testid="reasons-form">
        {detail.items.map((i) => (
          <fieldset key={i.itemNo}>
            <legend>{staff.reasons.item} {i.itemNo} · <code>{i.insurerCode}</code> {i.productName}</legend>
            <CodeChecks id={`codes-${i.itemNo}`} name={`codes-${i.itemNo}`} legend={staff.reasons.codes} codes={codeList} />
            <label htmlFor={`text-${i.itemNo}`}>{staff.reasons.text}</label>
            <textarea id={`text-${i.itemNo}`} name={`text-${i.itemNo}`} autoComplete="off" />
          </fieldset>
        ))}
        <button type="submit">{staff.reasons.save}</button>
      </form>
      <ResultLine shown={shown} />
    </Section>
  );
}
