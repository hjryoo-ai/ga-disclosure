package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.rules.validation.ValidationResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 3B S7(단위): 봉인 판정은 승인을 (규칙, 대상 해시, 고정 룰 버전 2종)이 모두 같을 때만 인정한다(3A 수용심사 §3-8). 대상 해시만 같고 룰 버전이
 * 다른 승인 — 재기준 전 승인 — 은 무시된다. 오버라이드 불가 실패는 승인으로 덮이지 않는다.
 */
class SealGateTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DisclosureId ID = DisclosureId.of(UUID.randomUUID());
    private static final RuleVersionId RULE = RuleVersionId.of("DISC-2026-07");
    private static final RuleVersionId HOUSE = RuleVersionId.of("HOUSE-2026");

    private static final ValidationResult TEMP = ValidationResult.failOverridable("R-TEMP-PRODUCT", "임시등록",
            JSON.readTree("[{\"insurer\":\"INS-D\",\"quoteDocNo\":\"Q-1\"}]"));
    private static final ValidationResult BLOCKING = ValidationResult.fail("R-REASON", "사유 없음");
    private static final ValidationResult PASSED = ValidationResult.pass("R-PANEL", "패널");

    private static Review review(String rule, String hash, RuleVersionId global, RuleVersionId tenantOrNull) {
        return new Review(UUID.randomUUID(), ID, rule, hash, global, tenantOrNull, "manager@test", "MANAGER", Instant.parse("2026-09-23T02:00:00Z"),
                "확인");
    }

    @Test
    void approvalCountsOnlyForTheSameRuleSubjectAndPinnedVersions() {
        String hash = TEMP.subjectHash().orElseThrow();
        List<ValidationResult> results = List.of(PASSED, TEMP);
        assertThat(SealGate.unapproved(results, List.of(), RULE, Optional.empty())).containsExactly(TEMP);
        assertThat(SealGate.unapproved(results, List.of(review("R-TEMP-PRODUCT", hash, RULE, null)), RULE, Optional.empty())).isEmpty();
        // 대상 해시가 다르면 무효
        assertThat(SealGate.unapproved(results, List.of(review("R-TEMP-PRODUCT", "0".repeat(64), RULE, null)), RULE, Optional.empty()))
                .containsExactly(TEMP);
        // 대상은 같지만 룰 버전이 다르면 무효 — 재기준 전 승인(GLOBAL이 다르거나, 사규가 생겼거나 바뀌었다)
        assertThat(SealGate.unapproved(results, List.of(review("R-TEMP-PRODUCT", hash, RuleVersionId.of("DISC-2026-09"), null)), RULE,
                Optional.empty())).containsExactly(TEMP);
        assertThat(SealGate.unapproved(results, List.of(review("R-TEMP-PRODUCT", hash, RULE, null)), RULE, Optional.of(HOUSE)))
                .containsExactly(TEMP);
        assertThat(SealGate.unapproved(results, List.of(review("R-TEMP-PRODUCT", hash, RULE, HOUSE)), RULE, Optional.of(HOUSE))).isEmpty();
        // 다른 규칙의 승인은 무효
        assertThat(SealGate.unapproved(results, List.of(review("R-GRADE-UNAVAILABLE", hash, RULE, null)), RULE, Optional.empty()))
                .containsExactly(TEMP);
    }

    @Test
    void blockingFailuresAreNeverCoveredAndTheLatestMatchingApprovalResolvesTheFlag() {
        String hash = TEMP.subjectHash().orElseThrow();
        Review first = review("R-TEMP-PRODUCT", hash, RULE, null);
        Review later = review("R-TEMP-PRODUCT", hash, RULE, null);
        assertThat(SealGate.unapproved(List.of(BLOCKING, TEMP), List.of(first), RULE, Optional.empty())).containsExactly(BLOCKING);
        assertThat(SealGate.approvalFor(TEMP, List.of(first, later), RULE, Optional.empty())).contains(later);
        assertThat(SealGate.approvalFor(BLOCKING, List.of(first), RULE, Optional.empty())).isEmpty();
        assertThat(SealGate.approvalFor(PASSED, List.of(first), RULE, Optional.empty())).isEmpty();
    }
}
