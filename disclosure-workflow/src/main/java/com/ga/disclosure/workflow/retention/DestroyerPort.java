package com.ga.disclosure.workflow.retention;

import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.domain.vo.DisclosureId;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 파기 함수 호출 포트(5 계획 §5.4, 승인 Q2): 호출자의 테넌트 트랜잭션 안에서 파기자 롤로 전환해 V9 함수를 부르고 롤을 되돌린다. 함수는 바인딩된 테넌트·
 * 판정 조건을 다시 단언하고 어긋나면 {@link DestructionRefusedException}(GD114) — 그 트랜잭션의 감사·아웃박스도 함께 롤백된다.
 */
public interface DestroyerPort {

    /** 문서 데이터 키 파기({@code ga_document_key_shred}) — 파기한 키 ID. */
    String shredDocumentKey(DisclosureId disclosure, LocalDate asOf, Instant at, String by);

    /** 확인서 지정 컬럼 NULL·묘비({@code ga_disclosure_destroy}) — 키가 먼저 파기돼 있어야 한다. */
    void destroyDisclosure(DisclosureId disclosure, LocalDate asOf, Instant at, String by);

    /** 고객 참조 지정 컬럼 NULL({@code ga_customer_ref_destroy}) — 살아 있는 확인서·보류가 없어야 한다. */
    void destroyCustomerRef(CustomerRef customer, Instant at, String by);
}
