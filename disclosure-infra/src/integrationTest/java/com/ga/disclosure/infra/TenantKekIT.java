package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.verify.VerifySchemas;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.infra.crypto.CiphertextRejectedException;
import com.ga.disclosure.infra.crypto.LocalFileKeyProvider;
import com.ga.disclosure.infra.crypto.TenantKeyProvider;
import com.ga.disclosure.infra.persistence.KekRewrapRepository;
import com.ga.disclosure.infra.persistence.TenantKekRepository;
import com.ga.disclosure.infra.secret.FileSecretSource;
import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.disclosure.infra.testing.TestKeks;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.domain.enums.ArtifactKind;
import com.ga.disclosure.workflow.artifact.ArtifactUnreadableException;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.customer.CustomerRefService;
import com.ga.disclosure.workflow.disclosure.ArtifactService;
import com.ga.disclosure.workflow.customer.CustomerRekeyService;
import com.ga.disclosure.workflow.customer.KeyProviderPort;
import com.ga.disclosure.workflow.job.JobKind;
import com.ga.disclosure.workflow.job.JobRunner;
import com.ga.disclosure.workflow.job.StandardJobs;
import com.ga.disclosure.workflow.kek.KekRewrapStore;
import com.ga.disclosure.workflow.kek.TenantKekService;
import com.ga.disclosure.workflow.kek.TenantKekService.Outcome;
import com.ga.disclosure.workflow.kek.TenantKekStore;
import com.ga.disclosure.workflow.secret.SecretName;
import com.ga.platform.canonical.Canonicalizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 8 테넌트 KEK(8 계획 승인 Q2): 레지스트리 가드(GD140), 재래핑 함수의 전제(GD141)와 불변 트리거의 재래핑 분기, 전역 시절 KEK로 감싼 문서·고객·보고서 키를
 * 이중 읽기로 열고 {@code KEK_REWRAP}으로 테넌트 KEK로 옮기기(행마다 감사, 두 번째 실행 0건, 파기된 키 제외, 다른 테넌트 불변), 옮긴 뒤 전역 경로 없이 전부
 * 열림, 테넌트 KEK 회전(v1 → v2).
 */
class TenantKekIT {

    private static final String OPERATOR = "ops-kek@test";

    private final JobSetup j = new JobSetup();
    private final WorkflowSetup w = j.s.w;
    private final PostgresHarness db = w.db;
    private final TenantKekRepository registry = new TenantKekRepository(w.gateway);
    private final LocalFileKeyProvider legacy = LocalFileKeyProvider.load(CatalogCustomerSetup.newKekFile());

    @AfterEach
    void close() {
        j.close();
    }

    private Caller operator() {
        return Caller.cli(w.tenant, OPERATOR);
    }

    private TenantKekService service(KeyProviderPort keys) {
        return new TenantKekService(registry, new KekRewrapRepository(w.gateway), keys, w.audit, w.tx, Callers.authz(w.clock), w.clock);
    }

    private KeyProviderPort tenantKeys(LocalFileKeyProvider legacyOrNull) {
        return new TenantKeyProvider(TestKeks.shared().secrets(), t -> w.tx.inTenant(t, registry::current), legacyOrNull);
    }

