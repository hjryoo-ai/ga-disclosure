package com.ga.disclosure.audit.chain;

import java.util.Objects;

/** 봉인 체인의 한 고리(해시만): {@code chain_seq}와 그 봉인의 canonical·PDF 해시, 저장된 {@code chain_hash}. 파기된 확인서도 묘비로 남는다. */
public record SealLink(long chainSeq, String canonicalHash, String pdfHash, String chainHash) {

    public SealLink {
        Objects.requireNonNull(canonicalHash, "canonicalHash");
        Objects.requireNonNull(pdfHash, "pdfHash");
        Objects.requireNonNull(chainHash, "chainHash");
    }
}
