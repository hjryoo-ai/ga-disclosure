// 실행 산출물 센티널 스캔(Phase 8 계획 ③-x-4): 시험이 끝난 뒤 build/e2e 아래 모든 파일(소유자 전용 실행 디렉터리 run/ 제외 — 설계상 입력값을 담고 실행 뒤
// 지운다)에서 허구 센티널(이름·전화 두 형식·생년월일 두 형식)을 찾는다. HTML 보고서는 첨부(error-context.md 등)를 base64 zip으로 index.html에 싣으므로
// 그 zip을 풀어 엔트리도 본다. 결과 파일에는 센티널 번호·파일 상대 경로만 적는다(값 0).
import { readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { join, relative } from 'node:path';
import { inflateRawSync } from 'node:zlib';

/** 같은 형태 목록(e2e/support/env.ts sentinelForms와 같다). */
export function sentinelForms(s) {
  return [s.name, s.phone, s.phone.replaceAll('-', ''), s.birthDate, s.birthDate.replaceAll('-', '')];
}

function files(dir, skip) {
  const out = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (p === skip) continue;
    if (statSync(p).isDirectory()) out.push(...files(p, skip));
    else out.push(p);
  }
  return out;
}

/** zip의 로컬 엔트리(저장·deflate)를 차례로 푼다 — 보고서 zip만 다루면 충분하다(암호화·zip64 없음). */
export function zipEntries(buf) {
  const out = [];
  let i = 0;
  while (i + 30 <= buf.length && buf.readUInt32LE(i) === 0x04034b50) {
    const flags = buf.readUInt16LE(i + 6);
    const method = buf.readUInt16LE(i + 8);
    let size = buf.readUInt32LE(i + 18);
    const nameLen = buf.readUInt16LE(i + 26);
    const extraLen = buf.readUInt16LE(i + 28);
    const name = buf.subarray(i + 30, i + 30 + nameLen).toString('utf8');
    const start = i + 30 + nameLen + extraLen;
    if ((flags & 0x08) !== 0 && size === 0) {
      // 크기가 데이터 설명자에 있는 엔트리: 다음 로컬 헤더 또는 중앙 디렉터리 서명까지
      let end = start;
      while (end + 4 <= buf.length && buf.readUInt32LE(end) !== 0x08074b50 && buf.readUInt32LE(end) !== 0x04034b50 && buf.readUInt32LE(end) !== 0x02014b50) end++;
      size = end - start;
    }
    const data = buf.subarray(start, start + size);
    out.push({ name, data: method === 8 ? inflateRawSync(data) : data });
    i = start + size;
    if ((flags & 0x08) !== 0) i += buf.readUInt32LE(i) === 0x08074b50 ? 16 : 12;
  }
  return out;
}

/** HTML 보고서의 내장 zip(base64). 없으면 null. */
function embeddedZip(html) {
  const m = /data:application\/zip;base64,([A-Za-z0-9+/=]+)/.exec(html);
  return m === null ? null : Buffer.from(m[1], 'base64');
}

/** 찾으면 [{sentinel, file}] — 값은 싣지 않는다. */
export function scan(root, run, sentinels) {
  const forms = sentinelForms(sentinels).map((f) => Buffer.from(f, 'utf8'));
  const hits = [];
  let scanned = 0;
  const look = (label, buf) => {
    scanned++;
    forms.forEach((f, i) => { if (buf.includes(f)) hits.push({ sentinel: i, file: label }); });
  };
  for (const p of files(root, run)) {
    const buf = readFileSync(p);
    const label = relative(root, p);
    look(label, buf);
    if (p.endsWith('.html')) {
      const zip = embeddedZip(buf.toString('latin1'));
      if (zip !== null) for (const e of zipEntries(zip)) look(`${label}!${e.name}`, e.data);
    }
  }
  writeFileSync(join(root, 'artifact-scan.json'), JSON.stringify({ scanned, sentinels: forms.length, hits }, null, 2));
  return { scanned, hits };
}
