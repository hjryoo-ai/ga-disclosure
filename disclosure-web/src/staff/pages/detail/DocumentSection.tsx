// 문서(설계사·관리자·준법): 워터마크 미리보기(getPreviewPdf), 산출물 내려받기(getArtifact — 바이트 그대로), 앵커 영수증(getAnchorReceipt).
// 미리보기만 워터마크가 있다(서버). 내려받기는 메모리의 Blob URL로만 열고 곧바로 놓는다(저장소·캐시 없음).
import { staff } from '../../../shared/messages.ko.json';
import { outcome, useApi } from '../../api/useApi';
import { ResultLine, Section, useShown } from '../../components/ui';

const KINDS = ['PDF', 'SIGNED_PDF', 'EVIDENCE_ZIP', 'CANONICAL_JSON'] as const;

function deliver(blob: Blob, name: string, open: boolean) {
  const url = URL.createObjectURL(blob);
  if (open) {
    window.open(url, '_blank', 'noopener');
  } else {
    const a = document.createElement('a');
    a.href = url;
    a.download = name;
    a.click();
  }
  setTimeout(() => { URL.revokeObjectURL(url); }, 60_000);
}

export function DocumentSection({ id }: { id: string }) {
  const api = useApi();
  const [shown, show] = useShown();
  const path = { params: { path: { disclosureId: id } } };

  const preview = () => {
    void api.GET('/api/v1/disclosures/{disclosureId}/preview.pdf', { ...path, parseAs: 'blob' }).then((r) => {
      const o = outcome(r);
      if (show(o, staff.documents.previewOpened) && o.ok) deliver(o.data, 'preview.pdf', true);
    });
  };
  const artifact = (kind: (typeof KINDS)[number]) => {
    void api.GET('/api/v1/disclosures/{disclosureId}/artifacts/{kind}', { params: { path: { disclosureId: id, kind } }, parseAs: 'blob' }).then((r) => {
      const o = outcome(r);
      if (show(o, staff.documents.downloaded) && o.ok) deliver(o.data, `${id}-${kind.toLowerCase()}`, false);
    });
  };
  const receipt = () => {
    void api.GET('/api/v1/disclosures/{disclosureId}/anchor-receipt', { ...path, parseAs: 'blob' }).then((r) => {
      const o = outcome(r);
      if (show(o, staff.documents.downloaded) && o.ok) deliver(o.data, `${id}-anchor-receipt.json`, false);
    });
  };

  return (
    <Section id="documents" title={staff.documents.title}>
      <button type="button" onClick={preview}>{staff.documents.preview}</button>
      {KINDS.map((k) => <button type="button" key={k} onClick={() => { artifact(k); }}>{staff.documents.artifact[k]}</button>)}
      <button type="button" onClick={receipt}>{staff.documents.receipt}</button>
      <ResultLine shown={shown} />
    </Section>
  );
}
