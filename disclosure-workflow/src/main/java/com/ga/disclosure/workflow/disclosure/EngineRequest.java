package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.vo.GroupCode;
import com.ga.disclosure.domain.vo.ProductKey;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.canonical.Sha256;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 엔진에 보낼 산출 요청(설계서 §4.1): 기준일 = 상담일, 상품군, 임시등록을 제외한 항목의 상품키(항목 순서). 임시등록은 엔진 도메인
 * 밖이라 보내지 않는다(Phase 2 심사 §3-5).
 *
 * <p>{@link #fingerprint()}는 요청의 정체성이다: 엔진 호출은 트랜잭션 밖에서 하므로(3A 계획 Q6) 쓰기 트랜잭션이 행을 잠근 뒤
 * 지금 확인서의 요청 지문과 비교해 다르면 {@code GRADE_STALE}로 거부한다. 상품키 순서와 무관한 집합이다(순위는 엔진이 매긴다).
 */
public record EngineRequest(LocalDate asOf, GroupCode group, List<ProductKey> products) {

    public EngineRequest {
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(group, "group");
        products = List.copyOf(products);
        if (products.isEmpty()) {
            throw new IllegalArgumentException("nothing to grade: every item is a temp product");
        }
        if (products.stream().distinct().count() != products.size()) {
            throw new IllegalArgumentException("duplicate product key in the request");
        }
    }

    public String fingerprint() {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("asOf", asOf.toString());
        n.put("group", group.value());
        ArrayNode keys = n.putArray("products");
        products.stream().map(ProductKey::value).sorted().forEach(keys::add);
        return Sha256.of(Canonicalizer.canonicalize(n));
    }
}
