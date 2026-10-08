package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditRecord;
import com.ga.disclosure.infra.crypto.LocalFileKeyProvider;
import com.ga.disclosure.infra.persistence.AuditLogRepository;
import com.ga.disclosure.infra.persistence.CatalogRepository;
import com.ga.disclosure.infra.persistence.CustomerVaultRepository;
import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.disclosure.infra.tx.TenantTransactionTemplate;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.catalog.CatalogImportOutcome;
import com.ga.disclosure.workflow.catalog.CatalogImportService;
import com.ga.disclosure.workflow.customer.CustomerRefService;
import com.ga.disclosure.workflow.customer.CustomerRekeyService;
import com.ga.platform.core.tenant.TenantId;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantSessionBinder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Supplier;

/**
 * Phase 2 통합 테스트 조립: 실제 어댑터(카탈로그·고객 키 저장소·감사)와 워크플로 서비스. 시계는 고정(Asia/Seoul),
 * KEK는 임시 디렉터리의 로컬 키 파일(소유자 전용 권한)이다.
 */
final class CatalogCustomerSetup {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final Actor OPERATOR = new Actor("ops@test", "OPERATOR");

    final PostgresHarness db = PostgresHarness.get();
    final TenantJdbcGateway gateway = new TenantJdbcGateway(db.appDataSource());
    final TenantTransactionTemplate tx = new TenantTransactionTemplate(new TenantSessionBinder(db.appDataSource()));
    final AuditLogRepository audit = new AuditLogRepository(gateway);
    final CatalogRepository catalog = new CatalogRepository(gateway);
    final Clock clock;
    final Path kekFile;
    final LocalFileKeyProvider keys;
    final CustomerVaultRepository vault;
    final CatalogImportService imports;
    final CustomerRefService customers;
    final CustomerRekeyService rekey;

    CatalogCustomerSetup(String instant) {
        this(instant, newKekFile());
    }

    CatalogCustomerSetup(String instant, Path kekFile) {
        this.clock = Clock.fixed(Instant.parse(instant), SEOUL);
        this.kekFile = kekFile;
        this.keys = LocalFileKeyProvider.load(kekFile);
        this.vault = new CustomerVaultRepository(gateway, keys);
        this.imports = new CatalogImportService(catalog, audit, tx, clock, Callers.authz(clock));
        this.customers = new CustomerRefService(vault, audit, tx, clock, Callers.authz(clock));
        this.rekey = new CustomerRekeyService(vault, audit, tx, clock, Callers.authz(clock));
    }

    CatalogCustomerSetup() {
        this("2026-09-01T00:00:00Z");
    }

    /** 같은 KEK 파일, 다른 시각. */
    CatalogCustomerSetup at(String instant) {
        return new CatalogCustomerSetup(instant, kekFile);
    }

    static Path newKekFile() {
        try {
            Path file = Files.createTempDirectory("ga-kek").resolve("kek.json");
            LocalFileKeyProvider.initialize(file, "KEK-TEST-1");
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    TenantId freshTenant(String prefix) {
        String t = SeedData.uniqueTenant(prefix);
        db.seed(t, c -> SeedData.tenant(c, t));
        return TenantId.of(t);
    }

    <T> T in(TenantId tenant, Supplier<T> work) {
        return tx.inTenant(tenant, work);
    }

    List<AuditRecord> auditOf(TenantId tenant) {
        return in(tenant, audit::readAll);
    }

    CatalogImportOutcome importJson(TenantId tenant, String fileName, String json) {
        return imports.importFile(Callers.of(tenant, OPERATOR), fileName, json.getBytes(StandardCharsets.UTF_8));
    }
}
