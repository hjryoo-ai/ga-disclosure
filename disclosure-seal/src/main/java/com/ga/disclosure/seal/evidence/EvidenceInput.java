package com.ga.disclosure.seal.evidence;

import com.ga.disclosure.domain.enums.IdentityMethod;
import com.ga.disclosure.domain.enums.SignatureChannel;
import com.ga.disclosure.domain.enums.SignatureMethod;
import com.ga.disclosure.domain.enums.SignerRole;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 증거 패키지 입력(완료 트랜잭션이 모은다, 4 계획 §4·§7.3). 바이트 세 개(canonical·봉인 PDF·서명본)는 해시를 빌더가 다시 계산해 서명 레코드의
 * 귀속 해시와 대조한다. 감사 행은 이 확인서 대상 행 전부(seq 순, {@code entry_hash} 포함), 완료 감사 행 직전까지 — 매니페스트 {@code toSeq}가 경계.
 */
public record EvidenceInput(String tenantId, String disclosureId, String disclosureNo, int version, byte[] canonicalJson, byte[] pdf,
                            byte[] signedPdf, String chainHash, long chainSeq, Pinned pinned, Snapshot snapshot, Instant sealedAt,
                            Instant completedAt, LocalDate retentionUntil, List<SignatureRecord> signatures, List<AuditRow> audit,
                            AnchorRef anchor) {

    public EvidenceInput {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(disclosureId, "disclosureId");
        Objects.requireNonNull(disclosureNo, "disclosureNo");
        canonicalJson = canonicalJson.clone();
        pdf = pdf.clone();
        signedPdf = signedPdf.clone();
        Objects.requireNonNull(pinned, "pinned");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(sealedAt, "sealedAt");
        Objects.requireNonNull(completedAt, "completedAt");
        Objects.requireNonNull(retentionUntil, "retentionUntil");
        signatures = List.copyOf(signatures);
        audit = List.copyOf(audit);
    }

    @Override
    public byte[] canonicalJson() {
        return canonicalJson.clone();
    }

    @Override
    public byte[] pdf() {
        return pdf.clone();
    }

    @Override
    public byte[] signedPdf() {
        return signedPdf.clone();
    }

    /**
     * 완료 시점에 이미 있던 그 테넌트의 최신 일일 앵커(없으면 매니페스트 {@code anchor = null}, 4 수용심사 결정 2). 패키지는 다시 만들지 않으므로 이후의
     * 증명은 영수증 내보내기가 잇는다.
     */
    public record AnchorRef(long anchorSeq, LocalDate anchorDate, String leafHash, long sealChainSeq, long auditSeq) {
        public AnchorRef {
            Objects.requireNonNull(anchorDate, "anchorDate");
            Objects.requireNonNull(leafHash, "leafHash");
        }
    }

    /** 고정 버전과 그 번들 해시(TENANT 룰이 없으면 둘 다 null). */
    public record Pinned(String ruleVersionId, String ruleBundleHash, String tenantRuleVersionId, String tenantRuleBundleHash, String templateId,
                         int templateVersion, String templateBundleHash) {
        public Pinned {
            if ((tenantRuleVersionId == null) != (tenantRuleBundleHash == null)) {
                throw new IllegalArgumentException("tenant rule id and bundle hash come together");
            }
        }
    }

    /** 엔진 스냅샷 헤더 복사본. */
    public record Snapshot(String snapshotId, String gradingPolicyVersionId, String rankingPolicyVersionId, String tieBreak, Instant generatedAt) {
    }

    /** 본인확인 결과 1건(입력값 없음). */
    public record IdentityCheckEntry(IdentityMethod type, boolean passed, Instant at) {
    }

    /** 서명 증거 객체의 해시(원본은 패키지에 넣지 않는다). */
    public record EvidenceObject(String kind, String sha256, String cipherSha256, long bytes) {
    }

    /**
     * 서명 1건. 고객이 아니면 {@code sessionId}가 없다.
     *
     * @param viewEvidence      세션 열람 증거(없으면 null)
     * @param acknowledgedFlags 관리자가 사유를 확인한 플래그 ID
     * @param scanMatch         종이 스캔 대조 기록(없으면 null)
     * @param device            기기 정보(없으면 null)
     * @param ip                IP(없으면 null)
     */
    public record SignatureRecord(String signatureId, SignerRole role, SignatureChannel channel, SignatureMethod method, Instant signedAt,
                                  String signedDocHash, String signedPdfHash, String sessionId, List<IdentityCheckEntry> identityCheck,
                                  JsonNode viewEvidence, List<String> acknowledgedFlags, JsonNode scanMatch, JsonNode device, String ip,
                                  List<EvidenceObject> evidence) {
        public SignatureRecord {
            Objects.requireNonNull(signatureId, "signatureId");
            Objects.requireNonNull(role, "role");
            identityCheck = List.copyOf(identityCheck);
            acknowledgedFlags = List.copyOf(acknowledgedFlags);
            evidence = List.copyOf(evidence);
        }
    }

    /** 감사 행 1건: 저장된 행의 JSON 표현(필드명은 감사 계약 그대로)과 그 {@code entry_hash}. */
    public record AuditRow(long seq, JsonNode row, String entryHash) {
        public AuditRow {
            Objects.requireNonNull(row, "row");
            Objects.requireNonNull(entryHash, "entryHash");
        }
    }
}
