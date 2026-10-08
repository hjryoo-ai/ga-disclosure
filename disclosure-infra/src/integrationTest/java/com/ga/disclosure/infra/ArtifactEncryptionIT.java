package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.domain.enums.ArtifactKind;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.workflow.artifact.ArtifactRecord;
import com.ga.disclosure.workflow.artifact.ArtifactUnreadableException;
import com.ga.disclosure.workflow.artifact.DocumentCryptoPort;
import com.ga.disclosure.workflow.disclosure.ArtifactService;
import com.ga.disclosure.workflow.disclosure.SealService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 3B S8: 산출물은 문서별 키로 암호화돼 저장된다 — 버킷에서 직접 받은 바이트에 평문 SHA-256(hex·바이트)·고객 성명(UTF-8)이 없고 형식 머리는
 * 0x01, 객체 키에도 평문 해시가 없다(승인 Q4). 복호화 후 {@code sha256} 일치. AAD 교차(다른 종류·다른 확인서로 옮김)는 태그 검증 실패.
 * 문서 키를 파기하면({@code ga_shred_document_key}, 소유 롤) 어떤 사본도 읽을 수 없다 — 열람은 거부되고 그 사실이 감사된다.
 */
class ArtifactEncryptionIT {

    private final SealSetup s = new SealSetup();

