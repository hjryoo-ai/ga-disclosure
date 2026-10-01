package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.vo.ChainHash;
import com.ga.disclosure.domain.vo.DisclosureNo;
import com.ga.disclosure.domain.vo.Sha256;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 봉인 결과(V7 봉인 컬럼 7개): 번호·봉인 시각·canonical·PDF 해시·체인·보존기한. 봉인 유스케이스가 한 트랜잭션에서 채번·렌더·체인 계산을 마친 뒤
 * 애그리게이트에 건넨다. 봉인 이후 불변(V3 본문 컬럼), 보존기한만 연장 가능(GD094).
 */
public record SealStamp(DisclosureNo number, Instant sealedAt, Sha256 canonicalHash, Sha256 pdfHash, ChainHash chainHash, long chainSeq,
                        LocalDate retentionUntil) {

    public SealStamp {
        Objects.requireNonNull(number, "number");
        Objects.requireNonNull(sealedAt, "sealedAt");
        Objects.requireNonNull(canonicalHash, "canonicalHash");
        Objects.requireNonNull(pdfHash, "pdfHash");
        Objects.requireNonNull(chainHash, "chainHash");
        Objects.requireNonNull(retentionUntil, "retentionUntil");
        if (chainSeq < 1) {
            throw new IllegalArgumentException("chain_seq starts at 1");
        }
    }
}
