/**
 * 로컬 스텁 TSA(BC {@code TimeStampResponseGenerator}, 자체 서명 ECDSA P-256). 테스트는 실행마다 키를 만들고, 데모는 저장소 밖
 * 파일에 한 번 만들어 재사용한다(5 계획 §3, 승인 Q11). 키는 커밋하지 않는다.
 */
package com.ga.disclosure.audit.tsa.stub;
