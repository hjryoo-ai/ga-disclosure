package com.ga.disclosure.rules.template;

import com.ga.disclosure.domain.enums.IdentityMethod;
import com.ga.disclosure.domain.enums.SignatureChannel;
import com.ga.disclosure.domain.enums.SignatureMethod;
import com.ga.disclosure.domain.enums.SignerRole;
import com.ga.disclosure.rules.testing.Bundles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 서명 외관 페이지 문구는 서식 데이터(설계서 §6.5 v1.9): 정본 서식에서 읽히고, 빠진 키는 기본값 없이 실패한다. */
class SignaturePageLayoutTest {

    private static TemplateResolution standard() {
        return TemplateResolver.resolution(Bundles.template(Bundles.template(Bundles.STANDARD_V1), null));
    }

    @Test
    void labelsComeFromTheTemplate() {
        SignaturePageLayout l = SignaturePageLayout.of(standard());
        assertThat(l.title()).isEqualTo("서명 확인");
        assertThat(l.columns().signedAt()).isEqualTo("서명 시각");
        assertThat(l.role(SignerRole.MANAGER)).isEqualTo("관리자");
        assertThat(l.channel(SignatureChannel.SSO)).isEqualTo("사내 인증");
        assertThat(l.method(SignatureMethod.SSO_APPROVAL)).isEqualTo("승인");
        assertThat(l.identity(IdentityMethod.BIRTH_DATE)).isEqualTo("생년월일");
        assertThat(l.passLabel()).isEqualTo("확인");
        assertThat(l.failLabel()).isEqualTo("불일치");
        // CERTIFIED_ESIGN은 v2 — 서식에 문구가 없으면 쓰는 순간 실패한다(기본 문구 없음)
        assertThatThrownBy(() -> l.channel(SignatureChannel.CERTIFIED_ESIGN)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"title", "columns", "originalPdfLabel", "roleLabels", "channelLabels", "methodLabels", "identityLabels", "resultLabels"})
    void missingKeysFail(String key) {
        TemplateResolution t = standard();
        ObjectNode layout = (ObjectNode) t.layout();
        ((ObjectNode) layout.get("signaturePage")).remove(key);
        TemplateResolution edited = new TemplateResolution(t.ref(), t.templateType(), t.fields(), layout, t.pendingConfirmationRefs());
        assertThatThrownBy(() -> SignaturePageLayout.of(edited)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void templateWithoutASignaturePageFails() {
        TemplateResolution t = standard();
        ObjectNode layout = (ObjectNode) t.layout();
        layout.remove("signaturePage");
        assertThatThrownBy(() -> SignaturePageLayout.of(new TemplateResolution(t.ref(), t.templateType(), t.fields(), layout,
                t.pendingConfirmationRefs()))).isInstanceOf(IllegalStateException.class);
    }
}
