package com.ga.disclosure.audit;

import java.util.List;

/**
 * 감사 기록 포트(infra 어댑터가 구현). 호출자의 업무 트랜잭션 안에서 실행된다(설계서 §6.7 "같은 트랜잭션") — 업무가 롤백되면
 * 감사 행도 남지 않는다. 테넌트별 append는 직렬화되어 seq에 중복·갭이 없다.
 */
public interface AuditPort {

    AuditRecord append(AuditEntry entry);

    /** 바인딩된 테넌트의 전 행(seq 오름차순). */
    List<AuditRecord> readAll();
}
