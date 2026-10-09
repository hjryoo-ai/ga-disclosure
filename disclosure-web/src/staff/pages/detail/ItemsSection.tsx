// 비교표 구성(설계사): 카탈로그 검색(searchCatalogProducts — 그 확인서의 상품군) → 고른 상품·추천·고객 요청 표시 → replaceItems.
// 개수·보험사 중복 같은 규칙은 화면이 세지 않는다 — 서버 검증 결과를 본다.
import { useState, type SubmitEvent } from 'react';
import type { components } from '../../../gen/disclosure-api';
import { staff } from '../../../shared/messages.ko.json';
import { outcome, useApi, write, type Outcome } from '../../api/useApi';
import { ProblemView } from '../../components/ProblemView';
import { formText, ResultLine, Section, useShown } from '../../components/ui';

type Detail = components['schemas']['DisclosureDetail'];
type Product = components['schemas']['CatalogProduct'];
interface Chosen {
  productKey: string;
  name: string;
  recommended: boolean;
  requestedByCustomer: boolean;
}

export function ItemsSection({ id, detail, reload }: { id: string; detail: Detail; reload: () => void }) {
  const api = useApi();
  const [found, setFound] = useState<Outcome<components['schemas']['CatalogProductList']> | null>(null);
  const [chosen, setChosen] = useState<Chosen[]>(() => detail.items.flatMap((i) => i.productKey === null ? []
    : [{ productKey: i.productKey, name: i.productName, recommended: i.recommended, requestedByCustomer: i.requestedByCustomer }]));
  const [shown, show] = useShown();

  const search = (e: SubmitEvent<HTMLFormElement>) => {
    e.preventDefault();
    const q = formText(new FormData(e.currentTarget), 'q');
    void api.GET('/api/v1/catalog/products', { params: { query: q === '' ? { group: detail.groupCode } : { group: detail.groupCode, q } } })
      .then((r) => { setFound(outcome(r)); });
  };
  const add = (p: Product) => {
    setChosen((c) => c.some((x) => x.productKey === p.productKey) ? c : [...c, { productKey: p.productKey, name: p.name, recommended: false, requestedByCustomer: false }]);
  };
  const toggle = (key: string, field: 'recommended' | 'requestedByCustomer') => {
    setChosen((c) => c.map((x) => x.productKey === key ? { ...x, [field]: !x[field] } : x));
  };
  const save = () => {
    const items = chosen.map((c) => ({ productKey: c.productKey, recommended: c.recommended, requestedByCustomer: c.requestedByCustomer }));
    void write((key) => api.POST('/api/v1/disclosures/{disclosureId}/items', { params: { path: { disclosureId: id }, header: { 'Idempotency-Key': key } },
      body: { items } })).then((o) => { if (show(o, staff.items.saved)) reload(); });
  };

  return (
    <Section id="items" title={staff.items.title}>
      <form onSubmit={search} role="search" autoComplete="off">
        <label htmlFor="cat-q">{staff.items.query} <code>{detail.groupCode}</code></label>
        <input id="cat-q" name="q" autoComplete="off" />
        <button type="submit">{staff.items.search}</button>
      </form>
      {found !== null && (!found.ok ? <ProblemView status={found.status} problem={found.problem} /> : (
        <ul aria-label={staff.items.results}>
          {found.data.items.map((p) => (
            <li key={p.productKey}>
              <code>{p.insurerCode}</code> {p.name} <code>{p.productKey}</code>
              <button type="button" onClick={() => { add(p); }} aria-label={`${staff.items.add}: ${p.productKey}`}>{staff.items.add}</button>
            </li>
          ))}
        </ul>
      ))}
      <fieldset>
        <legend>{staff.items.chosen}</legend>
        {chosen.length === 0 ? <p>{staff.items.none}</p> : (
          <ul>
            {chosen.map((c) => (
              <li key={c.productKey}>
                <code>{c.productKey}</code> {c.name}
                <label><input type="checkbox" checked={c.recommended} onChange={() => { toggle(c.productKey, 'recommended'); }} /> {staff.items.recommended}</label>
                <label><input type="checkbox" checked={c.requestedByCustomer} onChange={() => { toggle(c.productKey, 'requestedByCustomer'); }} /> {staff.items.requested}</label>
                <button type="button" onClick={() => { setChosen((x) => x.filter((y) => y.productKey !== c.productKey)); }}
                  aria-label={`${staff.items.remove}: ${c.productKey}`}>{staff.items.remove}</button>
              </li>
            ))}
          </ul>
        )}
      </fieldset>
      <button type="button" onClick={save}>{staff.items.save}</button>
      <ResultLine shown={shown} />
    </Section>
  );
}
