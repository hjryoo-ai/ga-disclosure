package com.ga.disclosure.workflow.verify;

import com.ga.disclosure.domain.vo.DisclosureId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 바인딩된 테넌트의 봉인 체인 고리(해시만, 읽기 전용 — infra가 구현, RLS). 파기된 확인서도 묘비로 남아 그대로 읽힌다. */
public interface SealChainReader {

    /** {@code from ≤ chain_seq ≤ to}인 봉인 확인서(체인 순번 오름차순). */
    List<ChainRow> links(long fromSeq, long toSeq);

    Optional<ChainRow> byDisclosure(DisclosureId id);

    /** 체인 머리 순번(봉인이 없으면 0). */
    long headSeq();

    record ChainRow(long chainSeq, DisclosureId disclosureId, String disclosureNo, String canonicalHash, String pdfHash, String chainHash,
                    Instant sealedAt, Instant destroyedAtOrNull) {
    }
}