    @AfterEach
    void close() {
        s.close();
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    @Test
    void storedBytesAreCiphertextBoundToTheirContext() {
        SealService.Outcome o = s.sealReasoned();
        DisclosureId id = o.id();
        List<ArtifactRecord> artifacts = s.artifactsOf(id);
        assertThat(artifacts).extracting(ArtifactRecord::kind).containsExactlyInAnyOrder(ArtifactKind.CANONICAL_JSON, ArtifactKind.PDF);
        byte[] name = "가상고객".getBytes(StandardCharsets.UTF_8);
        for (ArtifactRecord a : artifacts) {
            byte[] stored = s.bucket.get(a.storageKey());
            assertThat(stored[0]).isEqualTo((byte) 0x01);
            assertThat(stored).hasSize((int) a.cipherBytes());
            assertThat(a.cipherBytes()).isEqualTo(a.bytes() + 29);
            assertThat(contains(stored, a.sha256().hex().getBytes(StandardCharsets.US_ASCII))).as("평문 해시 hex").isFalse();
            assertThat(contains(stored, HexFormat.of().parseHex(a.sha256().hex()))).as("평문 해시 바이트").isFalse();
            assertThat(contains(stored, name)).as("성명 UTF-8").isFalse();
            assertThat(a.storageKey()).doesNotContain(a.sha256().hex()).endsWith(a.cipherSha256().hex());
            assertThat(com.ga.platform.canonical.Sha256.of(stored)).isEqualTo(a.cipherSha256().hex());
        }
        // 복호화 → 평문 해시 일치(PDF에는 성명이 인쇄돼 있다 — 암호문에만 없어야 한다)
        ArtifactService.View pdf = s.artifacts.view(Callers.of(s.w.tenant, SealSetup.MANAGER), id, ArtifactKind.PDF);
        assertThat(pdf).isInstanceOf(ArtifactService.View.Granted.class);
        byte[] plain = ((ArtifactService.View.Granted) pdf).plaintext();
        assertThat(com.ga.platform.canonical.Sha256.of(plain)).isEqualTo(s.text(
                "SELECT pdf_hash FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", s.w.tenant.value(), id.value()));

        // AAD 교차: 같은 키로도 다른 종류·다른 확인서로 옮긴 암호문은 풀리지 않는다
        DocumentCryptoPort.StoredKey key = s.w.in(() -> s.records.liveKey(id).orElseThrow());
        ArtifactRecord pdfRecord = artifacts.stream().filter(a -> a.kind() == ArtifactKind.PDF).findFirst().orElseThrow();
        byte[] cipher = s.bucket.get(pdfRecord.storageKey());
        assertThat(s.cipher.open(s.w.tenant, id, key, ArtifactKind.PDF, cipher)).isEqualTo(plain);
        assertThatThrownBy(() -> s.cipher.open(s.w.tenant, id, key, ArtifactKind.CANONICAL_JSON, cipher))
                .isInstanceOf(ArtifactUnreadableException.class);
        assertThatThrownBy(() -> s.cipher.open(s.w.tenant, DisclosureId.of(UUID.randomUUID()), key, ArtifactKind.PDF, cipher))
                .isInstanceOf(ArtifactUnreadableException.class);
    }

    @Test
    void shreddingTheDocumentKeyMakesEveryCopyUnreadable() {
        SealService.Outcome o = s.sealReasoned();
        DisclosureId id = o.id();
        assertThat(s.artifacts.view(Callers.of(s.w.tenant, SealSetup.MANAGER), id, ArtifactKind.CANONICAL_JSON)).isInstanceOf(ArtifactService.View.Granted.class);
        // 키 파기의 효과(V9: 정의자 롤 + 함수 표식만 — 판정 조건을 거치는 함수 경로는 DestroyerRoleIT)
        s.w.db.seed(s.w.tenant.value(), c -> com.ga.disclosure.infra.testing.SeedData.shredDocumentKey(c, s.w.tenant.value(), id.value()));
        ArtifactService.View denied = s.artifacts.view(Callers.of(s.w.tenant, SealSetup.MANAGER), id, ArtifactKind.PDF);
        assertThat(denied).isEqualTo(new ArtifactService.View.Denied(ArtifactService.View.Reason.KEY_SHREDDED));
        assertThat(s.audit()).anyMatch(r -> r.entry().action() == AuditAction.ARTIFACT_VIEW_DENIED
                && r.entry().detail().path("reason").asString().equals("KEY_SHREDDED"));
        // 버킷에 사본이 남아 있어도(잠금) 감싼 키가 없으니 어떤 경로로도 풀 수 없다
        assertThat(s.w.in(() -> s.records.liveKey(id))).isEmpty();
        assertThat(s.objects()).isEqualTo(2);
    }

    @Test
    void viewDeniesAnObjectThatDoesNotOpenWithTheRecordedKey() {
        SealService.Outcome o = s.sealReasoned();
        DisclosureId id = o.id();
        ArtifactRecord canonical = s.artifactsOf(id).stream().filter(a -> a.kind() == ArtifactKind.CANONICAL_JSON).findFirst().orElseThrow();
        // 같은 객체 키에 다른 문서 키로 만든 암호문을 새 버전으로 올린다(잠긴 원본 버전은 남는다) — 기록된 키로는 풀리지 않는다
        byte[] other = s.cipher.seal(s.w.tenant, id, java.util.Map.of(ArtifactKind.CANONICAL_JSON, "{}".getBytes(StandardCharsets.UTF_8)))
                .ciphertext(ArtifactKind.CANONICAL_JSON);
        s.bucket.put(canonical.storageKey(), other);
        assertThat(s.artifacts.view(Callers.of(s.w.tenant, SealSetup.MANAGER), id, ArtifactKind.CANONICAL_JSON))
                .isEqualTo(new ArtifactService.View.Denied(ArtifactService.View.Reason.UNREADABLE));
        assertThat(s.audit()).anyMatch(r -> r.entry().action() == AuditAction.ARTIFACT_VIEW_DENIED
                && r.entry().detail().path("reason").asString().equals("UNREADABLE"));
    }

    @Test
    void viewDeniesPlaintextThatDoesNotMatchTheRecordedHash() {
        SealService.Outcome o = s.sealReasoned();
        DisclosureId id = o.id();
        // 복호화는 되지만 평문이 기록된 sha256과 다르다(같은 키·AAD로 다시 만든 바이트를 흉내 낸다) — 열람은 거부되고 감사된다
        DocumentCryptoPort altering = new DocumentCryptoPort() {
            @Override
            public Sealed seal(com.ga.platform.core.tenant.TenantId tenant, DisclosureId disclosure, java.util.Map<ArtifactKind, byte[]> plaintexts) {
                return s.cipher.seal(tenant, disclosure, plaintexts);
            }

            @Override
            public byte[] open(com.ga.platform.core.tenant.TenantId tenant, DisclosureId disclosure, StoredKey key, ArtifactKind kind, byte[] ciphertext) {
                byte[] plain = s.cipher.open(tenant, disclosure, key, kind, ciphertext);
                plain[plain.length - 1] ^= 0x01;
                return plain;
            }

            @Override
            public byte[] encrypt(com.ga.platform.core.tenant.TenantId tenant, DisclosureId disclosure, StoredKey key, ArtifactKind kind,
                                  byte[] plaintext) {
                return s.cipher.encrypt(tenant, disclosure, key, kind, plaintext);
            }

            @Override
            public byte[] encryptEvidence(com.ga.platform.core.tenant.TenantId tenant, DisclosureId disclosure, StoredKey key, UUID signatureId,
                                          com.ga.disclosure.domain.enums.SignatureEvidenceKind kind, byte[] plaintext) {
                return s.cipher.encryptEvidence(tenant, disclosure, key, signatureId, kind, plaintext);
            }

            @Override
            public byte[] openEvidence(com.ga.platform.core.tenant.TenantId tenant, DisclosureId disclosure, StoredKey key, UUID signatureId,
                                       com.ga.disclosure.domain.enums.SignatureEvidenceKind kind, byte[] ciphertext) {
                return s.cipher.openEvidence(tenant, disclosure, key, signatureId, kind, ciphertext);
            }
        };
        ArtifactService view = new ArtifactService(s.records, altering, s.store, s.w.audit, s.w.tx, s.w.clock, SealService.DEFAULT_TRANSACTION_TIMEOUT, Callers.authz(s.w.clock));
        assertThat(view.view(Callers.of(s.w.tenant, SealSetup.MANAGER), id, ArtifactKind.PDF))
                .isEqualTo(new ArtifactService.View.Denied(ArtifactService.View.Reason.HASH_MISMATCH));
        assertThat(s.audit()).anyMatch(r -> r.entry().action() == AuditAction.ARTIFACT_VIEW_DENIED
                && r.entry().detail().path("reason").asString().equals("HASH_MISMATCH"));
        assertThat(s.audit()).noneMatch(r -> r.entry().action() == AuditAction.ARTIFACT_VIEW);
    }
}
