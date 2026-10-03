package com.ga.disclosure.audit;

import com.ga.disclosure.audit.chain.ChainBreak;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 감사 체인을 한 행씩 흘려 걷는다(5 계획 §8.4 — 커서로 읽어 전부를 메모리에 두지 않는다). seq 갭·중복, prevHash 단절, entryHash
 * 불일치를 모으고, 미리 지정한 checkpoint seq(앵커의 {@code auditSeq})에서의 재계산 머리만 기억한다 — 앵커 대조는 저장된 값이 아니라
 * 걷기에서 다시 계산한 머리와 한다. 행은 seq 오름차순으로 넣는다.
 */
public final class AuditChainWalker {

    private final Set<Long> checkpoints;
    private final Map<Long, String> heads = new HashMap<>();
    private final List<ChainBreak> breaks = new ArrayList<>();
    private String prev = AuditChain.GENESIS;
    private long expectedSeq = 1;
    private long lastSeq;

    public AuditChainWalker(Set<Long> checkpoints) {
        this.checkpoints = Set.copyOf(Objects.requireNonNull(checkpoints, "checkpoints"));
    }

    public void accept(AuditRecord r) {
        if (r.seq() != expectedSeq) {
            breaks.add(new ChainBreak(r.seq(), ChainBreak.Kind.SEQ_GAP, expectedSeq));
        }
        if (!r.prevHash().equals(prev)) {
            breaks.add(new ChainBreak(r.seq(), ChainBreak.Kind.PREV_MISMATCH, expectedSeq));
        }
        String recomputed = AuditChain.entryHash(r.prevHash(), AuditChain.canonicalEntry(r.tenantId(), r.seq(), r.entry()));
        if (!recomputed.equals(r.entryHash())) {
            breaks.add(new ChainBreak(r.seq(), ChainBreak.Kind.HASH_MISMATCH, expectedSeq));
        }
        if (checkpoints.contains(r.seq())) {
            heads.put(r.seq(), recomputed);
        }
        prev = r.entryHash();
        expectedSeq = r.seq() + 1;
        lastSeq = r.seq();
    }

    public List<ChainBreak> breaks() {
        return List.copyOf(breaks);
    }

    /** 걷기에서 다시 계산한 seq 시점의 머리. checkpoint가 아니거나 그 seq에 닿지 못했으면 빈 값. seq 0은 체인 시작. */
    public Optional<String> headAt(long seq) {
        if (seq == 0) {
            return Optional.of(AuditChain.GENESIS);
        }
        if (!checkpoints.contains(seq)) {
            throw new IllegalArgumentException("seq " + seq + " was not declared as a checkpoint");
        }
        return Optional.ofNullable(heads.get(seq));
    }

    public long lastSeq() {
        return lastSeq;
    }
}
