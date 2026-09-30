package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.vo.InsurerCode;
import com.ga.disclosure.domain.vo.ProductKey;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Objects;

/**
 * 항목 교체 명령의 입력 1건. 카탈로그 상품은 상품키만 받고 상품명·기본값은 상담일 기준 카탈로그에서 채운다(설계사가 카탈로그 값을 덮지
 * 못한다). 임시등록 상품은 카탈로그 밖이므로 상품명·발행번호·항목값을 설계사가 입력한다. {@code agentValues}는 서식에서 출처가 AGENT인
 * 항목(임시등록이면 CATALOG 출처 항목도)에만 받는다.
 */
public sealed interface ItemInput {

    boolean recommended();

    boolean requestedByCustomer();

    Map<String, JsonNode> agentValues();

    record Catalog(ProductKey productKey, boolean recommended, boolean requestedByCustomer, Map<String, JsonNode> agentValues)
            implements ItemInput {
        public Catalog {
            Objects.requireNonNull(productKey, "productKey");
            agentValues = Map.copyOf(agentValues);
        }
    }

    record Temp(InsurerCode insurer, String productName, String quoteDocNo, boolean recommended, boolean requestedByCustomer,
                Map<String, JsonNode> agentValues) implements ItemInput {
        public Temp {
            Objects.requireNonNull(insurer, "insurer");
            agentValues = Map.copyOf(agentValues);
        }
    }
}
