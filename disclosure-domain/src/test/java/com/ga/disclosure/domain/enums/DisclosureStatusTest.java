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

    /** 승인 Q3: 고객 채널만 룰 {@code channels}의 대상이고 SSO는 설계사·관리자 경로다. */
    @Test
    void ssoIsTheOnlyNonCustomerChannel() {
        assertThat(Arrays.stream(SignatureChannel.values()).filter(ch -> !ch.isCustomerChannel())).containsExactly(SignatureChannel.SSO);
    }

    @Test
    void enumsMatchDesignDocument() {
        assertThat(SignerRole.values()).extracting(Enum::name).containsExactly("CUSTOMER", "AGENT", "MANAGER");
        assertThat(SignatureChannel.values()).extracting(Enum::name)
                .containsExactly("TOUCH_PAD", "REMOTE_LINK", "PAPER_SCAN", "CERTIFIED_ESIGN", "SSO");
        assertThat(SignatureMethod.values()).extracting(Enum::name).containsExactly("DRAWN", "UPLOADED_SCAN", "SSO_APPROVAL");
        assertThat(IdentityMethod.values()).extracting(Enum::name)
                .containsExactly("LINK_POSSESSION", "BIRTH_DATE", "AGENT_FACE_TO_FACE", "SCROLL_COMPLETE", "PROVIDER");
        assertThat(RetentionAnchor.values()).extracting(Enum::name).containsExactly("SEAL", "COMPLETION", "CONTRACT_DATE");
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
