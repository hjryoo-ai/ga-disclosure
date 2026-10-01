package com.ga.disclosure.workflow.disclosure;

import java.util.Objects;
import java.util.Optional;

/**
 * 채번 카운터·봉인 체인 머리 포트(V7, 바인딩된 테넌트의 봉인 트랜잭션 안). 잠금 순서는 모든 봉인에서 같다: 확인서 행 → 카운터 행(테넌트, 연도) →
 * 체인 머리 행(테넌트)(3B 계획 승인 Q7). 번호는 선할당하지 않는다 — 채번은 봉인 트랜잭션 안에서 한 번, 실패하면 롤백으로 되돌아간다(무결번).
 */
public interface SealLedgerPort {

    /** {@code INSERT … ON CONFLICT (tenant, year) DO UPDATE SET seq = seq + 1 RETURNING seq} — 카운터 행을 잠그고 다음 번호를 낸다. */
    int issueNumber(int year);

    /** 체인 머리를 {@code FOR UPDATE}로 잠그고 읽는다. 테넌트의 첫 봉인이면 빈 값. */
    Optional<ChainLink> lockChainHead();

    /** 머리를 방금 봉인한 확인서로 옮긴다(첫 봉인이면 INSERT). 머리는 언제나 실재하는 마지막 봉인을 가리킨다(GD091). */
    void advanceChainHead(ChainLink next);

    /** 체인 고리: 순번과 해시(소문자 hex). */
    record ChainLink(long seq, String hash) {
        public ChainLink {
            Objects.requireNonNull(hash, "hash");
            if (seq < 1 || !hash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("chain link needs seq ≥ 1 and a 64-hex hash");
            }
        }
    }
}
