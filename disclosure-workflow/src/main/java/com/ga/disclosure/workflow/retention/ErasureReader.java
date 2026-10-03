package com.ga.disclosure.workflow.retention;

import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.domain.vo.DisclosureId;

import java.util.List;
import java.util.Objects;

/**
 * 파기 직전에 지울 값을 읽어 감사 표현으로 바꾼다(5 계획 §5.5, 승인 Q3 — infra가 구현). 평문은 이 포트 밖으로 나오지 않는다: 자유 텍스트·외부 식별자는
 * {@code sha256-utf8}, JSON 행동 기록은 {@code sha256-jcs}, 암호문·감싼 키는 {@code sha256-stored}(저장된 바이트), 기기는 {@code presence},
 * IP는 {@code presence-family}(4|6 — 해시가 값 자체가 되는 작은 정의역). NULL인 값은 목록에 없다.
 */
public interface ErasureReader {

    /** {@code document_key.wrapped_dek}(살아 있을 때만). */
    List<Erased> documentKey(DisclosureId disclosure);

    /** {@code ga_disclosure_destroy}가 NULL로 바꿀 컬럼 전부(확인서·추천·검토·서명·세션·플래그). */
    List<Erased> disclosure(DisclosureId disclosure);

    /** {@code ga_customer_ref_destroy}가 NULL로 바꿀 컬럼 전부. */
    List<Erased> customer(CustomerRef customer);

    /**
     * @param row  행 식별(번호·UUID 등 개인정보 아닌 키)
     * @param repr 표현 종류({@code pii-columns} 블록의 {@code auditRepr})
     * @param value 해시 hex, 또는 {@code present}·{@code 4}·{@code 6}
     */
    record Erased(String table, String column, String row, String repr, String value) {
        public Erased {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(row, "row");
            Objects.requireNonNull(repr, "repr");
            Objects.requireNonNull(value, "value");
        }
    }
}
