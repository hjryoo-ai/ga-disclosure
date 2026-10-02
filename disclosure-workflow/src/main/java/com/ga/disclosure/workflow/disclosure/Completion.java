package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.audit.AuditChain;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.audit.AuditRecord;
import com.ga.disclosure.domain.enums.ArtifactKind;
import com.ga.disclosure.domain.enums.RetentionAnchor;
import com.ga.disclosure.domain.enums.SignatureEvidenceKind;
import com.ga.disclosure.domain.enums.SignatureMethod;
import com.ga.disclosure.domain.grade.EngineSnapshot;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.Sha256;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.rules.template.TemplateResolver;
import com.ga.disclosure.seal.canonical.CanonicalDocument;
import com.ga.disclosure.seal.evidence.EvidenceInput;
import com.ga.disclosure.seal.evidence.EvidencePackage;
import com.ga.disclosure.seal.evidence.EvidencePackageBuilder;
import com.ga.disclosure.seal.renderer.DisclosurePdfRenderer;
import com.ga.disclosure.seal.renderer.SignatureAppearance;
import com.ga.disclosure.seal.renderer.SignedPdfAppender;
import com.ga.disclosure.sign.retention.RetentionAnchors;
import com.ga.disclosure.workflow.artifact.ArtifactRecord;
import com.ga.disclosure.workflow.artifact.ArtifactStore;
import com.ga.disclosure.workflow.artifact.DocumentCryptoPort;
import com.ga.disclosure.workflow.artifact.DocumentRecordStore;
import com.ga.disclosure.workflow.artifact.SignatureEvidenceRecord;
import com.ga.disclosure.workflow.disclosure.DisclosureLoader.Loaded;
import com.ga.disclosure.workflow.sign.IdentityResult;
import com.ga.disclosure.workflow.sign.StoredSignature;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 완료 산출물(4 계획 §7.3, 설계서 §6.5): 봉인 원본(canonical·PDF)을 문서 키로 풀어 해시를 확인하고, 서명본 PDF(원본 + 증분 갱신 서명 외관 페이지)와
 * 증거 패키지(매니페스트·감사 발췌·원본·서명본·서명 레코드 요약 — 스트로크·이미지·스캔 원본은 해시만)를 만들어 <b>봉인 때의 문서 키</b>로 암호화해
 * 올리고({@code put}, 잠금 없음) 산출물로 기록한다. 보존기한은 앵커 일반식으로 다시 계산한다(연장만). 완료 트랜잭션 안에서 부르며, 감사 발췌는 이
 * 호출 시점까지의 그 확인서 대상 행이다 — 완료 감사 행은 이 뒤에 남으므로 발췌의 경계({@code toSeq})가 완료 직전이 된다.
 */
final class Completion {

    record Built(CompletionStamp stamp, List<ArtifactRecord> artifacts, String signedPdfSha256, String evidenceSha256, String manifestSha256,
                 long auditFromSeq, long auditToSeq) {
        Built {
            artifacts = List.copyOf(artifacts);
        }
    }

    private final StoredArtifacts stored;
    private final DocumentRecordStore records;
    private final DocumentCryptoPort crypto;
    private final ArtifactStore storage;
    private final AuditPort audit;
    private final RuleResolver rules;
    private final TemplateResolver templates;
    private final SignedPdfAppender appender;

    Completion(StoredArtifacts stored, DocumentRecordStore records, DocumentCryptoPort crypto, ArtifactStore storage, AuditPort audit,
               RuleResolver rules, TemplateResolver templates, SignedPdfAppender appender) {
        this.stored = Objects.requireNonNull(stored, "stored");
        this.records = Objects.requireNonNull(records, "records");
        this.crypto = Objects.requireNonNull(crypto, "crypto");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.templates = Objects.requireNonNull(templates, "templates");
        this.appender = Objects.requireNonNull(appender, "appender");
    }

