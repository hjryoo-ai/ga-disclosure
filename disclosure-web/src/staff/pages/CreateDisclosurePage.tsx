// 초안 생성(설계사, createDisclosure): 고객 가명·상품군·상담일·서식 종류(계약 enum). 서식·룰은 서버가 상담일로 해석해 고정한다.
import { type SubmitEvent } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { staff } from '../../shared/messages.ko.json';
import { useApi, write } from '../api/useApi';
import { formText, ResultLine, useShown } from '../components/ui';

const TEMPLATE_TYPES = ['STANDARD', 'AUTO'] as const;
const todayKst = () => new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Seoul' });

export function CreateDisclosurePage() {
  const api = useApi();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [shown, show] = useShown();

  const submit = (e: SubmitEvent<HTMLFormElement>) => {
    e.preventDefault();
    const data = new FormData(e.currentTarget);
    const type = formText(data, 'templateType');
    const body = {
      customerRef: formText(data, 'customerRef'),
      groupCode: formText(data, 'groupCode'),
      consultDate: formText(data, 'consultDate'),
      templateType: TEMPLATE_TYPES.find((t) => t === type) ?? TEMPLATE_TYPES[0],
    };
    void write((key) => api.POST('/api/v1/disclosures', { params: { header: { 'Idempotency-Key': key } }, body })).then((o) => {
      if (show(o, staff.create.created) && o.ok) void navigate(`/staff/disclosures/${o.data.disclosureId}`);
    });
  };

  return (
    <>
      <h1>{staff.create.title}</h1>
      <form onSubmit={submit} autoComplete="off">
        <label htmlFor="d-customer">{staff.create.customerRef}</label>
        <input id="d-customer" name="customerRef" required defaultValue={params.get('customerRef') ?? ''} autoComplete="off" />
        <label htmlFor="d-group">{staff.create.groupCode}</label>
        <input id="d-group" name="groupCode" required autoComplete="off" />
        <label htmlFor="d-date">{staff.create.consultDate}</label>
        <input id="d-date" name="consultDate" type="date" required defaultValue={todayKst()} />
        <label htmlFor="d-type">{staff.create.templateType}</label>
        <select id="d-type" name="templateType" defaultValue={TEMPLATE_TYPES[0]}>
          {TEMPLATE_TYPES.map((t) => <option key={t} value={t}>{staff.templateType[t]}</option>)}
        </select>
        <div><button type="submit">{staff.create.submit}</button></div>
      </form>
      <ResultLine shown={shown} />
    </>
  );
}
