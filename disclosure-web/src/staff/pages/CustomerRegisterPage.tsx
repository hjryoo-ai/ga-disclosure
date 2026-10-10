// 고객 등록(설계사, registerCustomer): 응답은 가명·영수증뿐 — 화면도 이름을 다시 보이지 않는다. 입력값은 제출하면 곧바로 지운다(상태·요소).
// 이 요청의 멱등 키는 의도마다 무작위다 — 본문(개인정보)에서 만들지 않는다.
import { useState, type SubmitEvent } from 'react';
import { Link } from 'react-router';
import { staff } from '../../shared/messages.ko.json';
import { useApi, write } from '../api/useApi';
import { formText, ResultLine, useShown } from '../components/ui';

export function CustomerRegisterPage() {
  const api = useApi();
  const [shown, show] = useShown();
  const [registered, setRegistered] = useState<{ customerRef: string; receiptId: string } | null>(null);

  const submit = (e: SubmitEvent<HTMLFormElement>) => {
    e.preventDefault();
    const form = e.currentTarget;
    const data = new FormData(form);
    const body: { name: string; phone?: string; birthDate?: string } = { name: formText(data, 'name') };
    const phone = formText(data, 'phone');
    const birthDate = formText(data, 'birthDate');
    if (phone !== '') body.phone = phone;
    if (birthDate !== '') body.birthDate = birthDate;
    form.reset();                                                          // 제출 즉시 입력 요소를 비운다
    void write((key) => api.POST('/api/v1/customers', { params: { header: { 'Idempotency-Key': key } }, body })).then((o) => {
      body.name = '';
      delete body.phone;
      delete body.birthDate;
      if (show(o, staff.customer.registered) && o.ok) setRegistered(o.data);
    });
  };

  return (
    <>
      <h1>{staff.customer.title}</h1>
      <p>{staff.customer.intro}</p>
      <form onSubmit={submit} autoComplete="off">
        <label htmlFor="c-name">{staff.customer.name}</label>
        <input id="c-name" name="name" required autoComplete="off" />
        <label htmlFor="c-phone">{staff.customer.phone}</label>
        <input id="c-phone" name="phone" inputMode="tel" autoComplete="off" />
        <label htmlFor="c-birth">{staff.customer.birthDate}</label>
        <input id="c-birth" name="birthDate" inputMode="numeric" autoComplete="off" spellCheck={false} />
        <div><button type="submit">{staff.customer.submit}</button></div>
      </form>
      <ResultLine shown={shown} />
      {registered !== null && (
        <dl className="facts">
          <dt>{staff.customer.ref}</dt><dd><code data-testid="customer-ref">{registered.customerRef}</code></dd>
          <dt>{staff.customer.receipt}</dt><dd><code>{registered.receiptId}</code></dd>
          <dt>{staff.customer.next}</dt>
          <dd><Link to={`/staff/disclosures/new?customerRef=${encodeURIComponent(registered.customerRef)}`}>{staff.customer.createWith}</Link></dd>
        </dl>
      )}
    </>
  );
}