    private List<String> keks(String sql) {
        return db.asApp(w.tenant.value(), c -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        });
    }

    private List<String> allWrappingKeks() {
        List<String> all = new ArrayList<>(keks("SELECT kek_key_id FROM document_key WHERE wrapped_dek IS NOT NULL"));
        all.addAll(keks("SELECT kek_id FROM customer_data_key WHERE status <> 'DESTROYED'"));
        all.addAll(keks("SELECT report_kek_id FROM async_job WHERE report_key_wrapped IS NOT NULL"));
        return all;
    }

    /** 전역 시절 KEK로 봉인 1건·고객 키 1개(재순환)·작업 보고서 1건을 만든다. */
    private record GlobalEra(DisclosureId sealed, UUID job) {
    }

    private GlobalEra globalEraData() {
        w.keys.use(SwitchableKeys.globalEra(legacy, tenantKeys(null)));
        DisclosureId sealed = j.s.sealReasoned().id();
        new CustomerRekeyService(w.vault, w.audit, w.tx, w.clock, Callers.authz(w.clock)).rekey(operator(), 100);
        JobRunner.Run<String> run = j.runner().run(List.of(j.operator()), JobKind.RECONCILE, JobSetup.params(), JobSetup.fixed("{\"global\":true}"));
        UUID job = UUID.fromString(db.asApp(w.tenant.value(), c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT job_id::text FROM async_job WHERE report_key_wrapped IS NOT NULL");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }));
        assertThat(run.result()).isPresent();
        assertThat(allWrappingKeks()).as("everything sealed in the global era is wrapped by the one global KEK").containsOnly(legacy.currentKekId());
        return new GlobalEra(sealed, job);
    }

    private byte[] pdf(DisclosureId id) {
        ArtifactService.View view = j.s.artifacts.view(Callers.of(w.tenant, SealSetup.MANAGER), id, ArtifactKind.PDF);
        assertThat(view).isInstanceOf(ArtifactService.View.Granted.class);
        return ((ArtifactService.View.Granted) view).plaintext();
    }

    private void readsEverything(GlobalEra g) {
        assertThat(pdf(g.sealed())).startsWith("%PDF".getBytes());
        assertThat(new CustomerRefService(w.vault, w.audit, w.tx, w.clock, Callers.authz(w.clock))
                .lookup(w.tenant, new Actor(OPERATOR, "OPERATOR"), w.customer)).isNotNull();
        assertThat(new String(j.queries.report(j.operator(), g.job()))).isEqualTo("{\"global\":true}");
    }

    // ------------------------------------------------------------------ 이행: 전역 → 테넌트

    @Test
    void globalEraKeysMoveToTheTenantKekAndNothingNeedsTheGlobalKeyAfterwards() {
        GlobalEra g = globalEraData();
        String tenantKek = TestKeks.firstKekId(w.tenant.value());

        // 1a 이중 읽기: 테넌트 KEK 어댑터가 옛 ID의 키를 전역 KEK로 푼다
        w.keys.use(tenantKeys(legacy));
        readsEverything(g);

        TenantKekService keks = service(w.keys);
        TenantKekService.Report dry = keks.rewrap(operator(), false, UUID.randomUUID());
        assertThat(dry.count(Outcome.PENDING)).as("one document key, one customer key, one report key").isEqualTo(3);
        assertThat(dry.toKekId()).isEqualTo(tenantKek);
        assertThat(allWrappingKeks()).as("a dry run writes nothing").containsOnly(legacy.currentKekId());
        assertThat(j.s.audit()).noneMatch(r -> r.entry().action() == AuditAction.KEK_REWRAPPED);

        UUID job = UUID.randomUUID();
        TenantKekService.Report applied = keks.rewrap(operator(), true, job);
        assertThat(applied.count(Outcome.REWRAPPED)).isEqualTo(3);
        assertThat(VerifySchemas.kekRewrapReport(Canonicalizer.parseStrict(new String(Canonicalizer.canonicalize(applied.toJson()))))).isEmpty();
        assertThat(allWrappingKeks()).containsOnly(tenantKek);
        assertThat(j.s.audit()).filteredOn(r -> r.entry().action() == AuditAction.KEK_REWRAPPED).as("one audit row per rewrapped key")
                .hasSize(3).allSatisfy(r -> {
                    assertThat(r.entry().detail().get("fromKekId").asString()).isEqualTo(legacy.currentKekId());
                    assertThat(r.entry().detail().get("toKekId").asString()).isEqualTo(tenantKek);
                    assertThat(r.entry().detail().get("jobId").asString()).isEqualTo(job.toString());
                    assertThat(r.entry().actorRole()).isEqualTo("OPERATOR");
                });
        assertThat(keks.rewrap(operator(), true, UUID.randomUUID()).items()).as("the second run has nothing to do").isEmpty();

        // 1b 이후: 전역 KEK 없이 전부 열린다
        w.keys.use(tenantKeys(null));
        readsEverything(g);
    }

    /** 주입 B1(이중 읽기 제거): 옮기기 전에 전역 경로를 빼면 옛 문서·고객·보고서는 열리지 않는다 — 그래서 2단 업그레이드다. */
    @Test
    void withoutTheGlobalPathTheUnmigratedKeysDoNotOpen() {
        GlobalEra g = globalEraData();
        w.keys.use(tenantKeys(null));
        assertThatThrownBy(() -> pdf(g.sealed()))
                .isInstanceOfAny(CiphertextRejectedException.class, ArtifactUnreadableException.class);
        TenantKekService.Report r = service(w.keys).rewrap(operator(), true, UUID.randomUUID());
        assertThat(r.count(Outcome.FAILED)).as("a key that cannot be unwrapped is reported and left as it was").isEqualTo(3);
        assertThat(allWrappingKeks()).containsOnly(legacy.currentKekId());
    }

    @Test
    void aShreddedDocumentKeyIsNotRewrapped() {
        w.keys.use(SwitchableKeys.globalEra(legacy, tenantKeys(null)));
        DisclosureId sealed = j.s.sealReasoned().id();
        // 파기 묘비: 정의자 롤 + 표식(파기 함수가 하는 것과 같은 분기 — 트리거를 끄지 않는다)
        db.seed(w.tenant.value(), c -> SeedData.exec(c, "SET ROLE disclosure_destroy_definer; DO $$ BEGIN "
                + "PERFORM set_config('ga.destroy', 'key:" + sealed + "', true); "
                + "UPDATE document_key SET wrapped_dek = NULL, shredded_at = now(), shredded_by = 'it' WHERE disclosure_id = '" + sealed + "'; "
                + "END $$; RESET ROLE"));
        w.keys.use(tenantKeys(legacy));
        TenantKekService.Report r = service(w.keys).rewrap(operator(), true, UUID.randomUUID());
        assertThat(r.items()).noneMatch(i -> i.target() == KekRewrapStore.Target.DOCUMENT_KEY);
        assertThat(keks("SELECT kek_key_id || ':' || (wrapped_dek IS NULL) FROM document_key")).containsExactly(legacy.currentKekId() + ":true");
    }

    @Test
    void anotherTenantsKeysAreUntouched() {
        GlobalEra g = globalEraData();
        JobSetup other = new JobSetup();
        try {
            other.s.w.keys.use(SwitchableKeys.globalEra(legacy, TestKeks.shared().standalone()));
            other.s.sealReasoned();
            w.keys.use(tenantKeys(legacy));
            service(w.keys).rewrap(operator(), true, UUID.randomUUID());
            String otherKek = other.s.w.db.asApp(other.s.w.tenant.value(), c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT kek_key_id FROM document_key"); ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            });
            assertThat(otherKek).isEqualTo(legacy.currentKekId());
            assertThat(other.s.w.tenant).isNotEqualTo(w.tenant);
            readsEverything(g);
        } finally {
            other.close();
        }
    }

    // ------------------------------------------------------------------ 회전: 테넌트 KEK v1 → v2 (G5의 두 번째 실행)

    @Test
    void rotatingTheTenantKekRewrapsEverythingAndTheOldKekIsNoLongerNeeded() {
        DisclosureId sealed = j.s.sealReasoned().id();
        j.runner().run(List.of(j.operator()), JobKind.RECONCILE, JobSetup.params(), JobSetup.fixed("{\"v\":1}"));
        String v1 = TestKeks.firstKekId(w.tenant.value());
        String v2 = w.tenant.value() + "-KEK-2";
        assertThat(allWrappingKeks()).containsOnly(v1);

        TestKeks.shared().ensure(w.tenant, v2);
        TenantKekService keks = service(w.keys);
        keks.register(operator(), v2);
        assertThat(w.tx.inTenant(w.tenant, registry::all)).extracting(TenantKekIT::view).containsExactlyInAnyOrder(v1 + ":RETIRED", v2 + ":CURRENT");
        assertThat(j.s.audit()).filteredOn(r -> r.entry().action() == AuditAction.KEK_REGISTERED).singleElement()
                .satisfies(r -> assertThat(r.entry().detail().get("retired").asString()).isEqualTo(v1));
        // 회전 직후 새 봉인은 v2로
        DisclosureId after = j.s.sealReasoned().id();
        assertThat(keks("SELECT kek_key_id FROM document_key WHERE disclosure_id = '" + after + "'")).containsExactly(v2);

        TenantKekService.Report r = keks.rewrap(operator(), true, UUID.randomUUID());
        assertThat(r.count(Outcome.REWRAPPED)).isGreaterThanOrEqualTo(3);
        assertThat(r.count(Outcome.FAILED)).isZero();
        assertThat(allWrappingKeks()).containsOnly(v2);

        // 옛 KEK 바이트가 없는 출처로도 전부 열린다
        TestKeks onlyV2 = TestKeks.fresh();
        copy(TestKeks.shared(), onlyV2, v2);
        w.keys.use(new TenantKeyProvider(onlyV2.secrets(), t -> w.tx.inTenant(t, registry::current), null));
        assertThat(pdf(sealed)).startsWith("%PDF".getBytes());
    }

    @Test
    void registeringAKekWhoseBytesAreMissingIsRefusedBeforeTheRegistryChanges() {
        String missing = w.tenant.value() + "-KEK-9";
        assertThatThrownBy(() -> service(w.keys).register(operator(), missing)).isInstanceOf(IllegalStateException.class).hasMessageContaining(missing);
        assertThat(w.tx.inTenant(w.tenant, registry::current)).contains(TestKeks.firstKekId(w.tenant.value()));
    }

    // ------------------------------------------------------------------ DB 가드

    /** 앱 롤에는 레지스트리 삭제·식별 변경과 감싼 키 갱신 권한부터 없다(42501). 권한이 있는 소유자도 트리거에 막힌다(GD140·GD092·GD060·GD121). */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "app|42501|DELETE FROM tenant_kek",
            "app|42501|UPDATE tenant_kek SET registered_by = 'x'",
            "app|42501|UPDATE document_key SET kek_key_id = 'X-KEK-1'",
            "app|GD140|UPDATE tenant_kek SET status = 'CURRENT', retired_at = NULL",
            "app|GD140|INSERT INTO tenant_kek (tenant_id, kek_id, status, registered_at, registered_by, retired_at) "
                    + "SELECT tenant_id, tenant_id || '-KEK-7', 'RETIRED', now(), 'x', now() FROM tenant_kek",
            "app|23505|INSERT INTO tenant_kek (tenant_id, kek_id, status, registered_at, registered_by) "
                    + "SELECT tenant_id, tenant_id || '-KEK-8', 'CURRENT', now(), 'x' FROM tenant_kek",
            "app|23514|INSERT INTO tenant_kek (tenant_id, kek_id, status, registered_at, registered_by) "
                    + "SELECT tenant_id, 'KEK-LOCAL-1', 'CURRENT', now(), 'x' FROM tenant_kek",
            "app|GD060|UPDATE customer_data_key SET kek_id = 'X-KEK-1'",
            "app|GD121|UPDATE async_job SET report_kek_id = 'X-KEK-1' WHERE report_kek_id IS NOT NULL",
            "app|GD141|SELECT ga_kek_rewrap(current_setting('app.tenant_id'), 'document_key', key_id, kek_key_id, kek_key_id, wrapped_dek) FROM document_key",
            "app|GD141|SELECT ga_kek_rewrap(current_setting('app.tenant_id'), 'document_key', key_id, kek_key_id, tenant_id || '-KEK-5', wrapped_dek) "
                    + "FROM document_key",
            "app|GD141|SELECT ga_kek_rewrap('OTHER', 'document_key', key_id, kek_key_id, 'OTHER-KEK-1', wrapped_dek) FROM document_key",
            "app|GD141|SELECT ga_kek_rewrap(current_setting('app.tenant_id'), 'nope', key_id, 'A', tenant_id || '-KEK-1', wrapped_dek) FROM document_key",
            "app|GD141|SELECT ga_kek_rewrap(current_setting('app.tenant_id'), 'document_key', key_id, 'A', tenant_id || '-KEK-1', '\\x01'::bytea) "
                    + "FROM document_key",
            "owner|GD140|DELETE FROM tenant_kek",
            "owner|GD140|UPDATE tenant_kek SET registered_by = 'x'",
            "owner|GD140|TRUNCATE tenant_kek",
            "owner|GD092|UPDATE document_key SET kek_key_id = 'X-KEK-1'"
    })
    void theRegistryAndTheWrappedKeysAreGuardedByTheDatabase(String caseSpec) {
        String[] parts = caseSpec.split("\\|", 3);
        j.s.sealReasoned();
        j.runner().run(List.of(j.operator()), JobKind.RECONCILE, JobSetup.params(), JobSetup.fixed("{}"));
        String state = parts[0].equals("app")
                ? TriggerAssertions.sqlStateOf(() -> db.asApp(w.tenant.value(), c -> {
                    try (var st = c.createStatement()) {
                        return st.execute(parts[2]);
                    }
                }))
                : TriggerAssertions.sqlStateOf(() -> db.seed(w.tenant.value(), c -> SeedData.exec(c, parts[2])));
        assertThat(state).isEqualTo(parts[1]);
    }

    /** 정의자 롤도 표식 없이는 재래핑 분기를 열 수 없다(파기 분기와 같은 장치). */
    @Test
    void theDefinerWithoutTheMarkerCannotChangeAWrappedKey() {
        j.s.sealReasoned();
        assertThat(TriggerAssertions.sqlStateOf(() -> db.seed(w.tenant.value(), c -> SeedData.exec(c,
                "SET ROLE disclosure_destroy_definer; UPDATE document_key SET kek_key_id = kek_key_id || 'X'; RESET ROLE")))).isEqualTo("GD092");
    }

    /** 레지스트리는 테넌트 격리(RLS): 다른 테넌트의 KEK 행이 보이지 않는다. */
    @Test
    void theRegistryIsTenantIsolated() {
        JobSetup other = new JobSetup();
        try {
            assertThat(w.tx.inTenant(w.tenant, registry::all)).extracting(TenantKekIT::view)
                    .containsExactly(TestKeks.firstKekId(w.tenant.value()) + ":CURRENT");
        } finally {
            other.close();
        }
    }

    private static void copy(TestKeks from, TestKeks to, String kekId) {
        String tenant = kekId.substring(0, kekId.indexOf("-KEK-"));
        try {
            FileSecretSource.create(to.dir(), SecretName.of("kek/" + tenant + "/" + kekId), Files.readAllBytes(from.dir().resolve("kek/" + tenant + "/" + kekId)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 레지스트리 행의 시험용 표기 "ID:상태". */
    private static String view(TenantKekStore.Registered r) {
        return r.kekId() + ":" + (r.current() ? "CURRENT" : "RETIRED");
    }
}
