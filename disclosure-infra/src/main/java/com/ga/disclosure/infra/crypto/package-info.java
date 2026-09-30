/**
 * 고객 개인정보 컬럼 암호화(설계서 §9). {@code javax.crypto}를 쓰는 곳은 이 패키지뿐이다(아키텍처 테스트로 강제).
 * AES-256-GCM, 96비트 랜덤 nonce, 128비트 태그. 저장 형식 {@code 0x01 ‖ nonce ‖ ciphertext‖tag}. AAD는 JCS 정규 JSON이다.
 */
package com.ga.disclosure.infra.crypto;
