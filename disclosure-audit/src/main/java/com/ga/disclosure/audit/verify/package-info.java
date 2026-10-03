/**
 * {@code verify} 보고서와 {@code verify package}(5 계획 §4·§8.3, 승인 Q1). 패키지 검증은 DB·키·네트워크·파일 시스템·생산자 코드(seal)·workflow에
 * 의존하지 않는다 — 입력 바이트를 받아 자체 리더로 읽고 계약 스키마로 직접 검증한다(ArchitectureRulesTest). {@code verify tenant}의 조정은 workflow가
 * 하고 보고서 모델·체인 걷기·머클·TSA 검증은 여기 것을 쓴다.
 */
package com.ga.disclosure.audit.verify;
