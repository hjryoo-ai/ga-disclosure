/**
 * 비밀 출처 포트(Phase 8, 8 계획 ④): 서버 비밀(테넌트 KEK·커서·요청 해시·영수증·데모 OIDC·TSA 신뢰 앵커·엔진 토큰·백업 키)을 이름으로 읽는다.
 * 어댑터는 파일(쿠버네티스 Secret 마운트·로컬 디렉터리)·환경변수(개발 전용)이고, 외부 비밀 저장소는 External Secrets Operator가 Secret으로 옮겨 파일
 * 어댑터로 읽는다(실 벤더 연동 없음 — 지시문). 값은 로그·예외 문장에 싣지 않는다 — 이름만.
 */
package com.ga.disclosure.workflow.secret;
