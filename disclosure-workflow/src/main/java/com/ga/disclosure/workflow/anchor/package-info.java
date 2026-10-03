/**
 * 일일 앵커 조정(5 계획 §8.1, 승인 Q1 — 배치는 workflow, 순수 계산은 audit): 테넌트마다 두 체인 머리를 고정하고(A단계), 영수증 없는 앵커 전부를
 * 날짜별 머클 루트 하나로 묶어 TSA 토큰을 받아 테넌트마다 영수증을 쓴다(B단계). 스케줄 등록은 Phase 6, 지금은 CLI {@code anchor run}.
 */
package com.ga.disclosure.workflow.anchor;
