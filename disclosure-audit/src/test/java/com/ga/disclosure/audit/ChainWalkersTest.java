package com.ga.disclosure.audit;

import com.ga.disclosure.audit.chain.ChainBreak;
import com.ga.disclosure.audit.chain.SealChain;
import com.ga.disclosure.audit.chain.SealChainWalker;
import com.ga.disclosure.audit.chain.SealLink;
import com.ga.platform.canonical.Sha256;
import com.ga.platform.core.tenant.TenantId;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 흘려 걷기(5 계획 §8.4): 단절·갭·내용 변조 검출, checkpoint 머리는 저장값이 아니라 재계산값, 영수증 구간은 직전 머리에서 시작. */
class ChainWalkersTest {

    static final TenantId T = TenantId.of("T1");
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void auditWalkerRemembersRecomputedHeadsOnlyAtCheckpoints() {
        List<AuditRecord> chain = auditChain(6);
        AuditChainWalker clean = new AuditChainWalker(Set.of(2L, 6L));
        chain.forEach(clean::accept);

        assertThat(clean.breaks()).isEmpty();
        assertThat(clean.headAt(0)).contains(AuditChain.GENESIS);
        assertThat(clean.headAt(2)).contains(chain.get(1).entryHash());
        assertThat(clean.headAt(6)).contains(chain.get(5).entryHash());
        assertThat(clean.lastSeq()).isEqualTo(6);
        assertThatThrownBy(() -> clean.headAt(3)).isInstanceOf(IllegalArgumentException.class);

        AuditChainWalker shortWalk = new AuditChainWalker(Set.of(9L));
        chain.forEach(shortWalk::accept);
        assertThat(shortWalk.headAt(9)).isEmpty();
    }

    @Test
    void anEditedAuditRowIsReportedOnceAndItsCheckpointHeadIsTheRecomputedOne() {
        List<AuditRecord> chain = auditChain(5);
        AuditRecord third = chain.get(2);
        chain.set(2, new AuditRecord(T, 3, entry(99), third.prevHash(), third.entryHash()));
        AuditChainWalker walker = new AuditChainWalker(Set.of(3L));
        chain.forEach(walker::accept);

        assertThat(walker.breaks()).containsExactly(new ChainBreak(3, ChainBreak.Kind.HASH_MISMATCH, 3));
        assertThat(walker.headAt(3)).isPresent().get().isNotEqualTo(third.entryHash());
    }

    @Test
    void sealWalkerFindsGapsAndTamperedLinksOnce() {
        List<SealLink> links = sealChain(SealChain.ZERO, 1, 5);
        SealChainWalker clean = SealChainWalker.fromGenesis(Set.of(5L));
        links.forEach(clean::accept);
        assertThat(clean.breaks()).isEmpty();
        assertThat(clean.headAt(5)).contains(links.get(4).chainHash());
        assertThat(clean.headAt(0)).contains(SealChain.ZERO);

        List<SealLink> tampered = new ArrayList<>(links);
        SealLink third = tampered.get(2);
        tampered.set(2, new SealLink(3, third.canonicalHash(), Sha256.of("other pdf".getBytes()), third.chainHash()));
        SealChainWalker walker = SealChainWalker.fromGenesis(Set.of());
        tampered.forEach(walker::accept);
        assertThat(walker.breaks()).containsExactly(new ChainBreak(3, ChainBreak.Kind.HASH_MISMATCH, 3));

        List<SealLink> gap = new ArrayList<>(links);
        gap.remove(1);
        SealChainWalker gapped = SealChainWalker.fromGenesis(Set.of());
        gap.forEach(gapped::accept);
        assertThat(gapped.breaks()).extracting(ChainBreak::kind).containsExactly(ChainBreak.Kind.SEQ_GAP, ChainBreak.Kind.HASH_MISMATCH);
    }

    @Test
    void aReceiptSegmentStartsAtThePreviousAnchorHead() {
        List<SealLink> links = sealChain(SealChain.ZERO, 1, 6);
        SealChainWalker segment = SealChainWalker.from(2, links.get(1).chainHash(), Set.of(6L));
        links.subList(2, 6).forEach(segment::accept);
        assertThat(segment.breaks()).isEmpty();
        assertThat(segment.headAt(2)).contains(links.get(1).chainHash());
        assertThat(segment.headAt(6)).contains(links.get(5).chainHash());

        SealChainWalker wrongStart = SealChainWalker.from(2, links.get(0).chainHash(), Set.of());
        links.subList(2, 6).forEach(wrongStart::accept);
        assertThat(wrongStart.breaks()).containsExactly(new ChainBreak(3, ChainBreak.Kind.HASH_MISMATCH, 3));
    }

    private static List<AuditRecord> auditChain(int n) {
        List<AuditRecord> out = new ArrayList<>();
        AuditRecord prev = null;
        for (int i = 0; i < n; i++) {
            prev = AuditChain.next(T, prev, entry(i));
            out.add(prev);
        }
        return out;
    }

    private static AuditEntry entry(int i) {
        return new AuditEntry(Instant.parse("2026-10-03T00:00:00Z").plusSeconds(i), "ops@example", "OPERATOR", AuditAction.RULE_DISTRIBUTE,
                "RULE_VERSION", "R-" + i, JSON.readTree("{\"i\":" + i + "}"));
    }

    private static List<SealLink> sealChain(String start, long fromSeq, long toSeq) {
        List<SealLink> out = new ArrayList<>();
        String prev = start;
        for (long seq = fromSeq; seq <= toSeq; seq++) {
            String canonical = Sha256.of(("canonical-" + seq).getBytes());
            String pdf = Sha256.of(("pdf-" + seq).getBytes());
            prev = SealChain.next(prev, canonical, pdf);
            out.add(new SealLink(seq, canonical, pdf, prev));
        }
        return out;
    }
}
