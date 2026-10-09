// 서명(설계사): 세션 발급(채널 — 계약 enum), 현장 기기 토큰의 대면 확인·서명 창 열기, 종이 스캔 올리기, 설계사 서명(패드 — 룰이 승인 클릭이면 빈 본문).
// 현장 기기 토큰은 이 화면의 메모리(상태)에만 있다 — 저장소·URL·로그에 두지 않는다. 서명 창은 조각(#)으로만 넘긴다(서버로 가지 않는다).
// 원격 링크 토큰은 화면에 오지 않는다(통지 대기열이 발송, 설계서 §6.5) — 발급 뒤 "발송 대기"만 보인다.
import { useEffect, useRef, useState, type SubmitEvent } from 'react';
import type { components } from '../../../gen/disclosure-api';
import { fingerprint } from '../../../shared/fingerprint';
import { staff } from '../../../shared/messages.ko.json';
import { SignaturePad } from '../../../shared/pad';
import { useApi, write } from '../../api/useApi';
import { formText, label, ResultLine, Section, useShown } from '../../components/ui';

type Detail = components['schemas']['DisclosureDetail'];
type Channel = components['schemas']['SessionIssueRequest']['channel'];
type Issued = components['schemas']['SessionIssueReceipt'];
const CHANNELS: readonly Channel[] = ['TOUCH_PAD', 'REMOTE_LINK', 'PAPER_SCAN', 'CERTIFIED_ESIGN'];

function base64(file: File): Promise<string> {
  return file.arrayBuffer().then((buf) => {
    let s = '';
    for (const b of new Uint8Array(buf)) s += String.fromCharCode(b);
    return btoa(s);
  });
}

export function SigningSection({ id, detail, reload }: { id: string; detail: Detail; reload: () => void }) {
  const api = useApi();
  const [shown, show] = useShown();
  const [issued, setIssued] = useState<Issued | null>(null);
  const canvas = useRef<HTMLCanvasElement>(null);
  const pad = useRef<SignaturePad | null>(null);
  useEffect(() => {
    if (canvas.current !== null && pad.current === null) pad.current = new SignaturePad(canvas.current);
  }, []);
  const header = (key: string) => ({ 'Idempotency-Key': key });

  const issue = (e: SubmitEvent<HTMLFormElement>) => {
    e.preventDefault();
    const channel = CHANNELS.find((c) => c === formText(new FormData(e.currentTarget), 'channel')) ?? 'TOUCH_PAD';
    void write((key) => api.POST('/api/v1/disclosures/{disclosureId}/sign-sessions', {
      params: { path: { disclosureId: id }, header: header(key) }, body: { channel },
    })).then((o) => { if (show(o, staff.signing.issued)) setIssued(o.ok ? o.data : null); });
  };
  const token = issued?.deviceToken ?? null;
  const confirm = () => {
    if (token === null) return;
    void write((key) => api.POST('/api/v1/sign-sessions/face-to-face', { params: { header: header(key) }, body: { token } }))
      .then((o) => { show(o, staff.signing.confirmed); });
  };
  const openSignWindow = () => {
    if (token !== null) window.open(`/s#${token}`, '_blank', 'noopener');
  };
  const upload = (e: SubmitEvent<HTMLFormElement>) => {
    e.preventDefault();
    const form = e.currentTarget;
    const data = new FormData(form);
    const file = data.get('scan');
    if (token === null || !(file instanceof File)) return;
    void base64(file).then((imageBase64) => write((key) => api.POST('/api/v1/sign-sessions/paper-scan', {
      params: { header: header(key) },
      body: { token, disclosureNo: formText(data, 'disclosureNo'), hashPrefix: formText(data, 'hashPrefix'), imageBase64 },
    }))).then((o) => { if (show(o, staff.signing.uploaded)) { form.reset(); reload(); } });
  };
  const agentSign = () => {
    const p = pad.current;
    void fingerprint().then((deviceFingerprint) => write((key) => api.POST('/api/v1/disclosures/{disclosureId}/agent-signature', {
      params: { path: { disclosureId: id }, header: header(key) },
      body: p === null || p.isEmpty() ? {} : { strokes: p.strokesSnapshot(), imagePngBase64: p.pngBase64(), deviceFingerprint },
    }))).then((o) => { if (show(o, staff.signing.signed)) { p?.clear(); reload(); } });
  };

  return (
    <Section id="signing" title={staff.signing.title}>
      {detail.signatures.length > 0 && (
        <ul aria-label={staff.signing.signaturesLabel}>
          {detail.signatures.map((s) => <li key={`${s.role}-${s.signedAt}`}>{label(staff.signerRole, s.role)} · {s.signedAt}</li>)}
        </ul>
      )}
      <form onSubmit={issue}>
        <label htmlFor="s-channel">{staff.signing.channel}</label>
        <select id="s-channel" name="channel" defaultValue="TOUCH_PAD">
          {CHANNELS.map((c) => <option key={c} value={c}>{label(staff.channel, c)}</option>)}
        </select>
        <button type="submit">{staff.signing.issue}</button>
      </form>
      {issued !== null && (
        <div data-testid="issued">
          <p>{label(staff.channel, issued.channel)} · {staff.signing.expiresAt} {issued.expiresAt ?? '—'}</p>
          {token === null ? <p>{staff.signing.awaitingDispatch}</p> : (
            <>
              <button type="button" onClick={confirm}>{staff.signing.faceToFace}</button>
              <button type="button" onClick={openSignWindow}>{staff.signing.openSignWindow}</button>
              <form onSubmit={upload} autoComplete="off">
                <fieldset>
                  <legend>{staff.signing.paperScan}</legend>
                  <label htmlFor="ps-no">{staff.signing.scanNo}</label>
                  <input id="ps-no" name="disclosureNo" autoComplete="off" />
                  <label htmlFor="ps-hash">{staff.signing.scanHash}</label>
                  <input id="ps-hash" name="hashPrefix" autoComplete="off" spellCheck={false} />
                  <label htmlFor="ps-file">{staff.signing.scanFile}</label>
                  <input id="ps-file" name="scan" type="file" accept="image/jpeg,image/png" />
                  <button type="submit">{staff.signing.upload}</button>
                </fieldset>
              </form>
            </>
          )}
        </div>
      )}
      <h3>{staff.signing.agentHeading}</h3>
      <p>{staff.signing.agentHint}</p>
      <canvas ref={canvas} width={600} height={200} className="pad" role="img" aria-label={staff.signing.padLabel} />
      <button type="button" onClick={() => { pad.current?.clear(); }}>{staff.signing.clear}</button>
      <button type="button" onClick={agentSign}>{staff.signing.agentSign}</button>
      <ResultLine shown={shown} />
    </Section>
  );
}
