package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.domain.enums.ArtifactKind;
import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.seal.canonical.CanonicalDocument;
import com.ga.disclosure.workflow.disclosure.ArtifactService;
import com.ga.disclosure.workflow.disclosure.SealService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 3B 봉인 성공 경로(설계서 §6.4, 계획 §3.2): 번호 = {테넌트}-{봉인 연도}-{6자리}, 봉인 컬럼·키·산출물 2건·체인 머리, 커밋 후 Object Lock(보존기한 =
 * 봉인일 + 5년의 당일 끝), 감사 순서 CUSTOMER_VIEW → DISCLOSURE_SEAL → (커밋 후) ARTIFACT_RETAIN ×2, 열람은 평문 해시 대조 후.
 */
class SealFlowIT {

    private final SealSetup s = new SealSetup();

    @AfterEach
    void close() {
        s.close();
    }

    @Test
    void sealsCommitsThenLocks() {
        SealService.Outcome o = s.sealReasoned();
        assertThat(o.sealed()).as("%s %s", o.rejections(), o.results()).isTrue();
        assertThat(o.status()).isEqualTo(DisclosureStatus.SEALED);
        assertThat(o.retentionPending()).isFalse();
        assertThat(o.number().orElseThrow().value()).isEqualTo(s.w.tenant.value() + "-2026-000001");
        DisclosureId id = o.id();

        assertThat(s.count("SELECT count(*) FROM disclosure WHERE tenant_id = ? AND disclosure_id = ? AND status = 'SEALED' AND chain_seq = 1"
                + " AND retention_until = DATE '2031-09-23'", s.w.tenant.value(), id.value())).isEqualTo(1);
        assertThat(s.count("SELECT count(*) FROM document_artifact WHERE tenant_id = ? AND disclosure_id = ? AND retention_applied_at IS NOT NULL",
                s.w.tenant.value(), id.value())).isEqualTo(2);
        assertThat(s.count("SELECT count(*) FROM document_key WHERE tenant_id = ? AND disclosure_id = ? AND wrapped_dek IS NOT NULL",
                s.w.tenant.value(), id.value())).isEqualTo(1);
        assertThat(s.count("SELECT chain_seq FROM disclosure_chain_head WHERE tenant_id = ?", s.w.tenant.value())).isEqualTo(1);
        assertThat(s.objects()).isEqualTo(2);
        s.artifactsOf(id).forEach(a -> assertThat(s.bucket.retention(a.storageKey())).hasValue(
                SealService.retainUntilInstant(LocalDate.of(2031, 9, 23))));

        assertThat(s.actionsFor(id)).containsSubsequence(AuditAction.DISCLOSURE_SEAL);
        assertThat(s.audit().stream().map(r -> r.entry().action()).toList())
                .containsSubsequence(AuditAction.CUSTOMER_VIEW, AuditAction.DISCLOSURE_SEAL, AuditAction.ARTIFACT_RETAIN, AuditAction.ARTIFACT_RETAIN);

        ArtifactService.View view = s.artifacts.view(s.w.tenant, SealSetup.MANAGER, id, ArtifactKind.CANONICAL_JSON);
        assertThat(view).isInstanceOf(ArtifactService.View.Granted.class);
        CanonicalDocument canonical = CanonicalDocument.parse(((ArtifactService.View.Granted) view).plaintext());
        assertThat(canonical.sha256()).isEqualTo(s.text("SELECT canonical_hash FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?",
                s.w.tenant.value(), id.value()));
        assertThat(canonical.json().path("customerName").asString()).isNotBlank();
        // 봉인의 검증 결과는 DISCLOSURE_SEAL detail에 있고 별도 검증 행을 남기지 않는다(직전 행은 추천사유 전이)
        java.util.List<AuditAction> actions = s.actionsFor(id);
        assertThat(actions.subList(actions.size() - 2, actions.size()))
                .containsExactly(AuditAction.DISCLOSURE_TRANSITION, AuditAction.DISCLOSURE_SEAL);
    }
}
