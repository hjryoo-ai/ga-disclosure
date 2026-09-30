# 봉인 렌더 자산(저장소 동봉, 빌드 시 내려받지 않는다 — 3A 계획 승인 §3)

| 파일 | 출처 | 라이선스 | SHA-256 |
|---|---|---|---|
| `fonts/NanumGothic-Regular.ttf` | google/fonts `ofl/nanumgothic` | SIL OFL 1.1 (`fonts/OFL.txt`) | `76f45ef4a6bcff344c837c95a7dcc26e017e38b5846d5ae0cdcb5b86be2e2d31` |
| `fonts/NanumGothic-Bold.ttf` | google/fonts `ofl/nanumgothic` | SIL OFL 1.1 (`fonts/OFL.txt`) | `f96298f9fb18e364d2370f4c3ce948ac67a2b61af992d7234bc15c42b033c674` |
| `icc/sRGB-v2-magic.icc` | saucecontrol/Compact-ICC-Profiles `profiles/sRGB-v2-magic.icc` (ICC v2, 736 B) | CC0 1.0 (`icc/LICENSE-CC0.txt`) | `af4efe28f6d311799865f325ba39184a2e978b113ca74124d60af7de22b105f4` |

PDF/A 출력 의도(OutputIntent)의 sRGB 프로파일을 JDK 내장값이 아니라 이 파일로 고정해 JDK·OS에 따른 차이를 없앤다. 3B 봉인 렌더러와
`verification/pdf-candidates/`가 같은 파일을 쓴다. 파일을 바꾸면 위 해시와 3B 결정론 테스트의 기대 해시를 함께 고친다.
