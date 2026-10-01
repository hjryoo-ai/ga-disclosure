package com.ga.disclosure.infra;

import com.ga.disclosure.domain.enums.ArtifactKind;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.workflow.artifact.ArtifactRecord;
import com.ga.disclosure.workflow.artifact.ArtifactStore;
import com.ga.disclosure.workflow.artifact.DocumentCryptoPort;
import com.ga.disclosure.workflow.artifact.DocumentRecordStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** 3B S9 실패 주입: 저장소·기록 포트를 감싸 특정 지점에서 실패시키거나 관측한다. */
final class FailingPorts {

    private FailingPorts() {
    }

    static final class InjectedFailure extends RuntimeException {
        InjectedFailure(String point) {
            super("injected failure at " + point);
        }
    }

    /** 저장소: {@code failRetention}이면 Object Lock 적용이 실패한다(커밋 후 지점). 올린 키를 기억한다. */
    static final class Store implements ArtifactStore {
        final ArtifactStore delegate;
        final AtomicBoolean failRetention = new AtomicBoolean();
        final List<String> uploaded = new ArrayList<>();
        /** Object Lock 적용 호출마다 그때 DB 트랜잭션이 열려 있었는가(커밋 전 잠금 금지 — 언제나 false여야 한다). */
        final List<Boolean> retentionInsideTransaction = new ArrayList<>();

        Store(ArtifactStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void put(String key, byte[] bytes) {
            delegate.put(key, bytes);
            uploaded.add(key);
        }

        @Override
        public byte[] get(String key) {
            return delegate.get(key);
        }

        @Override
        public boolean exists(String key) {
            return delegate.exists(key);
        }

        @Override
        public void applyRetention(String key, Instant until) {
            retentionInsideTransaction.add(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            if (failRetention.get()) {
                throw new InjectedFailure("applyRetention");
            }
            delegate.applyRetention(key, until);
        }

        @Override
        public Optional<Instant> retention(String key) {
            return delegate.retention(key);
        }

        @Override
        public List<StoredObject> list(String prefix) {
            return delegate.list(prefix);
        }

        @Override
        public void delete(String key) {
            delegate.delete(key);
        }
    }

    /**
     * 기록 포트: {@code failOnArtifact}이면 산출물 기록에서 실패한다(업로드 뒤·커밋 전 지점 — 트랜잭션이 롤백된다). 산출물 기록 순간(커밋 전)에 그
     * 객체에 잠금이 있었는지를 관측한다.
     */
    static final class Records implements DocumentRecordStore {
        final DocumentRecordStore delegate;
        final ArtifactStore store;
        final AtomicBoolean failOnArtifact = new AtomicBoolean();
        final List<Optional<Instant>> retentionBeforeCommit = new ArrayList<>();

        Records(DocumentRecordStore delegate, ArtifactStore store) {
            this.delegate = delegate;
            this.store = store;
        }

        @Override
        public void insertKey(DisclosureId disclosure, DocumentCryptoPort.StoredKey key, Instant createdAt) {
            delegate.insertKey(disclosure, key, createdAt);
        }

        @Override
        public void insertArtifact(ArtifactRecord record) {
            retentionBeforeCommit.add(store.retention(record.storageKey()));
            if (failOnArtifact.get()) {
                throw new InjectedFailure("insertArtifact (before commit)");
            }
            delegate.insertArtifact(record);
        }

        @Override
        public List<ArtifactRecord> artifacts(DisclosureId disclosure) {
            return delegate.artifacts(disclosure);
        }

        @Override
        public KeyLookup key(DisclosureId disclosure) {
            return delegate.key(disclosure);
        }

        @Override
        public boolean markRetentionApplied(DisclosureId disclosure, ArtifactKind kind, Instant at) {
            return delegate.markRetentionApplied(disclosure, kind, at);
        }

        @Override
        public List<Unretained> unretained(int limit) {
            return delegate.unretained(limit);
        }

        @Override
        public boolean referenced(String storageKey) {
            return delegate.referenced(storageKey);
        }
    }
}
