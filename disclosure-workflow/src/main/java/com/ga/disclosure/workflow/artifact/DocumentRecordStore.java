package com.ga.disclosure.workflow.artifact;

import com.ga.disclosure.domain.enums.ArtifactKind;
import com.ga.disclosure.domain.vo.DisclosureId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 문서 키·산출물 기록 포트(V7 {@code document_key}·{@code document_artifact}, 바인딩된 테넌트의 트랜잭션 안). 키·산출물은 봉인된 확인서에만
 * 생기고(GD092·GD093) 지워지지 않는다. 키 파기는 이 포트에 없다(Phase 5 파기 배치 전용 함수).
 */
public interface DocumentRecordStore {

    void insertKey(DisclosureId disclosure, DocumentCryptoPort.StoredKey key, Instant createdAt);

    void insertArtifact(ArtifactRecord record);

    List<ArtifactRecord> artifacts(DisclosureId disclosure);

    /** 감싼 키가 남아 있는 문서 키. 파기됐으면 {@link KeyLookup.Shredded}, 없으면 {@link KeyLookup.Missing}. */
    KeyLookup key(DisclosureId disclosure);

    /** 서명 증거 객체 기록(V8 GD105 — 서명과 같은 확인서, 살아 있는 문서 키, 잠금 전). */
    void insertEvidence(SignatureEvidenceRecord record);

    List<SignatureEvidenceRecord> evidence(DisclosureId disclosure);

    /**
     * Object Lock 적용 기록(산출물·서명 증거 공통): 첫 적용 시각(NULL → 값 1회)과 적용 기한(증가만, V8 GD093·GD105). 이미 그 기한 이상으로 기록돼
     * 있으면 false.
     */
    boolean markRetentionApplied(LockedObject object, Instant at, LocalDate until);

    /**
     * 커밋됐지만 잠금이 지금 보존기한까지 걸렸다고 기록되지 않은 객체(재적용 대상 — 미적용·연장 뒤 미적용, 산출물과 서명 증거 모두)와 그 확인서의
     * 보존기한.
     */
    List<Unretained> unretained(int limit);

    /** 그 객체 키를 가리키는 기록(산출물·서명 증거·작업 보고서 — 6A)이 있는가(잔여물 정리 판정). */
    boolean referenced(String storageKey);

    record Unretained(LockedObject record, LocalDate retentionUntil) {
    }

    sealed interface KeyLookup {
        record Live(DocumentCryptoPort.StoredKey key) implements KeyLookup {
        }

        record Shredded(String keyId, Instant shreddedAt) implements KeyLookup {
        }

        record Missing() implements KeyLookup {
        }
    }

    default Optional<DocumentCryptoPort.StoredKey> liveKey(DisclosureId disclosure) {
        return key(disclosure) instanceof KeyLookup.Live live ? Optional.of(live.key()) : Optional.empty();
    }
}
