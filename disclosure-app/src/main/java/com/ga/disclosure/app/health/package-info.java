/**
 * 헬스(Phase 8, 지시문 §2 G7): 준비성(readiness) = DB({@link com.ga.disclosure.app.health.DatabaseHealthIndicator} — 전용 롤, 허용 목록 등재)·
 * 저장소·IdP JWKS, 활성(liveness) = 프로세스만. 관리 포트(인그레스 미노출)에서만 응답한다.
 */
package com.ga.disclosure.app.health;
