package com.ga.disclosure.app;

import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 운영자 CLI(프로파일 cli)를 실제 애플리케이션으로 실행한다: 데모 시드 → 번들 배포(--tenants all) → 사규 승인 → 활성화 → 대사.
 * 실패(거부)는 애플리케이션 시작 실패(종료 코드 ≠ 0)로 드러난다. 데모 테넌트 ID는 테스트마다 고유 접두를 붙인 시드 파일을 만든다.
 */
class OperatorCliIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static final Path ROOT = Path.of(System.getProperty("ga.repoRoot"));

    private static String run(String... args) {
        List<String> all = new ArrayList<>(List.of(
                "--spring.profiles.active=cli",
                "--spring.datasource.url=" + DB.jdbcUrl(),
                "--spring.flyway.url=" + DB.jdbcUrl(),
                "--ga.tenant-directory.url=" + DB.jdbcUrl(),
                "--ga.job-lock.url=" + DB.jdbcUrl()));
        all.addAll(List.of(args));
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            ConfigurableApplicationContext context = new SpringApplicationBuilder(DisclosureApplication.class).run(all.toArray(String[]::new));
            context.close();
            return buffer.toString(StandardCharsets.UTF_8);
        } finally {
            System.setOut(original);
            CliOutputScan.assertClean(buffer.toString(StandardCharsets.UTF_8));   // 실패 경로의 출력도(6A §9.3)
        }
    }

    private static String bundle(String relative) {
        return ROOT.resolve("contracts/rules/bundles").resolve(relative).toString();
    }

    private static List<String> column(String tenant, String sql) {
        return DB.asApp(tenant, c -> query(c, sql, tenant));
    }

    private static List<String> query(Connection c, String sql, String tenant) throws java.sql.SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tenant);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    @Test
    void demoSeedFlowEndsWithActiveRulesAndNoDrift() throws Exception {
        String one = SeedData.uniqueTenant("DEMO1");
        String two = SeedData.uniqueTenant("DEMO2");
        String seed = java.nio.file.Files.readString(ROOT.resolve("disclosure-demo/src/main/resources/demo/phase1-seed.json"))
                .replace("\"DEMO1\"", "\"" + one + "\"").replace("\"DEMO2\"", "\"" + two + "\"");
        Path seedFile = java.nio.file.Files.createTempFile("seed", ".json");
        java.nio.file.Files.writeString(seedFile, seed);

        assertThat(run("demo", "seed", "--file", seedFile.toString(), "--operator", "cli-test"))
                .contains("SEED TENANT " + one + " CREATED", "SEED TENANT_RULE " + one + " DEMO1-HOUSE-2026 DRAFT",
                        "SEED IDENTITY_LINK " + one + " DEMO-AGENT-1 CREATED", "SEED IDENTITY_LINK " + two + " DEMO-MGR-1 CREATED");
        assertThat(run("rules", "distribute", "--bundle", bundle("rules/DISC-2026-07.bundle.json"), "--tenants", "all", "--operator", "cli-test"))
                .contains("DISTRIBUTE " + one + " DISC-2026-07@", "DISTRIBUTE " + two + " DISC-2026-07@");
        run("rules", "distribute", "--bundle", "rules/DISC-2027-01.bundle.json", "--tenants", one + "," + two, "--operator", "cli-test");
        run("rules", "distribute", "--bundle", "templates/STANDARD-v1.bundle.json", "--tenants", one + "," + two, "--operator", "cli-test");
        assertThat(run("rules", "approve", "--tenant", one, "--rule", "DEMO1-HOUSE-2026", "--operator", "cli-test")).contains("APPROVED");
        assertThat(run("rules", "activate", "--as-of", "2026-09-23", "--tenants", one + "," + two, "--operator", "cli-test"))
                .contains("ACTIVATE " + one + " asOf=2026-09-23 retired=[] activated=[DEMO1-HOUSE-2026, DISC-2026-07]");
        assertThat(run("rules", "reconcile", "--tenants", one + "," + two, "--bundles-dir", ROOT.resolve("contracts/rules/bundles").toString(),
                "--operator", "cli-test")).contains("RECONCILE total drift=0");

        assertThat(column(one, "SELECT rule_version_id || ':' || status FROM rule_version WHERE tenant_id = ? ORDER BY 1"))
                .containsExactly("DEMO1-HOUSE-2026:ACTIVE", "DISC-2026-07:ACTIVE", "DISC-2027-01:APPROVED");
        assertThat(column(two, "SELECT rule_version_id || ':' || status FROM rule_version WHERE tenant_id = ? ORDER BY 1"))
                .containsExactly("DISC-2026-07:ACTIVE", "DISC-2027-01:APPROVED");
        assertThat(column(one, "SELECT DISTINCT actor_role FROM audit_log WHERE tenant_id = ?")).containsExactly("OPERATOR");
    }

    @Test
    void rejectedDistributionFailsTheCommand() {
        String bare = SeedData.uniqueTenant("BARE");
        DB.seed(bare, c -> SeedData.tenant(c, bare));
        assertThatThrownBy(() -> run("rules", "distribute", "--bundle", bundle("rules/DISC-2027-01.bundle.json"), "--tenants", bare,
                "--operator", "cli-test"))
                .hasStackTraceContaining("distribution rejected");
    }

    @Test
    void unknownCommandAndMissingOptionFail() {
        assertThatThrownBy(() -> run("rules", "delete", "--operator", "x")).hasStackTraceContaining("unknown command 'rules delete'");
        assertThatThrownBy(() -> run("rules", "approve", "--tenant", "T1", "--operator", "x")).hasStackTraceContaining("missing --rule");
    }

    /** 데모 테넌트: 번들·카탈로그·고객까지(봉인 산출물 저장소·로컬 KEK·엔진 스텁 인자 포함). */
    private record DemoTenant(String tenant, String[] storage, Path demo) {
    }

    private static DemoTenant demoTenant(String prefix) throws Exception {
        String tenant = SeedData.uniqueTenant(prefix);
        DB.seed(tenant, c -> {
            SeedData.tenant(c, tenant);
            // Phase 4: 확인서의 설계사는 identity_link로 해석한다(데모 기본 행위자 demo-agent·demo-manager)
            SeedData.identityLink(c, tenant, "demo-agent", "DEMO-AGENT-1", "AGENT");
            SeedData.identityLink(c, tenant, "demo-manager", "DEMO-MGR-1", "MANAGER");
        });
        com.ga.disclosure.infra.testing.SeaweedHarness s3 = com.ga.disclosure.infra.testing.SeaweedHarness.get();
        String bucket = s3.freshBucket();
        Path kek = java.nio.file.Files.createTempDirectory("cli-seal-kek").resolve("kek.json");
        run("crypto", "init-kek", "--file", kek.toString());
        Path demo = ROOT.resolve("disclosure-demo/src/main/resources");
        String[] storage = {"--ga.crypto.local-kek-file=" + kek, "--ga.storage.s3.endpoint=" + s3.endpoint(), "--ga.storage.s3.bucket=" + bucket,
                "--ga.storage.s3.access-key-id=" + com.ga.disclosure.infra.testing.SeaweedHarness.ACCESS_KEY,
                "--ga.storage.s3.secret-access-key=" + com.ga.disclosure.infra.testing.SeaweedHarness.SECRET_KEY,
                "--ga.engine.mode=stub", "--ga.engine.stub-table=" + demo.resolve("demo/engine-table.json")};
        for (String b : List.of("rules/DISC-2026-07.bundle.json", "rules/DISC-2027-01.bundle.json", "templates/STANDARD-v1.bundle.json")) {
            run("rules", "distribute", "--bundle", bundle(b), "--tenants", tenant, "--operator", "cli-test");
        }
        run("rules", "activate", "--as-of", "2026-09-23", "--tenants", tenant, "--operator", "cli-test");
        for (String file : List.of("product-groups.json", "insurer-panel.json", "products.json")) {
            run("catalog", "import", "--tenant", tenant, "--file", demo.resolve("demo/catalog").resolve(file).toString(), "--operator", "cli-test");
        }
        run(with(storage, "customer", "import", "--tenant", tenant, "--file", demo.resolve("customers.json").toString(), "--operator", "cli-test"));
        return new DemoTenant(tenant, storage, demo);
    }

    /**
     * 3B 데모(지시문 §7): 새 테넌트에 번들·카탈로그·고객을 넣고 데모 확인서 시드를 <b>두 번</b> 돌린다 — 1회차는 봉인 4건(A-1 정상, A-2 임시등록 +
     * 관리자 승인, Phase 4 서명·만료 데모용 A-3·A-5)과 정정 1건(A-1 → 새 버전 REASONED), 2회차는 전부 NOOP(번호·버전이 늘지 않는다). 이어서
     * 산출물 열람(평문 해시 대조), 잔여물 정리·재적용, 봉인 거부의 종료 코드 2, 사유 파일로 받는 무효.
     */
    @Test
    void demoSeedSealsFourSupersedesOneAndIsIdempotent() throws Exception {
        DemoTenant prepared = demoTenant("CLI_SEAL");
        String tenant = prepared.tenant();
        String[] storage = prepared.storage();
        Path demo = prepared.demo();
        String first = run(with(storage, "demo", "disclosures", "--tenant", tenant, "--file", demo.resolve("demo/disclosures.json").toString(),
                "--operator", "cli-test"));
        assertThat(first).contains("A-1 seal -> SEALED no=" + tenant + "-2026-", "A-2 approve R-TEMP-PRODUCT", "A-2 seal -> SEALED no=",
                "A-1 supersede -> SUPERSEDED next=", "A-1 CORRECTED id=").contains("version=2 status=REASONED");
        String second = run(with(storage, "demo", "disclosures", "--tenant", tenant, "--file", demo.resolve("demo/disclosures.json").toString(),
                "--operator", "cli-test"));
        assertThat(second).contains("A-1 NOOP", "A-2 NOOP", "A-1 seal NOOP", "A-2 seal NOOP", "A-1 supersede NOOP")
                .doesNotContain("SEALED no=", "CORRECTED");
        assertThat(column(tenant, "SELECT status || ':' || version FROM disclosure WHERE tenant_id = ? ORDER BY consult_date, version"))
                .containsExactly("SUPERSEDED:1", "REASONED:2", "SEALED:1", "SEALED:1", "SEALED:1", "REASONED:1");
        assertThat(column(tenant, "SELECT seq FROM disclosure_counter WHERE tenant_id = ?")).containsExactly("4");
        assertThat(column(tenant, "SELECT count(*) FROM document_artifact WHERE tenant_id = ? AND retention_applied_at IS NOT NULL"))
                .containsExactly("8");

        String sealedId = column(tenant, "SELECT disclosure_id::text FROM disclosure WHERE tenant_id = ? AND status = 'SEALED' ORDER BY consult_date"
                + " LIMIT 1").getFirst();
        Path pdf = java.nio.file.Files.createTempFile("cli-seal", ".pdf");
        assertThat(run(with(storage, "artifacts", "get", "--tenant", tenant, "--id", sealedId, "--kind", "PDF", "--out", pdf.toString(),
                "--operator", "auditor-1"))).contains("ARTIFACT_GET " + tenant + " " + sealedId + " PDF sha256=");
        assertThat(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(pdf))))
                .isEqualTo(column(tenant, "SELECT pdf_hash FROM disclosure WHERE tenant_id = ? AND disclosure_id = '" + sealedId + "'::uuid")
                        .getFirst());
        assertThat(run(with(storage, "artifacts", "gc", "--tenants", tenant, "--operator", "cli-test")))
                .contains("ARTIFACT_GC " + tenant + " scanned=8 deleted=0 referenced=8");
        assertThat(run(with(storage, "artifacts", "reconcile", "--tenants", tenant, "--operator", "cli-test")))
                .contains("ARTIFACT_RECONCILE " + tenant + " applied=0 failed=0");

        // 봉인 거부 → 종료 코드 2와 거부 코드 목록(A-1-REQUEST의 추천사유 행을 지워 SEAL 단계 검증을 막는다)
        String reasoned = column(tenant, "SELECT disclosure_id::text FROM disclosure WHERE tenant_id = ? AND status = 'REASONED' AND version = 1")
                .getFirst();
        DB.seed(tenant, c -> SeedData.exec(c, "DELETE FROM recommendation WHERE tenant_id = ? AND disclosure_id = ?::uuid", tenant, reasoned));
        assertThatThrownBy(() -> run(with(storage, "disclosure", "seal", "--tenant", tenant, "--id", reasoned, "--operator", "cli-test")))
                .hasStackTraceContaining("seal rejected: [VALIDATION_BLOCKED, APPROVAL_MISSING]")   // 이 사례는 승인 없는 산출불가 항목도 있다
                .satisfies(e -> assertThat(rootCause(e)).isInstanceOf(org.springframework.boot.ExitCodeGenerator.class)
                        .extracting(t -> ((org.springframework.boot.ExitCodeGenerator) t).getExitCode()).isEqualTo(2));
        Path reason = java.nio.file.Files.createTempFile("void-reason", ".txt");
        java.nio.file.Files.writeString(reason, "(가상) 고객 상담 철회");
        // 6A 승인 Q9: --role은 폐기 — 조용히 무시하지 않고 거부한다(감사 역할은 OPERATOR, 업무 역할은 identity_link)
        assertThatThrownBy(() -> run(with(storage, "disclosure", "void", "--tenant", tenant, "--id", sealedId, "--reason-code", "CUSTOMER_CANCELLED",
                "--operator", "demo-manager", "--role", "MANAGER"))).hasStackTraceContaining("--role is no longer accepted");
        // V8: 사유 코드는 고정 룰의 닫힌 목록(voidReasons) — 모르는 코드는 거부(종료 코드 2), 텍스트는 파일로만
        assertThatThrownBy(() -> run(with(storage, "disclosure", "void", "--tenant", tenant, "--id", sealedId, "--reason-code", "NOT_A_REASON",
                "--operator", "demo-manager")))
                .hasStackTraceContaining("REASON_CODE_UNKNOWN")
                .satisfies(e -> assertThat(rootCause(e)).isInstanceOf(org.springframework.boot.ExitCodeGenerator.class));
        assertThat(run(with(storage, "disclosure", "void", "--tenant", tenant, "--id", sealedId, "--reason-code", "CUSTOMER_CANCELLED",
                "--reason-file", reason.toString(), "--operator", "demo-manager")))
                .contains("VOID " + tenant + " " + sealedId + " VOID")
                .doesNotContain("고객 상담 철회");
        assertThat(column(tenant, "SELECT status || ':' || coalesce(disclosure_no, '-') || ':' || void_reason_code FROM disclosure"
                + " WHERE tenant_id = ? AND voided_at IS NOT NULL"))
                .singleElement().satisfies(v -> assertThat(v).startsWith("VOID:" + tenant + "-2026-").endsWith(":CUSTOMER_CANCELLED"));
    }

    /**
     * Phase 4 데모 서명(4 계획 §7.6): 3자 터치 완료(A-2), 종이 스캔 → 관리자 확인 완료(A-4-SCAN — 데모는 DEMO2 파일, 여기서는 사규 없는 한 테넌트에
     * 두 파일을 다 돌린다), 원격 링크 — 콘솔 통지의 토큰으로 열람·본인확인(파일)·
     * 서명, 설계사·관리자는 CLI(A-3-REMOTE), 만료 1건({@code --as-of P30D}, A-5-EXPIRE). 두 번째 실행은 NOOP(링크 재발송 없음, 만료 0). 쓴 토큰은
     * 종료 코드 2. 본인확인 입력값은 출력에 없다.
     */
    @Test
    void demoSignaturesCompleteThreeFlowsExpireOneAndRepeatAsNoop() throws Exception {
        DemoTenant t = demoTenant("CLI_SIGN");
        String tenant = t.tenant();
        String[] storage = t.storage();
        Path demo = t.demo();
        Path sign = demo.resolve("demo/sign");
        run(with(storage, "demo", "disclosures", "--tenant", tenant, "--file", demo.resolve("demo/disclosures.json").toString(), "--operator", "cli-test"));
        run(with(storage, "demo", "disclosures", "--tenant", tenant, "--file", demo.resolve("demo/disclosures-demo2.json").toString(), "--operator",
                "cli-test"));
        assertThat(run(with(storage, "demo", "signatures", "--tenant", tenant, "--file", demo.resolve("demo/signatures-demo2.json").toString())))
                .contains("A-4-SCAN id=", "PAPER_SCAN -> COMPLETED");

        String first = run(with(storage, "demo", "signatures", "--tenant", tenant, "--file", demo.resolve("demo/signatures.json").toString()));
        // 6A: 원격 링크는 발급 때 아웃박스에 적재되고(queued=) 같은 명령 끝의 통지 발송(작업 NOTIFY)이 보낸다 — 토큰은 프래그먼트(/s#)
        assertThat(first).contains("A-2 id=", "TOUCH_PAD -> COMPLETED", "A-3-REMOTE id=", "REMOTE_LINK queued=", "NOTIFY " + tenant + " sent=1")
                .contains("SIGN LINK https://sign.example.invalid/s#" + tenant + "~");
        String token = first.lines().filter(l -> l.startsWith("SIGN LINK ")).findFirst().orElseThrow().replaceFirst("^SIGN LINK .*/s#", "");
        String remoteId = first.lines().filter(l -> l.startsWith("DEMO_SIGN " + tenant + " A-3-REMOTE id=")).findFirst().orElseThrow()
                .replaceFirst("^.* id=([0-9a-f-]+) .*$", "$1");

        assertThat(run(with(storage, "sign", "open", "--token", token, "--view-file", sign.resolve("view.json").toString()))).contains("SIGN_OPEN bytes=",
                "view=RECORDED");
        String verified = run(with(storage, "sign", "verify", "--token", token, "--inputs-file", sign.resolve("identity-C01.json").toString()));
        assertThat(verified).contains("BIRTH_DATE:PASS", "missing=[]").doesNotContain("1900-01-01").doesNotContain("19000101");
        assertThat(run(with(storage, "sign", "capture", "--token", token, "--strokes-file", sign.resolve("strokes.json").toString(), "--image-file",
                sign.resolve("signature.png").toString(), "--device-file", sign.resolve("phone-device.json").toString(), "--ip", "203.0.113.10")))
                .contains("SIGN_CAPTURE " + remoteId + " PARTIALLY_SIGNED signature=");
        assertThat(run(with(storage, "sign", "agent", "--tenant", tenant, "--id", remoteId, "--operator", "demo-agent", "--strokes-file",
                sign.resolve("strokes.json").toString(), "--image-file", sign.resolve("signature.png").toString())))
                .contains("SIGN_AGENT " + remoteId + " PARTIALLY_SIGNED");
        assertThat(run(with(storage, "sign", "manager", "--tenant", tenant, "--id", remoteId, "--operator", "demo-manager", "--ack", "all")))
                .contains("SIGN_MANAGER " + remoteId + " COMPLETED signature=");
        assertThat(run(with(storage, "disclosure", "expire", "--tenants", tenant, "--as-of", "P30D", "--operator", "cli-test")))
                .contains("EXPIRE " + tenant, "expired=1");
        assertThat(column(tenant, "SELECT status FROM disclosure WHERE tenant_id = ? AND disclosure_no IS NOT NULL ORDER BY consult_date, disclosure_no"))
                .containsExactly("SUPERSEDED", "COMPLETED", "COMPLETED", "COMPLETED", "EXPIRED");
        assertThat(column(tenant, "SELECT count(*) FROM document_artifact WHERE tenant_id = ? AND kind IN ('SIGNED_PDF', 'EVIDENCE_ZIP')"))
                .containsExactly("6");

        assertThatThrownBy(() -> run(with(storage, "sign", "capture", "--token", token, "--strokes-file", sign.resolve("strokes.json").toString(),
                "--image-file", sign.resolve("signature.png").toString())))
                .hasStackTraceContaining("sign token rejected")
                .satisfies(e -> assertThat(rootCause(e)).isInstanceOf(org.springframework.boot.ExitCodeGenerator.class)
                        .extracting(x -> ((org.springframework.boot.ExitCodeGenerator) x).getExitCode()).isEqualTo(2));

        String second = run(with(storage, "demo", "signatures", "--tenant", tenant, "--file", demo.resolve("demo/signatures.json").toString()));
        assertThat(second).contains("A-2 id=", "NOOP (COMPLETED)").doesNotContain("SIGN LINK");
        assertThat(second.lines().filter(l -> l.contains("NOOP (COMPLETED)")).count()).isEqualTo(2);
        assertThat(run(with(storage, "demo", "signatures", "--tenant", tenant, "--file", demo.resolve("demo/signatures-demo2.json").toString())))
                .contains("A-4-SCAN id=", "NOOP (COMPLETED)");
        assertThat(run(with(storage, "disclosure", "expire", "--tenants", tenant, "--as-of", "P30D", "--operator", "cli-test")))
                .contains("expired=0", "NOOP");
    }

    private static String[] with(String[] prefix, String... args) {
        String[] all = java.util.Arrays.copyOf(prefix, prefix.length + args.length);
        System.arraycopy(args, 0, all, prefix.length, args.length);
        return all;
    }

    private static Throwable rootCause(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && !(t instanceof org.springframework.boot.ExitCodeGenerator)) {
            t = t.getCause();
        }
        return t;
    }

    /**
     * Phase 2: 카탈로그 수입(상품군 → 패널 → 상품, 데모 파일) — 같은 파일 재수입은 NOOP. 로컬 KEK 파일 생성은 한 번만(덮어쓰기 거부).
     * 고객 데이터 키 순환은 두 번째 실행에서 첫 키를 은퇴·파기한다(쓰는 행이 없으므로).
     */
    @Test
    void catalogImportKekInitAndCustomerRekey() throws Exception {
        String tenant = SeedData.uniqueTenant("CLI_CAT");
        DB.seed(tenant, c -> SeedData.tenant(c, tenant));
        Path catalog = ROOT.resolve("disclosure-demo/src/main/resources/demo/catalog");
        for (String file : List.of("product-groups.json", "insurer-panel.json", "products.json")) {
            assertThat(run("catalog", "import", "--tenant", tenant, "--file", catalog.resolve(file).toString(), "--operator", "cli-test"))
                    .contains("CATALOG_IMPORT " + tenant).contains(" IMPORTED ");
        }
        assertThat(run("catalog", "import", "--tenant", tenant, "--file", catalog.resolve("products.json").toString(), "--operator", "cli-test"))
                .contains(" PRODUCTS NOOP ");
        assertThat(column(tenant, "SELECT count(*) FROM product_catalog WHERE tenant_id = ?")).containsExactly("9");
        assertThat(column(tenant, "SELECT DISTINCT source FROM product_catalog WHERE tenant_id = ?")).containsExactly("DEMO_FILE");

        Path kek = java.nio.file.Files.createTempDirectory("cli-kek").resolve("kek.json");
        assertThat(run("crypto", "init-kek", "--file", kek.toString())).contains("KEK_INIT KEK-LOCAL-1");
        assertThat(java.nio.file.Files.getPosixFilePermissions(kek)).extracting(Enum::name)
                .containsExactlyInAnyOrder("OWNER_READ", "OWNER_WRITE");
        assertThatThrownBy(() -> run("crypto", "init-kek", "--file", kek.toString())).hasStackTraceContaining("already exists");

        String first = run("--ga.crypto.local-kek-file=" + kek, "customer", "rekey", "--tenant", tenant, "--operator", "cli-test");
        assertThat(first).contains("REKEY " + tenant + " retired=- ").contains("reencrypted=0 destroyed=[]");
        String second = run("--ga.crypto.local-kek-file=" + kek, "customer", "rekey", "--tenant", tenant, "--operator", "cli-test");
        String firstKey = first.substring(first.indexOf("active=") + 7).split(" ")[0];
        assertThat(second).contains("retired=" + firstKey).contains("destroyed=[" + firstKey + "]");
        assertThat(column(tenant, "SELECT status FROM customer_data_key WHERE tenant_id = ? ORDER BY created_at, status"))
                .containsExactlyInAnyOrder("DESTROYED", "ACTIVE");
    }
}
