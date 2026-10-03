package com.ga.disclosure.audit.anchor;

import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 일일 테넌트 앵커 레코드(5 계획 §2.1, 설계서 §6.7 {@code merkle-spec}): 그 KST 날짜에 고정한 봉인 체인 머리와 감사 체인 머리. 잎 =
 * SHA-256(0x00 ‖ JCS(레코드)) — 키 8개(anchorDate, anchorSeq, auditHead, auditSeq, sealChainHead, sealChainSeq, tenantId, v)는 고정이고
 * 값은 패턴 제한 문자·정수·ISO 날짜·소문자 hex뿐이다(DB V9 GD110이 같은 바이트를 만들어 다시 계산한다). 봉인·감사가 아직 없으면 seq 0과
 * 64개 0.
 */
public record AnchorRecord(TenantId tenant, long anchorSeq, LocalDate anchorDate, long sealChainSeq, String sealChainHead, long auditSeq,
                           String auditHead) {

    public static final int VERSION = 1;
    private static final Pattern HEX64 = Pattern.compile("^[0-9a-f]{64}$");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public AnchorRecord {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(anchorDate, "anchorDate");
        if (anchorSeq < 1 || sealChainSeq < 0 || auditSeq < 0) {
            throw new IllegalArgumentException("anchor numbers out of range");
        }
        if (!HEX64.matcher(Objects.requireNonNull(sealChainHead, "sealChainHead")).matches()
                || !HEX64.matcher(Objects.requireNonNull(auditHead, "auditHead")).matches()) {
            throw new IllegalArgumentException("anchor heads are lowercase SHA-256 hex");
        }
    }

    /** JCS 바이트(잎의 입력). */
    public byte[] canonical() {
        ObjectNode n = JSON.createObjectNode();
        n.put("anchorDate", anchorDate.toString());
        n.put("anchorSeq", anchorSeq);
        n.put("auditHead", auditHead);
        n.put("auditSeq", auditSeq);
        n.put("sealChainHead", sealChainHead);
        n.put("sealChainSeq", sealChainSeq);
        n.put("tenantId", tenant.value());
        n.put("v", VERSION);
        return Canonicalizer.canonicalize(n);
    }

    public String leafHash() {
        return MerkleTree.leaf(canonical());
    }
}
