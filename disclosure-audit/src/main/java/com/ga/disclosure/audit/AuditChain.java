package com.ga.disclosure.audit;

import com.ga.disclosure.audit.chain.ChainBreak;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.canonical.Sha256;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * 감사 체인의 정의(설계서 §5·§6.7).
 * <ul>
 *   <li>정규화 엔트리 = JCS({@code {tenantId, seq, at, actorSubject, actorRole, action, targetKind, targetId, detail}}),
 *       {@code at}은 UTC ISO-8601({@link java.time.Instant#toString()}, 마이크로초 절삭), 값이 없으면 JSON null.</li>
 *   <li>{@code entryHash = SHA-256(ASCII(prevHash) ‖ 정규화 엔트리)}, 소문자 hex.</li>
 *   <li>테넌트의 첫 행({@code seq = 1})의 {@code prevHash}는 {@link #GENESIS}(0이 64개).</li>
 * </ul>
 */
public final class AuditChain {

    public static final String GENESIS = "0".repeat(64);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private AuditChain() {
    }

    public static byte[] canonicalEntry(TenantId tenant, long seq, AuditEntry entry) {
        ObjectNode n = JSON.createObjectNode();
        n.put("tenantId", tenant.value());
        n.put("seq", seq);
        n.put("at", entry.at().toString());
        n.put("actorSubject", entry.actorSubject());
        n.put("actorRole", entry.actorRole());
        n.put("action", entry.action().name());
        n.put("targetKind", entry.targetKind());
        n.put("targetId", entry.targetId());
        n.set("detail", entry.detail());
        return Canonicalizer.canonicalize(n);
    }

    public static String entryHash(String prevHash, byte[] canonicalEntry) {
        byte[] prev = prevHash.getBytes(StandardCharsets.US_ASCII);
        byte[] input = new byte[prev.length + canonicalEntry.length];
        System.arraycopy(prev, 0, input, 0, prev.length);
        System.arraycopy(canonicalEntry, 0, input, prev.length, canonicalEntry.length);
        return Sha256.of(input);
    }

    /** 다음 행: 직전 행(없으면 체인 시작)에 이어지는 seq·prevHash·entryHash. */
    public static AuditRecord next(TenantId tenant, AuditRecord previousOrNull, AuditEntry entry) {
        long seq = previousOrNull == null ? 1 : previousOrNull.seq() + 1;
        String prev = previousOrNull == null ? GENESIS : previousOrNull.entryHash();
        return new AuditRecord(tenant, seq, entry, prev, entryHash(prev, canonicalEntry(tenant, seq, entry)));
    }

    /**
     * 한 테넌트의 행 목록(seq 오름차순)을 처음부터 재계산해 어긋난 곳을 돌려준다 — seq 갭·중복, prevHash 단절, entryHash 불일치.
     * 흘려 읽는 걷기({@link AuditChainWalker})의 목록판이다.
     */
    public static List<String> breaks(List<AuditRecord> records) {
        AuditChainWalker walker = new AuditChainWalker(Set.of());
        records.forEach(walker::accept);
        return walker.breaks().stream().map(ChainBreak::message).toList();
    }
}
