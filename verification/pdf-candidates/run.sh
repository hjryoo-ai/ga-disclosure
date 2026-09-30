#!/usr/bin/env bash
# PDF/A 변환기 후보 비교(3A 선행 소과제 D) — 후보마다 같은 XHTML을 "별도 프로세스로 2회" 변환해 SHA-256·바이트 차이·PDF/A 준수를 적는다.
# JVM 후보(openhtmltopdf·Flying Saucer)는 Gradle run, WeasyPrint·Gotenberg는 Docker. 폰트·ICC는 저장소 동봉 자산만 쓴다.
# 사용: verification/pdf-candidates/run.sh [--with-gotenberg]   결과: verification/pdf-candidates/out/(git 무시)
set -euo pipefail
cd "$(dirname "$0")"
REPO="$(cd ../.. && pwd)"
OUT=out
rm -rf "$OUT" && mkdir -p "$OUT"
run() { ../../gradlew -q --project-dir . run --args="$*"; }

for mode in ohtp-naive ohtp fs-naive fs; do
  for n in 1 2; do
    run "$mode $OUT/$mode-$n.pdf" > "$OUT/$mode-$n.log" 2>&1
  done
done

docker build -q -t pdf-candidates-weasyprint weasyprint > /dev/null
for n in 1 2; do
  docker run --rm -v "$REPO:/repo" -w /repo/verification/pdf-candidates pdf-candidates-weasyprint \
    weasyprint --pdf-variant pdf/a-2b sample.html "$OUT/wp-$n.pdf"
done

if [ "${1:-}" = "--with-gotenberg" ]; then
  cid=$(docker run -d --rm -p 3999:3000 gotenberg/gotenberg:8)
  trap 'docker stop "$cid" > /dev/null' EXIT
  for i in $(seq 1 30); do curl -sf localhost:3999/health > /dev/null && break; sleep 1; done
  for n in 1 2; do
    curl -sf -X POST localhost:3999/forms/chromium/convert/html -F "files=@sample.html;filename=index.html" \
      -F pdfa=PDF/A-2b -o "$OUT/gt-$n.pdf"
  done
fi

{
  echo "| 후보 | 1회 SHA-256 | 2회 SHA-256 | 바이트 일치 | veraPDF PDF/A-2b |"
  echo "|---|---|---|---|---|"
  for c in ohtp-naive ohtp fs-naive fs wp gt; do
    [ -f "$OUT/$c-1.pdf" ] || continue
    h1=$(shasum -a 256 "$OUT/$c-1.pdf" | cut -c1-16); h2=$(shasum -a 256 "$OUT/$c-2.pdf" | cut -c1-16)
    same=$(cmp -s "$OUT/$c-1.pdf" "$OUT/$c-2.pdf" && echo 일치 || echo 불일치)
    vera=$(run "verify $OUT/$c-1.pdf 2b" | head -1)
    echo "| ${c} | ${h1}… | ${h2}… | ${same} | ${vera} |"
  done
} | tee "$OUT/RESULTS.md"
