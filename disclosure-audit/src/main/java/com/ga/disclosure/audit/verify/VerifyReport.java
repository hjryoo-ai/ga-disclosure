package com.ga.disclosure.audit.verify;

import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.canonical.Sha256;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code verify} 보고서(스키마 {@code contracts/verify/v1/verify-report.schema.json}, 5 계획 §4). 직렬화는 JCS이고 {@code VERIFY_RUN} 감사에 그
 * SHA-256을 남긴다. 결과는 발견이 하나도 없을 때만 MATCH(종료 0)이고 그 밖에는 MISMATCH(종료 2)다.
 */
public record VerifyReport(String verifierVersion, Kind kind, Inputs inputs, List<Check> checks, List<Finding> findings, List<String> statements,
                           Conclusion conclusion, Counts counts) {

    public static final int REPORT_VERSION = 1;
    public static final int EXIT_MATCH = 0;
    public static final int EXIT_MISMATCH = 2;
    public static final int EXIT_INPUT_ERROR = 3;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public enum Kind { PACKAGE, TENANT }

    public enum Status { PASS, FAIL, SKIPPED }

    public record FileDigest(String sha256, long bytes) {
        public static FileDigest of(byte[] content) {
            return new FileDigest(Sha256.of(content), content.length);
        }
    }

    public record Inputs(FileDigest packageFile, FileDigest receipt, FileDigest trust, String tenantId, Long from, Long to, Instant asOf) {
        public Inputs {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(asOf, "asOf");
        }
    }

    public record Check(String check, Status status, int count) {
    }

    /** 발견 하나: 위치(seq·ID·엔트리 이름 등)와 세부(기대·실제 해시 등). 값은 문자열·정수·불리언·null뿐이고 개인정보를 싣지 않는다. */
    public record Finding(FindingCode code, Map<String, Object> where, Map<String, Object> detail) {
        public Finding {
            Objects.requireNonNull(code, "code");
            where = new LinkedHashMap<>(where);
            detail = new LinkedHashMap<>(detail);
        }
    }

    /** 자체 기록인 하한(승인 Q13 대안). */
    public record SealedAfter(LocalDate anchorDate, long sealChainSeq, Instant recordedAt) {
    }

    public record Conclusion(Instant existedBefore, SealedAfter sealedAfter, Boolean tsaTrusted) {
        public static final Conclusion NONE = new Conclusion(null, null, null);
    }

    public record Counts(long disclosures, long objects, long auditRows, long anchors, long receipts) {
    }

    public VerifyReport {
        Objects.requireNonNull(verifierVersion, "verifierVersion");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(inputs, "inputs");
        checks = List.copyOf(checks);
        findings = List.copyOf(findings);
        statements = List.copyOf(statements);
        Objects.requireNonNull(conclusion, "conclusion");
        Objects.requireNonNull(counts, "counts");
    }

    public boolean matches() {
        return findings.isEmpty();
    }

    public int exitCode() {
        return matches() ? EXIT_MATCH : EXIT_MISMATCH;
    }

    public ObjectNode toJson() {
        ObjectNode n = JSON.createObjectNode();
        n.put("reportVersion", REPORT_VERSION);
        n.put("verifierVersion", verifierVersion);
        n.put("kind", kind.name());
        ObjectNode in = n.putObject("inputs");
        digest(in, "package", inputs.packageFile());
        digest(in, "receipt", inputs.receipt());
        digest(in, "trust", inputs.trust());
        in.put("tenantId", inputs.tenantId());
        if (inputs.from() == null) {
            in.putNull("from");
        } else {
            in.put("from", inputs.from());
        }
        if (inputs.to() == null) {
            in.putNull("to");
        } else {
            in.put("to", inputs.to());
        }
        in.put("asOf", inputs.asOf().toString());
        n.put("result", matches() ? "MATCH" : "MISMATCH");
        ArrayNode cs = n.putArray("checks");
        checks.forEach(c -> cs.addObject().put("check", c.check()).put("status", c.status().name()).put("count", c.count()));
        ArrayNode fs = n.putArray("findings");
        for (Finding f : findings) {
            ObjectNode o = fs.addObject();
            o.put("code", f.code().name());
            values(o.putObject("where"), f.where());
            values(o.putObject("detail"), f.detail());
        }
        ArrayNode ss = n.putArray("statements");
        statements.forEach(ss::add);
        ObjectNode c = n.putObject("conclusion");
        if (conclusion.existedBefore() == null) {
            c.putNull("existedBefore");
        } else {
            c.put("existedBefore", conclusion.existedBefore().toString());
        }
        if (conclusion.sealedAfter() == null) {
            c.putNull("sealedAfter");
        } else {
            c.putObject("sealedAfter").put("anchorDate", conclusion.sealedAfter().anchorDate().toString())
                    .put("sealChainSeq", conclusion.sealedAfter().sealChainSeq()).put("recordedAt", conclusion.sealedAfter().recordedAt().toString());
        }
        if (conclusion.tsaTrusted() == null) {
            c.putNull("tsaTrusted");
        } else {
            c.put("tsaTrusted", conclusion.tsaTrusted());
        }
        n.putObject("counts").put("disclosures", counts.disclosures()).put("objects", counts.objects()).put("auditRows", counts.auditRows())
                .put("anchors", counts.anchors()).put("receipts", counts.receipts());
        return n;
    }

    /** JCS 바이트. */
    public byte[] canonical() {
        return Canonicalizer.canonicalize(toJson());
    }

    public String sha256() {
        return Sha256.of(canonical());
    }

    private static void digest(ObjectNode parent, String key, FileDigest d) {
        if (d == null) {
            parent.putNull(key);
        } else {
            parent.putObject(key).put("sha256", d.sha256()).put("bytes", d.bytes());
        }
    }

    private static void values(ObjectNode target, Map<String, Object> values) {
        values.forEach((k, v) -> {
            switch (v) {
                case null -> target.putNull(k);
                case String s -> target.put(k, s);
                case Long l -> target.put(k, l);
                case Integer i -> target.put(k, i);
                case Boolean b -> target.put(k, b);
                default -> throw new IllegalArgumentException("finding values are strings, integers, booleans or null: " + k);
            }
        });
    }
}
