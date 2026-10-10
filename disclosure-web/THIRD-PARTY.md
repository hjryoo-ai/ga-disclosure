# disclosure-web 서드파티 목록

Phase 7(계획 ③, 승인 2026-10-10). `./gradlew :disclosure-web:webLicenses`(`scripts/licenses.mjs`)가 `package.json`·`package-lock.json`과 아래 두 표를 **양방향**으로 대조한다 — 직접 의존이 바뀌거나, 허용 목록 밖 라이선스의 패키지가 생기거나, 표에 폐기 항목이 남으면 빌드가 실패한다.

- **허용 라이선스**: MIT · Apache-2.0 · BSD-2-Clause · BSD-3-Clause · ISC · OFL-1.1 · 0BSD · BlueOak-1.0.0. 그 밖은 **dev 전용**이고 아래 예외 표에 사유와 함께 있어야 한다. 운영(prod) 의존은 예외 불가.
- **버전**: 정확한 버전만(`^`·`~` 없음, `.npmrc` `save-exact`), 설치는 `npm ci`(Gradle `npmInstall`), 의존성 설치 스크립트 실행 안 함(`ignore-scripts`). Node 24.21.0 LTS·npm 11.19.0은 Gradle Node 플러그인이 내려받는다(`gradle/libs.versions.toml`).
- **확인 시점**: 2026-10-10 npm 레지스트리 `latest` 기준 최신 안정판. 예외 둘 — TypeScript는 7.0.2가 최신이지만 typescript-eslint(`<6.1.0`)·openapi-typescript(`^5.x`)의 피어 범위 밖이라 **5.9.3**(Q7, 피어 덮어쓰기 없음). `@types/node`는 런타임 Node 24에 맞춰 24.x 최신.
- **축 의존**: `axe-core`는 `@axe-core/playwright`가 고정한 4.13.0이 설치된다(계획 ③의 4.14.0은 레지스트리 최신 — 직접 의존으로 올리지 않는다).

## 직접 의존

<!-- licenses:direct -->
| 패키지 | 버전 | 라이선스 | 구분 | 용도 |
|---|---|---|---|---|
| `openapi-fetch` | `0.17.0` | `MIT` | `prod` | 생성 타입 위의 얇은 요청 실행기(G2) |
| `pdfjs-dist` | `6.4.299` | `Apache-2.0` | `prod` | 고객 서명 화면의 PDF 표시·스크롤 완료 측정(워커 포함 번들, CDN 0) |
| `react` | `19.3.0` | `MIT` | `prod` | 직원 화면 |
| `react-dom` | `19.3.0` | `MIT` | `prod` | 직원 화면 |
| `react-router` | `8.4.0` | `MIT` | `prod` | 직원 화면 라우팅(고객 서명 번들엔 없음) |
| `@axe-core/playwright` | `4.13.0` | `MPL-2.0` | `dev` | E2E 접근성 검사(시험 전용) |
| `@eslint/js` | `10.0.1` | `MIT` | `dev` | 린트 |
| `@playwright/test` | `1.64.0` | `Apache-2.0` | `dev` | E2E |
| `@testing-library/dom` | `10.4.2` | `MIT` | `dev` | 컴포넌트 시험 |
| `@testing-library/react` | `16.3.3` | `MIT` | `dev` | 컴포넌트 시험 |
| `@testing-library/user-event` | `14.6.7` | `MIT` | `dev` | 컴포넌트 시험 |
| `@types/node` | `24.19.1` | `MIT` | `dev` | 설정·시험 스크립트 타입 |
| `@types/react` | `19.3.0` | `MIT` | `dev` | 타입 |
| `@types/react-dom` | `19.3.0` | `MIT` | `dev` | 타입 |
| `@vitejs/plugin-react` | `6.1.2` | `MIT` | `dev` | 빌드 |
| `eslint` | `10.12.0` | `MIT` | `dev` | 린트 |
| `eslint-plugin-react-hooks` | `7.1.1` | `MIT` | `dev` | 린트 |
| `globals` | `17.13.0` | `MIT` | `dev` | 린트 전역 목록 |
| `jsdom` | `30.1.2` | `MIT` | `dev` | 컴포넌트 시험 DOM |
| `openapi-typescript` | `7.13.0` | `MIT` | `dev` | 계약 → 클라이언트 타입 생성 |
| `typescript` | `5.9.3` | `Apache-2.0` | `dev` | 언어(5.9.3 — Q7) |
| `typescript-eslint` | `8.71.1` | `MIT` | `dev` | 타입 인식 린트 |
| `vite` | `8.3.4` | `MIT` | `dev` | 번들러 |
| `vitest` | `5.0.3` | `MIT` | `dev` | 단위·컴포넌트 시험 |
<!-- /licenses:direct -->

