package com.ga.disclosure.app;

import com.ga.disclosure.app.cli.OfflineCli;
import com.ga.disclosure.infra.storage.ObjectVersionCopier;
import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeaweedHarness;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.disclosure.infra.testing.TestKeks;
import com.ga.disclosure.workflow.secret.SecretName;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 복구 증명(Phase 8 ⑤, G2 — 8 계획 "복구 증명"): 환경 A에서 수명주기 전부(봉인·서명 완료·앵커·영수증·법적 보류·파기 묘비)를 만든 뒤
 * <ol>
 *   <li>백업 — {@code pg_basebackup}(복제 롤 {@code disclosure_backup}, 테이블 권한 0) → {@code backup seal}(백업 키, 평문 센티널이 봉투에 없다) →
 *       {@code backup objects export}(모든 버전·보존·보류) → {@code backup upload}(보존 기한 하한 = 같은 실행 산출물의 가장 늦은 보존 기한).</li>
 *   <li>복구 — 새 PostgreSQL B(빈 볼륨에 백업 tar) · 새 버킷에 {@code backup objects import}.</li>
 *   <li>단언 — 모든 표의 내용 해시 A = B(복구 직후, B에 아무것도 쓰기 전), 저장소 모양 A = B(키·버전 순서·바이트 해시·보존·보류), 모든 테넌트
 *       {@code verify tenant} MATCH, 증거 패키지 바이트 A = B, 영수증과 함께 {@code verify package} MATCH(TSA 토큰·신뢰 앵커), 재조정 실패 0.</li>
 *   <li>음성 대조(공회전 방지) — 객체 하나를 빼고 복구한 버킷에서 {@code verify tenant}가 정확히 그 키 하나를 {@code OBJECT_MISSING}으로 잡는다.</li>
 * </ol>
 * 환경 A·B는 이 시험 전용 컨테이너(공유 하네스 아님 — 데이터 디렉터리를 통째로 갈아 끼운다). 저장소는 하네스 SeaweedFS의 새 버킷들.
 */
class RestoreProofIT {

    static final Path ROOT = Path.of(System.getProperty("ga.repoRoot"));
    static final Path DEMO = ROOT.resolve("disclosure-demo/src/main/resources");
    static final Path BUNDLES = ROOT.resolve("contracts/rules/bundles");
    static final String PGDATA = "/var/lib/postgresql/18/docker";
    static final String SUPERUSER_PASSWORD = "restore-proof-" + UUID.randomUUID();
    static final SeaweedHarness S3 = SeaweedHarness.get();
    static final List<GenericContainer<?>> STARTED = new ArrayList<>();

    @AfterAll
    static void stop() {
        STARTED.forEach(GenericContainer::stop);
    }

    // ------------------------------------------------------------------------------------------------ 환경

