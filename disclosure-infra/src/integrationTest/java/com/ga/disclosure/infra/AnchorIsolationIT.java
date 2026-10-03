package com.ga.disclosure.infra;

import com.ga.disclosure.audit.tsa.NonceSource;
import com.ga.disclosure.audit.tsa.TimestampClient;
import com.ga.disclosure.audit.tsa.stub.LocalStubTsa;
import com.ga.disclosure.infra.persistence.AnchorRepository;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.workflow.anchor.AnchorJob;
import com.ga.disclosure.workflow.anchor.AnchorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G3(5 계획 §2, 지시문 목표 1): 두 테넌트가 같은 루트를 공유해도 A의 앵커·영수증 행에는 B의 테넌트 ID와 두 체인 머리가 나타나지 않는다(경로의 형제는 B 잎의
 * 해시일 뿐이다). RLS: B로 바인딩한 세션은 A의 앵커·영수증을 읽지 못하고, A의 앵커에 영수증을 쓰지 못한다.
 */
class AnchorIsolationIT {

    final SealSetup a = new SealSetup();
    final SealSetup b = new SealSetup();
    final AnchorRepository anchors = new AnchorRepository(a.w.gateway);
    final LocalStubTsa tsa = LocalStubTsa.ephemeral(a.w.clock);

    @AfterEach
    void close() {
        a.close();
        b.close();
    }

    private String rowsAsJson(SealSetup s, String table) {
        return s.text("SELECT coalesce(json_agg(t)::text, '[]') FROM " + table + " t WHERE t.tenant_id = ?", s.w.tenant.value());
    }

    @Test
    void aTenantsRowsCarryNothingOfTheOtherTenant() {
        a.sealReasoned();
        b.sealReasoned();
        new AnchorJob(anchors, a.w.audit, a.w.tx, new RuleResolver(a.w.rules), new TimestampClient(tsa, NonceSource.secure(), tsa.trustAnchors()),
                a.w.clock).run(List.of(a.w.tenant, b.w.tenant), AnchorJobIT.DAY, AnchorJobIT.SYSTEM);

        AnchorStore.StoredAnchor anchorB = b.w.tx.inTenant(b.w.tenant, () -> anchors.onDate(AnchorJobIT.DAY)).orElseThrow();
        String rowsOfA = rowsAsJson(a, "anchor") + rowsAsJson(a, "anchor_receipt");

        assertThat(rowsOfA).contains(a.w.tenant.value());
        assertThat(rowsOfA).doesNotContain(b.w.tenant.value(), anchorB.record().sealChainHead(), anchorB.record().auditHead());
        assertThat(a.text("SELECT root_hash FROM anchor_receipt WHERE tenant_id = ?", a.w.tenant.value()))
                .isEqualTo(b.text("SELECT root_hash FROM anchor_receipt WHERE tenant_id = ?", b.w.tenant.value()));
    }

    @Test
    void rlsKeepsAnotherTenantsAnchorsAndReceiptsOutOfReach() {
        new AnchorJob(anchors, a.w.audit, a.w.tx, new RuleResolver(a.w.rules), new TimestampClient(tsa, NonceSource.secure(), tsa.trustAnchors()),
                a.w.clock).run(List.of(a.w.tenant), AnchorJobIT.DAY, AnchorJobIT.SYSTEM);
        String tenantA = a.w.tenant.value();

        long seen = b.w.db.asApp(b.w.tenant.value(), c -> com.ga.disclosure.infra.testing.SeedData.longValue(c,
                "SELECT (SELECT count(*) FROM anchor WHERE tenant_id = ?) + (SELECT count(*) FROM anchor_receipt WHERE tenant_id = ?)", tenantA, tenantA));
        assertThat(seen).isZero();

        assertThatThrownBy(() -> b.w.db.asApp(b.w.tenant.value(), c -> {
            try (var ps = c.prepareStatement("""
                    INSERT INTO anchor_receipt (tenant_id, anchor_seq, batch_id, root_hash, tree_depth, leaf_index, merkle_path, tsa_token,
                                                tsa_gen_time, tsa_policy_oid, tsa_serial, created_at)
                    VALUES (?, 1, gen_random_uuid(), repeat('a', 64), 1, 0, '["%s"]'::jsonb, '\\x00'::bytea, now(), '1.2.3', 'ff', now())
                    """.formatted("b".repeat(64)))) {
                ps.setString(1, tenantA);
                return ps.executeUpdate();
            }
        })).hasRootCauseInstanceOf(SQLException.class).rootCause().satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42501"));
    }
}
