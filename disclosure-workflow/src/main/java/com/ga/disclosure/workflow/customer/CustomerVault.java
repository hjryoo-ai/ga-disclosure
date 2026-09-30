package com.ga.disclosure.workflow.customer;

import com.ga.disclosure.domain.vo.CustomerRef;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 고객 참조 저장소 포트(infra 어댑터가 암호화·키 관리·영속을 함께 구현). 바인딩된 테넌트의 트랜잭션 안에서 호출된다.
 * 평문은 이 포트 경계를 {@code Sensitive}로만 넘는다. 암호화 방식(AES-256-GCM·AAD·DEK←KEK)은 설계서 §9.
 */
public interface CustomerVault {

    /** 테넌트의 ACTIVE 데이터 키로 암호화해 저장한다(키가 없으면 새로 만든다). 사용한 키 ID를 돌려준다. */
    String insert(CustomerRef ref, NewCustomer customer, Instant createdAt);

    Optional<Customer> find(CustomerRef ref);

    /**
     * 등록 멱등 키로 등록한다(3A 계획 Q7): 같은 키의 고객이 이미 있으면 새 행을 만들지 않고 그 참조를 돌려준다({@code created=false}).
     * 동시 등록도 한 행만 남는다(부분 유일 인덱스 + {@code ON CONFLICT DO NOTHING} 후 재조회).
     */
    KeyedInsert insertKeyed(CustomerRef ref, NewCustomer customer, Instant createdAt, RegistrationKey key);

    record KeyedInsert(CustomerRef ref, boolean created, String keyIdOrNull) {
    }

    /** 등록 멱등 키로 찾는다(복호화 없음). */
    Optional<CustomerRef> findByRegistrationKey(RegistrationKey key);

    /** 복호화 없이 존재만 본다(확인서 초안이 고객 참조를 가리킬 때, Phase 3A). */
    boolean exists(CustomerRef ref);

    /** 활성 키를 RETIRED로, 새 키를 ACTIVE로(같은 트랜잭션). 활성 키가 없었으면 새 키만. */
    KeyRotation rotate(Instant at);

    /** 활성 키가 아닌 키로 암호화된 행을 최대 {@code limit}개 재암호화하고 그 수를 돌려준다. */
    int reencryptBatch(int limit);

    /** 쓰는 행이 없는 RETIRED 키의 키 재료를 파기(DESTROYED)하고 파기한 키 ID를 돌려준다. */
    List<String> destroyUnusedRetiredKeys(Instant at);

    record KeyRotation(Optional<String> retiredKeyId, String activeKeyId) {
    }
}
