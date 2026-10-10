# 진입점·한도·브라우저 전제 (Phase 8 ③, G4)

> 학습·포트폴리오 저장소의 운영 문서다. 호스트 이름은 `.invalid` 예시값이다.

## 진입점 셋 — 경로 접두마다 포트·컨트롤러가 다르다

| 진입점 | 컨트롤러(네임스페이스) | 호스트·경로 | 앱 포트 | 인증 |
|---|---|---|---|---|
| A 직원 | `traefik-public`(`ga-ingress`) | `staff.ga.example.invalid` — `/api/` → `ga-app:8080`, 그 밖 → `ga-web`(직원 화면) | 8080 | 사내 IdP JWT(사람 역할) |
| B 고객 서명 | `traefik-public`(`ga-ingress`) | `sign.ga.example.invalid` — `/public/` → `ga-app:8080`, `/s`·`/assets/` → `ga-web` | 8080 | 서명 토큰(URL 조각 → 헤더) |
| C 내부 | `traefik-internal`(`ga-ingress-internal`, ClusterIP) | `/internal/` → `ga-app-internal:8081` | 8081 | mTLS(그 네임스페이스의 `TLSOption default` = `RequireAndVerifyClientCert`) |
| 관리 | 진입점 없음 | `/actuator/health/{readiness,liveness}`, `/actuator/prometheus` | 8082 | 클러스터 안(kubelet·수집기) |

`/internal`이 밖에서 닿지 않는 것은 세 겹이다(승인 Q5): ① 공개 컨트롤러에 그 라우트가 없다(린트 — 주입 P8-25/25b) ② 앱이 `/internal`을 내부 포트에서만
받는다(`PortChannelFilter` — 라우팅 파서로 판정, 주입 P8-12·SEC-1) ③ NetworkPolicy가 8081을 내부 컨트롤러 파드에서만 받는다(`deploy/base/networkpolicies.yaml`
— 주입 P8-25c). kind 스모크가 셋 다 밖에서 두드린다(`kind.sh smoke`).

공개 라우트는 첫 미들웨어로 클라이언트 인증서 주체 헤더를 지운다(`strip-client-cert` — 밖에서 위조한 주체가 앱에 닿지 않게, 주입 P8-26). 컨트롤러는 자기
네임스페이스 Role만 갖고(앱 네임스페이스의 Secret을 읽을 수 없다 — SEC-4), 노드 읽기만 ClusterRole `ga-traefik-nodes`(KIND-2).

## 한도

| 한도 | 어디서 | 값(예시) | 실효값 |
|---|---|---|---|
| 서명 호스트 IP당 요청 | Traefik `sign-rate-limit`(`ipStrategy.depth: 0` — 원격 주소, X-Forwarded-For 불신) | 분당 30, 버스트 30 | **30 × 공개 컨트롤러 복제본 수**(복제본마다 따로 센다 — kind 실측 KIND-4, 기본 2 → 60) |
| 서명 호스트 요청 크기 | Traefik `sign-body-limit` | 1 MiB | 같음 |
| 원 클라이언트 주소 | 공개 Service `externalTrafficPolicy: Local` | — | 다른 노드를 거친 요청의 SNAT 방지(SEC-5) |
| 공개 서명 테넌트 분당 | 앱 메모리(룰 값) | 번들 예시값 | **룰 값 × 앱 복제본 수** — 인증 전 경로라 DB 집계는 DB 부하를 키운다(계획 ②) |
| 고객 등록 주체 분당 | 앱 — 감사 행 DB 집계 | 룰 값 | 복제본 수와 무관 |
| 청약 게이트 주체 분당 | 앱 — `GATE_DECISION` 감사 행 DB 집계(주체별 advisory 잠금) | 룰 값(§14 #10) | 복제본 수와 무관(6B 이월 ② — 주입 P8-14) |

## 브라우저 전제

- **로그인은 새 창**(팝업)으로 열린다. PKCE verifier를 메모리에만 두기 위해서다(브라우저 저장소 0 원칙). 팝업이 막힌 브라우저에서는 로그인이 끝나지 않는다 —
  직원 호스트를 팝업 허용 목록에 넣는 것이 운영 전제다. 같은 창 리다이렉트 + `sessionStorage` verifier는 **채택하지 않는다**(토큰 교환 전의 짧은 값이라도
  저장소에 두지 않는다 — 7 수용심사 §2, 설계서 §8).
- **현장 서명 창**도 새 창이며, 서버가 준 서명 호스트 주소(`SessionIssueReceipt.signUrl` — `GA_SIGN_LINK_BASE_URL`)를 연다. 직원 호스트와 서명 호스트가
  다르므로 상대 경로로는 열 수 없다(KIND-10).
- 새로 고침 = 다시 로그인(토큰은 메모리에만).

## 확인

- 린트: `./gradlew :disclosure-app:deployTest`(`DeployRulesTest`).
- kind: `deploy/scripts/kind.sh smoke <클러스터>` — 평문 HTTP는 Traefik 자신의 404(앱·웹 미도달, KIND-5), 진입점 C는 클라이언트 인증서 없이 TLS 실패,
  공개 호스트의 `/internal`은 404, 한도는 복제본 수로 계산한 동시 요청에서 429, NetworkPolicy는 다른 파드에서 8081 연결 실패.