    /**
     * @param signatures 이 확인서의 서명 전부(서명 시각 순, 이번 서명 포함 — 이미 저장돼 있다)
     * @param freshImages 이번 트랜잭션에서 받은 서명 이미지(서명 ID → PNG). 나머지는 저장소에서 풀어 쓴다
     */
    Built build(TenantId tenant, Loaded l, List<StoredSignature> signatures, Map<UUID, byte[]> freshImages, Instant now) {
        Disclosure d = l.disclosure();
        DisclosureId id = d.id();
        SealStamp seal = d.sealStamp().orElseThrow(() -> new IllegalStateException(id + " is not sealed"));
        StoredArtifacts.Read<ArtifactRecord> canonical = stored.artifact(tenant, id, ArtifactKind.CANONICAL_JSON);
        StoredArtifacts.Read<ArtifactRecord> pdf = stored.artifact(tenant, id, ArtifactKind.PDF);
        if (!canonical.record().sha256().equals(seal.canonicalHash()) || !pdf.record().sha256().equals(seal.pdfHash())) {
            throw new IllegalStateException("stored originals of " + id + " do not match the sealed hashes");
        }
        List<SignatureEvidenceRecord> evidence = records.evidence(id);

        List<SignatureAppearance> appearances = new ArrayList<>();
        for (StoredSignature s : signatures) {
            byte[] image = null;
            if (s.method() == SignatureMethod.DRAWN) {
                image = freshImages.get(s.signatureId());
                if (image == null) {
                    SignatureEvidenceRecord e = evidence.stream()
                            .filter(r -> r.signatureId().equals(s.signatureId()) && r.kind() == SignatureEvidenceKind.IMAGE).findFirst()
                            .orElseThrow(() -> new IllegalStateException("drawn signature " + s.signatureId() + " has no image"));
                    image = stored.evidence(tenant, e);
                }
            }
            appearances.add(new SignatureAppearance(s.role(), s.channel(), s.method(), s.signedAt(),
                    s.identityCheck().stream().map(r -> new SignatureAppearance.IdentityResult(r.method(), r.passed())).toList(), image));
        }
        CanonicalDocument document = CanonicalDocument.parse(canonical.plaintext());
        DisclosurePdfRenderer.Rendered signedPdf = appender.append(pdf.plaintext(), document, l.template(), seal.number().value(), appearances);

        Map<RetentionAnchor, LocalDate> anchors = new EnumMap<>(RetentionAnchor.class);
        anchors.put(RetentionAnchor.SEAL, seal.sealedAt().atZone(SealService.SEOUL).toLocalDate());
        anchors.put(RetentionAnchor.COMPLETION, now.atZone(SealService.SEOUL).toLocalDate());
        LocalDate retentionUntil = RetentionAnchors.until(seal.retentionUntil(), l.rule().retentionAnchors(), anchors, l.rule().retentionYears());

        List<EvidenceInput.AuditRow> auditRows = new ArrayList<>();
        for (AuditRecord r : audit.readTarget(CommandRunner.TARGET, id.toString())) {
            ObjectNode row = (ObjectNode) Canonicalizer.parseStrict(new String(AuditChain.canonicalEntry(tenant, r.seq(), r.entry()),
                    java.nio.charset.StandardCharsets.UTF_8));
            row.put("prevHash", r.prevHash()).put("entryHash", r.entryHash());
            auditRows.add(new EvidenceInput.AuditRow(r.seq(), row, r.entryHash()));
        }
        EngineSnapshot snapshot = d.engineSnapshot().orElseThrow();
        String tenantRule = d.tenantRuleVersionId().map(v -> v.value()).orElse(null);
        EvidenceInput input = new EvidenceInput(tenant.value(), id.toString(), seal.number().value(), d.lineage().version(), canonical.plaintext(),
                pdf.plaintext(), signedPdf.pdf(), seal.chainHash().toString(), seal.chainSeq(),
                new EvidenceInput.Pinned(d.ruleVersionId().value(), rules.contentHash(tenant, d.ruleVersionId()), tenantRule,
                        d.tenantRuleVersionId().map(v -> rules.contentHash(tenant, v)).orElse(null), d.template().templateId(),
                        d.template().version(), templates.contentHash(tenant, d.template())),
                new EvidenceInput.Snapshot(snapshot.snapshot().snapshotId().value(), snapshot.snapshot().gradingPolicyVersionId(),
                        snapshot.snapshot().rankingPolicyVersionId(), snapshot.snapshot().tieBreak().name(), snapshot.generatedAt()),
                seal.sealedAt(), now, retentionUntil, signatures.stream().map(s -> record(s, evidence)).toList(), auditRows);
        EvidencePackage pkg = EvidencePackageBuilder.build(input);

        DocumentCryptoPort.StoredKey key = stored.liveKey(id);
        Map<ArtifactKind, byte[]> outputs = new LinkedHashMap<>();
        outputs.put(ArtifactKind.SIGNED_PDF, signedPdf.pdf());
        outputs.put(ArtifactKind.EVIDENCE_ZIP, pkg.zip());
        List<ArtifactRecord> written = new ArrayList<>();
        for (Map.Entry<ArtifactKind, byte[]> e : outputs.entrySet()) {
            byte[] cipher = crypto.encrypt(tenant, id, key, e.getKey(), e.getValue());
            Sha256 cipherHash = Sha256.of(com.ga.platform.canonical.Sha256.of(cipher));
            String storageKey = ArtifactRecord.storageKey(tenant.value(), id, e.getKey(), cipherHash);
            storage.put(storageKey, cipher);
            ArtifactRecord record = new ArtifactRecord(id, e.getKey(), storageKey, Sha256.of(com.ga.platform.canonical.Sha256.of(e.getValue())),
                    e.getValue().length, cipherHash, cipher.length, key.keyId(), now, null);
            records.insertArtifact(record);
            written.add(record);
        }
        return new Built(new CompletionStamp(now, retentionUntil), written, signedPdf.sha256(), pkg.sha256(), pkg.manifestSha256(),
                auditRows.getFirst().seq(), auditRows.getLast().seq());
    }

    /** 증거 패키지의 서명 레코드 요약(입력값·이미지 없음, 증거 객체는 해시만). */
    private static EvidenceInput.SignatureRecord record(StoredSignature s, List<SignatureEvidenceRecord> evidence) {
        List<EvidenceInput.EvidenceObject> objects = evidence.stream().filter(e -> e.signatureId().equals(s.signatureId()))
                .sorted(java.util.Comparator.comparing(SignatureEvidenceRecord::kindName))
                .map(e -> new EvidenceInput.EvidenceObject(e.kind().name(), e.sha256().hex(), e.cipherSha256().hex(), e.bytes())).toList();
        return new EvidenceInput.SignatureRecord(s.signatureId().toString(), s.role(), s.channel(), s.method(), s.signedAt(), s.signedDocHash().hex(),
                s.signedPdfHash().hex(), s.sessionIdOrNull() == null ? null : s.sessionIdOrNull().toString(),
                s.identityCheck().stream().map(Completion::entry).toList(), s.viewOrNull() == null ? null : s.viewOrNull().toJson(),
                s.acknowledgedFlags().stream().map(UUID::toString).toList(), s.scanMatchOrNull() == null ? null : s.scanMatchOrNull().toJson(),
                s.deviceOrNull() == null ? null : s.deviceOrNull().toJson(), s.ipOrNull(), objects);
    }

    private static EvidenceInput.IdentityCheckEntry entry(IdentityResult r) {
        return new EvidenceInput.IdentityCheckEntry(r.method(), r.passed(), r.at());
    }
}