## 허용 목록 밖 라이선스 (dev 전용 예외)

<!-- licenses:exceptions -->
| 패키지 | 버전 | 라이선스 | 사유 |
|---|---|---|---|
| `@axe-core/playwright` | `4.13.0` | `MPL-2.0` | 접근성 E2E 전용(승인된 스택). 배포 산출물에 없음 |
| `@csstools/color-helpers` | `6.1.2` | `MIT-0` | jsdom(시험 DOM)의 전이 의존. MIT-0(고지 의무 없는 MIT) |
| `@csstools/css-syntax-patches-for-csstree` | `1.1.15` | `MIT-0` | jsdom의 전이 의존. MIT-0 |
| `argparse` | `2.0.1` | `Python-2.0` | openapi-typescript → js-yaml의 전이 의존(생성 시점 도구). PSF 계열 허용형 |
| `axe-core` | `4.13.0` | `MPL-2.0` | @axe-core/playwright의 엔진. E2E가 시험 페이지에 주입할 뿐 배포 산출물에 없음 |
| `caniuse-lite` | `1.0.30001815` | `CC-BY-4.0` | eslint-plugin-react-hooks → browserslist의 데이터(린트 시점). CC-BY-4.0 데이터 |
| `lightningcss` | `1.33.0` | `MPL-2.0` | Vite 8의 CSS 변환·압축(빌드 도구). 산출 CSS에 라이브러리 코드가 들어가지 않음 |
| `lightningcss-android-arm64` | `1.33.0` | `MPL-2.0` | lightningcss의 플랫폼별 바이너리(설치되는 것은 실행 플랫폼 하나) |
| `lightningcss-darwin-arm64` | `1.33.0` | `MPL-2.0` | lightningcss의 플랫폼별 바이너리(설치되는 것은 실행 플랫폼 하나) |
| `lightningcss-darwin-x64` | `1.33.0` | `MPL-2.0` | lightningcss의 플랫폼별 바이너리(설치되는 것은 실행 플랫폼 하나) |
| `lightningcss-freebsd-x64` | `1.33.0` | `MPL-2.0` | lightningcss의 플랫폼별 바이너리(설치되는 것은 실행 플랫폼 하나) |
| `lightningcss-linux-arm-gnueabihf` | `1.33.0` | `MPL-2.0` | lightningcss의 플랫폼별 바이너리(설치되는 것은 실행 플랫폼 하나) |
| `lightningcss-linux-arm64-gnu` | `1.33.0` | `MPL-2.0` | lightningcss의 플랫폼별 바이너리(설치되는 것은 실행 플랫폼 하나) |
| `lightningcss-linux-arm64-musl` | `1.33.0` | `MPL-2.0` | lightningcss의 플랫폼별 바이너리(설치되는 것은 실행 플랫폼 하나) |
| `lightningcss-linux-x64-gnu` | `1.33.0` | `MPL-2.0` | lightningcss의 플랫폼별 바이너리(설치되는 것은 실행 플랫폼 하나) |
| `lightningcss-linux-x64-musl` | `1.33.0` | `MPL-2.0` | lightningcss의 플랫폼별 바이너리(설치되는 것은 실행 플랫폼 하나) |
| `lightningcss-win32-arm64-msvc` | `1.33.0` | `MPL-2.0` | lightningcss의 플랫폼별 바이너리(설치되는 것은 실행 플랫폼 하나) |
| `lightningcss-win32-x64-msvc` | `1.33.0` | `MPL-2.0` | lightningcss의 플랫폼별 바이너리(설치되는 것은 실행 플랫폼 하나) |
| `mdn-data` | `2.27.1` | `CC0-1.0` | jsdom → css-tree의 데이터. CC0 |
| `type-fest` | `4.41.0` | `(MIT OR CC0-1.0)` | openapi-typescript → parse-json의 타입 전용 패키지. MIT 선택 가능 이중 라이선스 |
<!-- /licenses:exceptions -->

## 번들 자원

| 자원 | 출처 | 라이선스 |
|---|---|---|
| NanumGothic Regular·Bold(TTF) | `disclosure-seal/src/main/resources/render/fonts/` — PDF 렌더러와 **같은 파일**(빌드 산출 스캔이 바이트 해시로 확인) | OFL-1.1 (`OFL.txt`) |

## OSV

`node scripts/osv.mjs`(수동, 네트워크 — 빌드에 넣지 않는다)가 `package-lock.json`의 모든 항목을 `api.osv.dev/v1/querybatch`로 묻는다.

| 일자 | 조회 | 권고 있음 |
|---|---|---|
| 2026-10-10 | 296 항목(설치되지 않는 플랫폼별 선택 의존 포함) | 0 |
