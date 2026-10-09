// PDF 열람(계획 ⑤, 승인 조건 ②): pdf.js로 쪽마다 캔버스를 그린다 — 워커까지 같은 출처 번들(CDN 0).
//  스크롤 완료 = 마지막 쪽이 렌더되어 뷰포트에 들어온 시점(IntersectionObserver).
//  열람 초 = 단조 시계(performance.now) 경과, 화면이 가려진 동안(visibilitychange)은 뺀다.
// 바이트는 서버가 준 봉인 PDF 그대로 — 화면이 고치지 않는다(워터마크 없음, G7).
import { GlobalWorkerOptions, getDocument } from 'pdfjs-dist';
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url';

GlobalWorkerOptions.workerSrc = workerUrl;

export class ViewTracker {
  private visibleSince: number | null;
  private accumulated = 0;
  private complete = false;

  constructor(private readonly now: () => number, private readonly doc: Pick<Document, 'visibilityState' | 'addEventListener'>) {
    this.visibleSince = doc.visibilityState === 'visible' ? now() : null;
    doc.addEventListener('visibilitychange', () => {
      if (doc.visibilityState === 'visible') {
        this.visibleSince ??= this.now();
      } else if (this.visibleSince !== null) {
        this.accumulated += this.now() - this.visibleSince;
        this.visibleSince = null;
      }
    });
  }

  /** 마지막 쪽이 렌더되어 뷰포트에 들어왔다. */
  markLastPageSeen(): void {
    this.complete = true;
  }

  scrollComplete(): boolean {
    return this.complete;
  }

  /** 지금까지 보인 시간(정수 초, 내림). */
  viewSeconds(): number {
    const running = this.visibleSince === null ? 0 : this.now() - this.visibleSince;
    return Math.floor((this.accumulated + running) / 1000);
  }
}

/** 쪽마다 캔버스를 container에 그린다. 마지막 쪽이 렌더된 뒤에야 그 쪽을 관찰한다(렌더 전 교차는 완료가 아니다). */
export async function renderPdf(bytes: ArrayBuffer, container: HTMLElement, tracker: ViewTracker, pageLabel: (n: number, total: number) => string)
  : Promise<number> {
  const pdf = await getDocument({ data: new Uint8Array(bytes), verbosity: 0, useSystemFonts: false, enableXfa: false }).promise;
  const total = pdf.numPages;
  for (let n = 1; n <= total; n++) {
    const page = await pdf.getPage(n);
    const viewport = page.getViewport({ scale: 1.5 });
    const canvas = document.createElement('canvas');
    canvas.width = Math.floor(viewport.width);
    canvas.height = Math.floor(viewport.height);
    canvas.className = 'pdf-page';
    canvas.setAttribute('role', 'img');
    canvas.setAttribute('aria-label', pageLabel(n, total));
    container.append(canvas);
    await page.render({ canvas, viewport }).promise;
    if (n === total) {
      const observer = new IntersectionObserver((entries) => {
        if (entries.some((e) => e.isIntersecting)) {
          tracker.markLastPageSeen();
          observer.disconnect();
        }
      }, { root: null, threshold: 0.1 });
      observer.observe(canvas);
    }
  }
  return total;
}
