package com.ga.disclosure.audit.chain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 봉인 체인을 한 고리씩 흘려 걷는다({@link SealChain#next} 재계산). 전 구간은 {@link #fromGenesis}(seq 0, {@link SealChain#ZERO}),
 * 영수증 구간은 {@link #from}(직전 앵커의 seq·머리)에서 시작한다(5 계획 §8.2·§8.3). 갭과 해시 불일치를 모으고, checkpoint seq의
 * 재계산 머리만 기억한다. 단절 뒤에는 저장된 {@code chain_hash}로 이어 걸어 같은 단절을 한 번만 보고한다.
 */
public final class SealChainWalker {

    private final Set<Long> checkpoints;
    private final Map<Long, String> heads = new HashMap<>();
    private final List<ChainBreak> breaks = new ArrayList<>();
    private final long startSeq;
    private final String startHead;
    private String prev;
    private long expectedSeq;

    private SealChainWalker(long startSeq, String startHead, Set<Long> checkpoints) {
        if (startSeq < 0) {
            throw new IllegalArgumentException("start seq must not be negative");
        }
        this.startSeq = startSeq;
        this.startHead = Objects.requireNonNull(startHead, "startHead");
        this.checkpoints = Set.copyOf(Objects.requireNonNull(checkpoints, "checkpoints"));
        this.prev = startHead;
        this.expectedSeq = startSeq + 1;
    }

    public static SealChainWalker fromGenesis(Set<Long> checkpoints) {
        return new SealChainWalker(0, SealChain.ZERO, checkpoints);
    }

    public static SealChainWalker from(long seq, String head, Set<Long> checkpoints) {
        return new SealChainWalker(seq, head, checkpoints);
    }

    public void accept(SealLink link) {
        if (link.chainSeq() != expectedSeq) {
            breaks.add(new ChainBreak(link.chainSeq(), ChainBreak.Kind.SEQ_GAP, expectedSeq));
        }
        String recomputed = SealChain.next(prev, link.canonicalHash(), link.pdfHash());
        if (!recomputed.equals(link.chainHash())) {
            breaks.add(new ChainBreak(link.chainSeq(), ChainBreak.Kind.HASH_MISMATCH, expectedSeq));
        }
        if (checkpoints.contains(link.chainSeq())) {
            heads.put(link.chainSeq(), recomputed);
        }
        prev = link.chainHash();
        expectedSeq = link.chainSeq() + 1;
    }

    public List<ChainBreak> breaks() {
        return List.copyOf(breaks);
    }

    /** 걷기에서 다시 계산한 seq 시점의 머리. 시작 seq는 시작 머리. checkpoint가 아니면 예외, 닿지 못했으면 빈 값. */
    public Optional<String> headAt(long seq) {
        if (seq == startSeq) {
            return Optional.of(startHead);
        }
        if (!checkpoints.contains(seq)) {
            throw new IllegalArgumentException("seq " + seq + " was not declared as a checkpoint");
        }
        return Optional.ofNullable(heads.get(seq));
    }

    public long lastSeq() {
        return expectedSeq - 1;
    }
}
