// 추천사유(설계사 입력만 — 절대 규칙 7, G6): 항목마다 사유 코드(룰의 코드, 쉼표 구분)와 설명. 시스템은 채우지 않는다 —
// 자동완성 끔, 예시·placeholder·기본값 없음, 서버 값을 미리 채우지도 않는다(다시 입력해 덮어쓴다). 코드 목록·필수 여부는 서버가 검증한다.
import { type SubmitEvent } from 'react';
import type { components } from '../../../gen/disclosure-api';
import { staff } from '../../../shared/messages.ko.json';
import { useApi, write } from '../../api/useApi';
import { formText, ResultLine, Section, useShown } from '../../components/ui';

type Detail = components['schemas']['DisclosureDetail'];
type Template = components['schemas']['DisclosureTemplate'];

export function RecommendationsSection({ id, detail, template, reload }: { id: string; detail: Detail; template: Template; reload: () => void }) {
  const api = useApi();
  const [shown, show] = useShown();
  const reasonLabel = template.fields.find((f) => f.code === 'RECOMMENDATION_REASON')?.label ?? staff.reasons.title;

  const submit = (e: SubmitEvent<HTMLFormElement>) => {
    e.preventDefault();
    const data = new FormData(e.currentTarget);
    const reasons = detail.items.flatMap((i) => {
      const codes = formText(data, `codes-${i.itemNo}`).split(',').map((c) => c.trim()).filter((c) => c !== '');
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
            <label htmlFor={`codes-${i.itemNo}`}>{staff.reasons.codes}</label>
            <input id={`codes-${i.itemNo}`} name={`codes-${i.itemNo}`} autoComplete="off" spellCheck={false} />
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
