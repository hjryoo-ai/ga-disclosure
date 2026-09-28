package com.ga.disclosure.domain.enums;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class DisclosureStatusTest {

    @Test
    void exactlyFourMutableStates() {
        assertThat(Arrays.stream(DisclosureStatus.values()).filter(DisclosureStatus::isMutable))
                .containsExactly(DisclosureStatus.DRAFT, DisclosureStatus.COMPARED, DisclosureStatus.GRADED, DisclosureStatus.REASONED);
        assertThat(Arrays.stream(DisclosureStatus.values()).filter(DisclosureStatus::isSealedOrLater))
                .containsExactly(DisclosureStatus.SEALED, DisclosureStatus.PARTIALLY_SIGNED, DisclosureStatus.COMPLETED,
                        DisclosureStatus.VOID, DisclosureStatus.SUPERSEDED, DisclosureStatus.EXPIRED);
    }

    @Test
    void enumsMatchDesignDocument() {
        assertThat(SignerRole.values()).extracting(Enum::name).containsExactly("CUSTOMER", "AGENT", "MANAGER");
        assertThat(SignatureChannel.values()).extracting(Enum::name)
                .containsExactly("TOUCH_PAD", "REMOTE_LINK", "PAPER_SCAN", "CERTIFIED_ESIGN");
        assertThat(IssuerMode.values()).extracting(Enum::name).containsExactly("SELF", "ASSOC");
        assertThat(GateMode.values()).extracting(Enum::name).containsExactly("BLOCK", "WARN", "OFF");
        assertThat(ManagerConfirmMode.values()).extracting(Enum::name).containsExactly("REQUIRED", "OPTIONAL", "OFF");
        assertThat(GradeStatus.values()).extracting(Enum::name).containsExactly("OK", "UNAVAILABLE");
        assertThat(ReconStatus.values()).extracting(Enum::name).containsExactly("MATCHED", "MISSING", "LATE", "EXEMPT");
        assertThat(RuleStatus.values()).extracting(Enum::name).containsExactly("DRAFT", "APPROVED", "ACTIVE", "RETIRED");
        assertThat(ArtifactKind.values()).extracting(Enum::name)
                .containsExactly("CANONICAL_JSON", "PDF", "SIGNED_PDF", "EVIDENCE_ZIP");
    }
}
