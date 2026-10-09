// 서명 패드(계획 ⑤): Pointer Events로 스트로크 [[{x, y, t}]](t = 첫 점부터 ms, Phase 4 형식)와 캔버스 PNG를 만든다.
// 좌표는 캔버스 픽셀(정수). 키보드 대체 입력은 없다(지시문 — 서명 외 전 흐름만 키보드).
export interface Point {
  x: number;
  y: number;
  t: number;
}

export class SignaturePad {
  private readonly strokes: Point[][] = [];
  private current: Point[] | null = null;
  private origin: number | null = null;

  constructor(private readonly canvas: HTMLCanvasElement) {
    canvas.addEventListener('pointerdown', (e) => { this.down(e); });
    canvas.addEventListener('pointermove', (e) => { this.move(e); });
    canvas.addEventListener('pointerup', () => { this.up(); });
    canvas.addEventListener('pointercancel', () => { this.up(); });
    canvas.addEventListener('pointerleave', () => { this.up(); });
  }

  /** 지금까지의 스트로크(복사본). */
  strokesSnapshot(): Point[][] {
    return this.strokes.map((s) => s.map((p) => ({ ...p })));
  }

  isEmpty(): boolean {
    return this.strokes.length === 0;
  }

  clear(): void {
    this.strokes.length = 0;
    this.current = null;
    this.origin = null;
    const ctx = this.canvas.getContext('2d');
    ctx?.clearRect(0, 0, this.canvas.width, this.canvas.height);
  }

  /** 캔버스 PNG의 base64(데이터 URL 머리 제외). */
  pngBase64(): string {
    const url = this.canvas.toDataURL('image/png');
    const comma = url.indexOf(',');
    return comma < 0 ? '' : url.slice(comma + 1);
  }

  private point(e: PointerEvent): Point {
    const rect = this.canvas.getBoundingClientRect();
    const sx = rect.width > 0 ? this.canvas.width / rect.width : 1;
    const sy = rect.height > 0 ? this.canvas.height / rect.height : 1;
    this.origin ??= e.timeStamp;
    return {
      x: Math.round((e.clientX - rect.left) * sx),
      y: Math.round((e.clientY - rect.top) * sy),
      t: Math.max(0, Math.round(e.timeStamp - this.origin)),
    };
  }

  private down(e: PointerEvent): void {
    e.preventDefault();
    this.canvas.setPointerCapture(e.pointerId);
    const p = this.point(e);
    this.current = [p];
    this.strokes.push(this.current);
  }

  private move(e: PointerEvent): void {
    if (this.current === null) return;
    const p = this.point(e);
    const last = this.current[this.current.length - 1];
    this.current.push(p);
    const ctx = this.canvas.getContext('2d');
    if (ctx !== null && last !== undefined) {
      ctx.lineWidth = 3;
      ctx.lineCap = 'round';
      ctx.strokeStyle = '#111';
      ctx.beginPath();
      ctx.moveTo(last.x, last.y);
      ctx.lineTo(p.x, p.y);
      ctx.stroke();
    }
  }

  private up(): void {
    this.current = null;
  }
}
