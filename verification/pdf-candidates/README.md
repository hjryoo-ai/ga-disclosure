# PDF/A 변환기 후보 비교 (Phase 3A 선행 소과제 D)

같은 XHTML(`sample.html` — 한글 제목·표·굵은 글씨·자모 검사 문자열, 값은 전부 가상)을 후보마다 **별도 프로세스로 2회** 변환해 SHA-256과
바이트를 대조하고, veraPDF 1.30.2(PDF/A-2b 프로파일)로 준수를 검증한다. 폰트(NanumGothic, OFL)와 sRGB ICC(CC0)는 저장소 동봉 자산
(`disclosure-seal/src/main/resources/render/`, 출처·해시는 그 폴더의 `SOURCES.md`)만 쓰고 빌드 때 내려받지 않는다. 메인 빌드에 포함하지 않는
독립 검증 빌드다.

```
verification/pdf-candidates/run.sh [--with-gotenberg]   # JDK 25(툴체인), Docker(WeasyPrint·Gotenberg)
```

- `ohtp-naive`·`fs-naive`: 라이브러리 기본값. `ohtp`: 문서 정보 날짜·XMP 날짜·트레일러 `/ID` 시드 세 곳을 고정. `fs`: 정보 날짜만 고정(`/ID`는 공개 API로 고정 불가).
- `wp`: WeasyPrint 70.0 `--pdf-variant pdf/a-2b`(기본값 그대로). `gt`: Gotenberg 8 Chromium 변환 + `pdfa=PDF/A-2b`.

## 결과 (2026-09-30, macOS arm64 · Temurin 25 · Docker Desktop, 동봉 ICC)

| 후보 | 1회 SHA-256 | 2회 SHA-256 | 바이트 일치 | veraPDF PDF/A-2b |
|---|---|---|---|---|
| ohtp-naive | 3f521977ac6d0f5d… | 485b62e16428364f… | 불일치(XMP `CreateDate`·`/ID`) | 준수(실패 0) |
| **ohtp** | 44e65db602701e16… | 44e65db602701e16… | **일치** | **준수(실패 0)** |
| fs-naive | e3a129efa083a410… | 58ba07d7a31622ee… | 불일치 | 비준수(3) |
| fs | ec0466d9bd582744… | e9b978d25a3979c2… | 불일치(`/ID` 시간 기반) | 비준수(3) |
| wp | 81ff571f8499b8fd… | 81ff571f8499b8fd… | 일치 | 준수(실패 0) |
| gt | 5231e045e86e9545… | aac7176640600732… | 불일치(폰트 스트림) | 비준수(1) |

iText 9 pdfHTML은 AGPL-3.0/상용으로 저장소 라이선스와 충돌해 측정하지 않았다.

## 결정(3A 계획 승인 §3 — 3B 선결정)

**openhtmltopdf 1.1.x.** JVM 안에서 끝나고, 세 지점(정보 날짜·XMP 날짜·`/ID` 시드)을 고정하면 바이트가 같다. 3B에서 세 값은 전부 봉인
입력(canonical 해시·확인서 번호)에서 파생하고 벽시계를 참조하지 않는다. WeasyPrint는 기본값으로 결정론·준수를 만족하지만 Python·Pango
사이드카와 프로세스 경계가 생긴다(봉인 p95 3초·장애 격리 목표에서 비용이 더 크다).

**미검증 → 3B 완료 기준:** 다른 OS·JDK에서 같은 바이트인지(이 표는 macOS arm64 한 곳) — CI(Linux)와 로컬의 PDF SHA-256 일치를 3B가
증명한다. 동봉 ICC·폰트로 고정한 뒤에도 어긋나면 WeasyPrint 사이드카로 전환하고 설계서 결정 기록에 남긴다. Boot BOM과의 의존성 겹침은
3B 첫 커밋에서 `checkBom`으로 확인한다.
