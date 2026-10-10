package com.ga.disclosure.workflow.kek;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 재래핑 대상 행(감싼 DEK가 있는 표 셋)과 DB 재래핑 함수({@code ga_kek_rewrap}, V21). 바인딩된 테넌트만. */
public interface KekRewrapStore {

    /** 감싼 DEK를 가진 표. {@code dbName}은 재래핑 함수의 대상 이름이다. */
    enum Target {
        DOCUMENT_KEY("document_key"), CUSTOMER_DATA_KEY("customer_data_key"), JOB_REPORT("async_job");

        private final String dbName;

        Target(String dbName) {
            this.dbName = dbName;
        }

        public String dbName() {
            return dbName;
        }
    }

    /**
     * 살아 있는(파기되지 않은) 감싼 키 하나. {@code rowKey}는 그 표의 행 식별자(키 ID 또는 작업 ID), {@code keyId}는 감싸기 AAD의 키 ID(보고서는
     * {@code RPT-{jobId}}).
     */
    record Wrapped(Target target, String rowKey, String keyId, String kekId, byte[] wrapped) {
        public Wrapped {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(rowKey, "rowKey");
            Objects.requireNonNull(keyId, "keyId");
            Objects.requireNonNull(kekId, "kekId");
            Objects.requireNonNull(wrapped, "wrapped");
        }
    }

    /** {@code kekId}가 아닌 KEK로 감싼 살아 있는 행, 행 식별자 순으로 {@code after} 다음부터 {@code limit}개. */
    List<Wrapped> notUnder(Target target, String kekId, Optional<String> after, int limit);

    /** 한 행을 옮긴다(DB 함수). 이미 옮겨졌거나 그 사이 파기됐으면 false. */
    boolean rewrap(Target target, String rowKey, String fromKekId, String toKekId, byte[] wrapped);
}