    /** 환경 A: 하네스와 같은 이미지·롤 SQL, 그리고 백업 롤의 복제 연결 허용(pg_hba — 컨테이너 IP로, 비밀번호 인증). */
    static GenericContainer<?> environmentA() {
        GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse(PostgresHarness.IMAGE))
                .withEnv(Map.of("POSTGRES_DB", "disclosure", "POSTGRES_USER", "postgres", "POSTGRES_PASSWORD", SUPERUSER_PASSWORD))
                .withCopyFileToContainer(MountableFile.forHostPath(ROOT.resolve("docker/postgres/init-roles.sql")), "/docker-entrypoint-initdb.d/00-init-roles.sql")
                .withCopyToContainer(Transferable.of("echo 'host replication disclosure_backup all scram-sha-256' >> \"$PGDATA/pg_hba.conf\"\n"),
                        "/docker-entrypoint-initdb.d/01-replication.sh")
                .withExposedPorts(5432)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2));
        c.start();
        STARTED.add(c);
        return c;
    }

    /** 환경 B: 빈 볼륨에 백업 tar를 풀고 그대로 기동(initdb·초기화 스크립트 없음 — 데이터 디렉터리가 이미 있다). */
    static GenericContainer<?> environmentB(Path baseTar) {
        GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse(PostgresHarness.IMAGE))
                .withCopyFileToContainer(MountableFile.forHostPath(baseTar), "/restore/base.tar")
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh", "-c", "set -e; mkdir -p " + PGDATA + "; tar -xf /restore/base.tar -C " + PGDATA
                        + "; chown -R postgres:postgres /var/lib/postgresql; chmod 700 " + PGDATA + "; exec docker-entrypoint.sh postgres"))
                .withExposedPorts(5432)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 1));
        c.start();
        STARTED.add(c);
        return c;
    }

    static String url(GenericContainer<?> c) {
        return "jdbc:postgresql://" + c.getHost() + ":" + c.getMappedPort(5432) + "/disclosure";
    }

    static Connection connect(GenericContainer<?> c, String user, String password) throws SQLException {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url(c));
        ds.setUser(user);
        ds.setPassword(password);
        return ds.getConnection();
    }

    /** 표마다 행 수와 내용 해시(정렬된 행 텍스트의 md5) — 슈퍼유저(RLS 밖)로. */
    static Map<String, String> tableDigests(GenericContainer<?> c) throws SQLException {
        Map<String, String> out = new TreeMap<>();
        try (Connection conn = connect(c, "postgres", SUPERUSER_PASSWORD); Statement s = conn.createStatement()) {
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = s.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            for (String t : tables) {
                try (ResultSet rs = s.executeQuery("SELECT count(*) || ':' || coalesce(md5(string_agg(x::text, '|' ORDER BY x::text)), '-') FROM \"" + t + "\" x")) {
                    rs.next();
                    out.put(t, rs.getString(1));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ CLI

    record Run(int exit, String out) {
    }

    /** 웹이 아닌 운영자 CLI를 그 DB·버킷으로(같은 JVM). 실패해도 출력과 종료 코드를 돌려준다. */
    static Run cli(GenericContainer<?> db, String bucket, Path tmp, String... args) {
        String url = url(db);
        List<String> all = new ArrayList<>(List.of("--spring.profiles.active=cli",
                "--spring.datasource.url=" + url, "--ga.health.url=" + url, "--ga.tenant-directory.url=" + url, "--ga.job-lock.url=" + url,
                "--ga.secrets.dir=" + TestKeks.shared().dir(),
                "--ga.storage.s3.endpoint=" + S3.endpoint(), "--ga.storage.s3.bucket=" + bucket,
                "--ga.storage.s3.access-key-id=" + SeaweedHarness.ACCESS_KEY, "--ga.storage.s3.secret-access-key=" + SeaweedHarness.SECRET_KEY,
                "--ga.engine.mode=stub", "--ga.engine.stub-table=" + DEMO.resolve("demo/engine-table.json"),
                "--ga.tsa.mode=stub", "--ga.tsa.stub.key-store=" + tmp.resolve("tsa-stub.p12"), "--ga.tsa.trust-pem=" + tmp.resolve("tsa-trust.pem")));
        all.addAll(List.of(args));
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        int exit = 0;
        try {
            new SpringApplicationBuilder(DisclosureApplication.class).run(all.toArray(String[]::new)).close();
        } catch (RuntimeException e) {
            Throwable t = e;
            while (t.getCause() != null && !(t instanceof ExitCodeGenerator)) {
                t = t.getCause();
            }
            exit = t instanceof ExitCodeGenerator g ? g.getExitCode() : 1;
        } finally {
            System.setOut(original);
        }
        String out = buffer.toString(StandardCharsets.UTF_8);
        CliOutputScan.assertClean(out);
        return new Run(exit, out);
    }

    static String ok(Run r) {
        assertThat(r.exit()).as(r.out()).isZero();
        return r.out();
    }

    /** DB가 필요 없는 명령(db migrate·backup *) — 앱 컨텍스트 없이. */
    static String offline(String... args) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        OfflineCli.run(args, new PrintStream(buffer, true, StandardCharsets.UTF_8));
        String out = buffer.toString(StandardCharsets.UTF_8);
        CliOutputScan.assertClean(out);
        return out;
    }

    static String[] backupStore(String artifactsBucket, String backupBucket) {
        return new String[] {"--ga.secrets.dir=" + TestKeks.shared().dir(),
                "--ga.storage.s3.endpoint=" + S3.endpoint(), "--ga.storage.s3.bucket=" + artifactsBucket,
                "--ga.storage.s3.access-key-id=" + SeaweedHarness.ACCESS_KEY, "--ga.storage.s3.secret-access-key=" + SeaweedHarness.SECRET_KEY,
                "--ga.backup.s3.endpoint=" + S3.endpoint(), "--ga.backup.s3.bucket=" + backupBucket,
                "--ga.backup.s3.access-key-id=" + SeaweedHarness.ACCESS_KEY, "--ga.backup.s3.secret-access-key=" + SeaweedHarness.SECRET_KEY};
    }

    static String[] concat(String[] a, String... b) {
        String[] all = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
    }

    static String idOf(String output, String head) {
        return output.lines().filter(l -> l.startsWith(head)).findFirst().orElseThrow(() -> new AssertionError(head + " not in output"))
                .replaceFirst("^.* (?:id|existing)=([0-9a-f-]{36}).*$", "$1");
    }

    static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** 테넌트·식별 연결(마이그레이터 — 하네스 seed와 같은 방식)과 룰·카탈로그·고객. */
    static void prepare(GenericContainer<?> a, String bucket, Path tmp, String tenant, List<Path> bundles) throws Exception {
        try (Connection c = connect(a, PostgresHarness.MIGRATOR, "migrator_local_only")) {
            c.setAutoCommit(false);
            PostgresHarness.setTenant(c, tenant);
            SeedData.tenant(c, tenant);
            SeedData.identityLink(c, tenant, "demo-agent", "DEMO-AGENT-1", "AGENT");
            SeedData.identityLink(c, tenant, "demo-manager", "DEMO-MGR-1", "MANAGER");
            c.commit();
        }
        for (Path b : bundles) {
            ok(cli(a, bucket, tmp, "rules", "distribute", "--bundle", b.toString(), "--tenants", tenant, "--operator", "restore-proof"));
        }
        ok(cli(a, bucket, tmp, "rules", "activate", "--as-of", "2026-09-23", "--tenants", tenant, "--operator", "restore-proof"));
        for (String file : List.of("product-groups.json", "insurer-panel.json", "products.json")) {
            ok(cli(a, bucket, tmp, "catalog", "import", "--tenant", tenant, "--file", DEMO.resolve("demo/catalog").resolve(file).toString(),
                    "--operator", "restore-proof"));
        }
        ok(cli(a, bucket, tmp, "customer", "import", "--tenant", tenant, "--file", DEMO.resolve("customers.json").toString(), "--operator", "restore-proof"));
    }

    // ------------------------------------------------------------------------------------------------ 증명

    @Test
    void aBackupRestoresEveryTableObjectAnchorAndTombstoneAndAMissingObjectIsCaught() throws Exception {
        Path tmp = Files.createTempDirectory("restore-proof");
        TestKeks.shared().ensureKey(SecretName.of("backup/key"));
        for (String name : List.of("api/cursor", "api/request-hash", "api/customer-receipt")) {
            TestKeks.shared().ensureKey(SecretName.of(name));
        }
        S3Client s3 = S3.client();
        String bucketA = S3.freshBucket();
        String backupBucket = S3.freshBucket();
        String bucketB = S3.freshBucket();

        // ---- 환경 A: 스키마 → 수명주기
        GenericContainer<?> a = environmentA();
        assertThat(offline("db", "migrate", "--ga.migrator.url=" + url(a))).contains("DB_MIGRATE applied=");
        String done = SeedData.uniqueTenant("RST_DONE");
        String shortLived = SeedData.uniqueTenant("RST_SHORT");
        prepare(a, bucketA, tmp, done, List.of(BUNDLES.resolve("rules/DISC-2026-07.bundle.json"), BUNDLES.resolve("templates/STANDARD-v1.bundle.json")));
        prepare(a, bucketA, tmp, shortLived, List.of(DEMO.resolve("demo/bundles/rules/DISC-DEMO-SHORT.bundle.json"),
                BUNDLES.resolve("templates/STANDARD-v1.bundle.json")));

        ok(cli(a, bucketA, tmp, "demo", "disclosures", "--tenant", done, "--file", DEMO.resolve("demo/disclosures-demo2.json").toString(), "--operator", "restore-proof"));
        String signed = ok(cli(a, bucketA, tmp, "demo", "signatures", "--tenant", done, "--file", DEMO.resolve("demo/signatures-demo2.json").toString()));
        String completed = idOf(signed, "DEMO_SIGN " + done + " A-4-SCAN");
        assertThat(ok(cli(a, bucketA, tmp, "anchor", "run", "--tenants", done, "--operator", "restore-proof"))).contains("receipts=1");
        Path receiptA = tmp.resolve("receipt-a.json");
        ok(cli(a, bucketA, tmp, "anchor", "receipt", "export", "--tenant", done, "--id", completed, "--out", receiptA.toString(), "--operator", "auditor-1"));
        Path evidenceA = tmp.resolve("evidence-a.zip");
        ok(cli(a, bucketA, tmp, "artifacts", "get", "--tenant", done, "--id", completed, "--kind", "EVIDENCE_ZIP", "--out", evidenceA.toString(),
                "--operator", "auditor-1"));

        String seeded = ok(cli(a, bucketA, tmp, "--spring.profiles.active=cli,demo", "--ga.demo.clock-offset=-P5D", "demo", "disclosures", "--tenant",
                shortLived, "--file", DEMO.resolve("demo/disclosures-demo3.json").toString(), "--operator", "restore-proof"));
        String shortId = idOf(seeded, "DEMO_DISCLOSURE " + shortLived + " A-6-SHORT");
        String heldId = idOf(seeded, "DEMO_DISCLOSURE " + shortLived + " A-7-HOLD");
        ok(cli(a, bucketA, tmp, "artifacts", "reconcile", "--tenants", shortLived, "--operator", "restore-proof"));
        ok(cli(a, bucketA, tmp, "legal-hold", "place", "--tenant", shortLived, "--id", heldId, "--reason-code", "LITIGATION", "--operator", "compliance-1"));
        assertThat(ok(cli(a, bucketA, tmp, "retention", "destroy", "--tenants", shortLived, "--operator", "restore-proof")))
                .contains("  DESTROYED " + shortId, "  SKIPPED " + heldId + " HOLD");
        assertThat(ok(cli(a, bucketA, tmp, "verify", "tenant", "--tenants", "all", "--tsa-trust", tmp.resolve("tsa-trust.pem").toString(),
                "--operator", "auditor-1"))).contains("VERIFY_TENANT " + done + " MATCH", "VERIFY_TENANT " + shortLived + " MATCH");
        Map<String, String> tablesA = tableDigests(a);
        ObjectVersionCopier.Report objectsA = ObjectVersionCopier.describe(s3, bucketA, "");
        assertThat(objectsA.held()).as("the held disclosure's objects").isPositive();
        assertThat(objectsA.retained()).isPositive();

        // ---- 백업: 물리 백업(복제 롤, 컨테이너 IP — pg_hba의 비밀번호 줄) → 봉투 → 객체 → 업로드
        var basebackup = a.execInContainer("sh", "-c", "PGPASSWORD=backup_local_only pg_basebackup -h \"$(hostname -i)\" -U disclosure_backup"
                + " -D /tmp/bb -Ft -X fetch --no-sync && ls /tmp/bb");
        assertThat(basebackup.getExitCode()).as(basebackup.getStderr()).isZero();
        Path plainTar = tmp.resolve("base.tar");
        a.copyFileFromContainer("/tmp/bb/base.tar", plainTar.toString());
        byte[] plainBytes = Files.readAllBytes(plainTar);
        byte[] sentinel = done.getBytes(StandardCharsets.US_ASCII);                     // 테넌트 ID는 평문 표 행에 있다
        assertThat(indexOf(plainBytes, sentinel)).as("control: the physical backup carries table rows in clear").isNotNegative();

        String exported = offline(concat(backupStore(bucketA, backupBucket), "backup", "objects", "export", "--run", "r1"));
        Matcher exp = Pattern.compile("BACKUP_OBJECTS_EXPORTED run=r1 keys=(\\d+) versions=(\\d+) deleteMarkers=(\\d+) retained=(\\d+) held=(\\d+) maxRetainUntil=(\\S+)")
                .matcher(exported);
        assertThat(exp.find()).as(exported).isTrue();
        assertThat(Long.parseLong(exp.group(2))).isEqualTo(objectsA.versions());
        assertThat(Long.parseLong(exp.group(5))).isEqualTo(objectsA.held());

        Path sealed = tmp.resolve("base.tar.gabk");
        String sealedOut = offline(concat(backupStore(bucketA, backupBucket), "backup", "seal", "--in", plainTar.toString(), "--out", sealed.toString()));
        Matcher seal = Pattern.compile("BACKUP_SEALED bytes=(\\d+) sha256=([0-9a-f]{64})").matcher(sealedOut);
        assertThat(seal.find()).as(sealedOut).isTrue();
        assertThat(indexOf(Files.readAllBytes(sealed), sentinel)).as("no plaintext table rows in the sealed backup").isEqualTo(-1);
        LocalDate floor = LocalDate.ofInstant(java.time.Instant.parse(exp.group(6)), ZoneOffset.UTC);
        // 보존 기한 하한: 산출물의 가장 늦은 보존 기한보다 이른 업로드는 거부, 같거나 늦으면 COMPLIANCE로
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> offline(concat(backupStore(bucketA, backupBucket), "backup", "upload", "--file",
                sealed.toString(), "--run", "r1", "--retain-until", floor.minusDays(1).toString())))
                .hasStackTraceContaining("earlier than the latest artifact retention");
        assertThat(offline(concat(backupStore(bucketA, backupBucket), "backup", "upload", "--file", sealed.toString(), "--run", "r1",
                "--retain-until", floor.plusDays(1).toString()))).contains("BACKUP_UPLOADED run=r1");
        Files.delete(plainTar);
        Files.delete(sealed);

        // ---- 복구: 내려받기 → 열기(같은 평문 해시) → 새 DB B → 새 버킷에 객체
        Path downloaded = tmp.resolve("restore.gabk");
        offline(concat(backupStore(bucketB, backupBucket), "backup", "download", "--run", "r1", "--out", downloaded.toString()));
        Path restoredTar = tmp.resolve("restore.tar");
        assertThat(offline(concat(backupStore(bucketB, backupBucket), "backup", "open", "--in", downloaded.toString(), "--out", restoredTar.toString())))
                .contains("BACKUP_OPENED bytes=" + seal.group(1) + " sha256=" + seal.group(2));
        GenericContainer<?> b = environmentB(restoredTar);
        assertThat(tableDigests(b)).as("every table's rows, before anything runs against B").isEqualTo(tablesA);
        assertThat(offline(concat(backupStore(bucketB, backupBucket), "backup", "objects", "import", "--run", "r1"))).doesNotContain("alreadyPresent");
        assertThat(ObjectVersionCopier.describe(s3, bucketB, "")).as("keys, version order, bytes, retention, holds").isEqualTo(objectsA);
        // 복구 절차를 다시 돌려도 된다: 같은 모양이면 쓰지 않고 성공, 다른 모양(다른 버킷의 일부)이면 거부
        assertThat(offline(concat(backupStore(bucketB, backupBucket), "backup", "objects", "import", "--run", "r1"))).contains("alreadyPresent=true");
        assertThat(ObjectVersionCopier.describe(s3, bucketB, "")).isEqualTo(objectsA);

        assertThat(ok(cli(b, bucketB, tmp, "verify", "tenant", "--tenants", "all", "--tsa-trust", tmp.resolve("tsa-trust.pem").toString(),
                "--operator", "auditor-1"))).contains("VERIFY_TENANT " + done + " MATCH findings=0", "VERIFY_TENANT " + shortLived + " MATCH findings=0");
        Path evidenceB = tmp.resolve("evidence-b.zip");
        ok(cli(b, bucketB, tmp, "artifacts", "get", "--tenant", done, "--id", completed, "--kind", "EVIDENCE_ZIP", "--out", evidenceB.toString(),
                "--operator", "auditor-1"));
        assertThat(Files.readAllBytes(evidenceB)).isEqualTo(Files.readAllBytes(evidenceA));
        Path receiptB = tmp.resolve("receipt-b.json");
        ok(cli(b, bucketB, tmp, "anchor", "receipt", "export", "--tenant", done, "--id", completed, "--out", receiptB.toString(), "--operator", "auditor-1"));
        assertThat(ok(cli(b, bucketB, tmp, "verify", "package", "--package", evidenceB.toString(), "--receipt", receiptB.toString(), "--tsa-trust",
                tmp.resolve("tsa-trust.pem").toString()))).contains("VERIFY_PACKAGE MATCH findings=0");
        assertThat(ok(cli(b, bucketB, tmp, "artifacts", "reconcile", "--tenants", "all", "--operator", "restore-proof"))).doesNotContain("failed=1");

        // ---- 음성 대조: 객체 하나(완료 문서의 키 하나)를 빼고 복구한 버킷 → verify tenant가 정확히 그 키를 잡는다
        String bucketC = S3.freshBucket();
        List<String> keys = objectsA.entries().stream().map(ObjectVersionCopier.Entry::key).distinct().toList();
        String missing = keys.stream().filter(k -> k.startsWith(done + "/" + completed + "/")).findFirst().orElseThrow();
        for (String k : keys) {
            if (!k.equals(missing)) {
                ObjectVersionCopier.copy(s3, backupBucket, "runs/r1/objects/" + k, s3, bucketC, k);
            }
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> offline(concat(backupStore(bucketC, backupBucket), "backup", "objects", "import", "--run", "r1")))
                .hasStackTraceContaining("does not match backup run r1");
        Run broken = cli(b, bucketC, tmp, "verify", "tenant", "--tenants", done, "--tsa-trust", tmp.resolve("tsa-trust.pem").toString(), "--operator", "auditor-1");
        assertThat(broken.exit()).as(broken.out()).isNotZero();
        List<String> findings = broken.out().lines().filter(l -> l.startsWith("  ") && !l.startsWith("  STATEMENT")).map(String::strip).toList();
        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst()).startsWith("OBJECT_MISSING ").contains(missing);
    }
}
