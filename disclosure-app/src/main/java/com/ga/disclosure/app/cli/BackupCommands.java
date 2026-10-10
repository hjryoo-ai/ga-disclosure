package com.ga.disclosure.app.cli;

import com.ga.disclosure.infra.crypto.BackupEnvelope;
import com.ga.disclosure.infra.secret.FileSecretSource;
import com.ga.disclosure.infra.storage.BackupStore;
import com.ga.disclosure.infra.storage.ObjectVersionCopier;
import com.ga.disclosure.infra.storage.S3StorageSettings;
import com.ga.disclosure.workflow.secret.SecretName;
import org.springframework.core.env.Environment;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Objects;

/**
 * 백업(Phase 8 ⑤, 승인 Q3) — 앱 컨텍스트 없이(DB 불요, {@link OfflineCli}).
 * <pre>
 * backup seal     --in &lt;plain&gt; --out &lt;sealed&gt;      DB 물리 백업 tar를 백업 키(비밀 backup/key)로 봉한다
 * backup open     --in &lt;sealed&gt; --out &lt;plain&gt;      연다(출력은 소유자 전용으로 새로 만든다 — 덮어쓰지 않는다)
 * backup objects export --run &lt;실행&gt;   산출물 저장소의 모든 버전·마커·보존·보류 → 백업 저장소 runs/&lt;실행&gt;/objects/(비어 있어야 한다)
 * backup upload   --file &lt;sealed&gt; --run &lt;실행&gt; --retain-until YYYY-MM-DD   runs/&lt;실행&gt;/db.gabk에 COMPLIANCE 보존으로
 * backup download --run &lt;실행&gt; --out &lt;sealed&gt;
 * backup objects import --run &lt;실행&gt;   runs/&lt;실행&gt;/objects/ → 산출물 저장소(비어 있어야 한다 — 복구 환경)
 * </pre>
 * 백업 저장소는 {@code ga.backup.s3.*}(Object Lock 버킷 — 산출물 저장소와 다른 버킷·다른 자격 증명, {@link BackupStore}). 업로드의 보존 기한은 같은 실행
 * {@code objects/}의 가장 늦은 보존 기한보다 이를 수 없다 — 산출물 보존 기한은 룰의 보존기간에서 나온다(DB 백업이 그것이 가리키는 문서보다 먼저 풀리지 않게).
 * 출력은 크기·해시·개수뿐(키·평문 0).
 */
final class BackupCommands {

    static final SecretName BACKUP_KEY = SecretName.of("backup/key");

    private final Environment env;
    private final PrintStream out;

    BackupCommands(Environment env, PrintStream out) {
        this.env = Objects.requireNonNull(env, "env");
        this.out = Objects.requireNonNull(out, "out");
    }

    void run(CliArguments args) {
        try {
            switch (args.command()) {
                case "backup seal" -> seal(args);
                case "backup open" -> open(args);
                case "backup upload" -> upload(args);
                case "backup download" -> download(args);
                case "backup objects export" -> objects(args, true);
                case "backup objects import" -> objects(args, false);
                default -> throw new CliFailure("unknown command '" + args.command() + "' — see BackupCommands javadoc");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void seal(CliArguments args) throws IOException {
        byte[] key = key();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(Path.of(args.required("in"))));
             OutputStream o = new BufferedOutputStream(createOwnerOnly(Path.of(args.required("out"))))) {
            BackupEnvelope.Summary s = BackupEnvelope.seal(key, in, o);
            out.println("BACKUP_SEALED bytes=" + s.plaintextBytes() + " sha256=" + s.plaintextSha256());
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private void open(CliArguments args) throws IOException {
        byte[] key = key();
        Path target = Path.of(args.required("out"));
        try (InputStream in = new BufferedInputStream(Files.newInputStream(Path.of(args.required("in"))));
             OutputStream o = new BufferedOutputStream(createOwnerOnly(target))) {
            BackupEnvelope.Summary s = BackupEnvelope.open(key, in, o);
            out.println("BACKUP_OPENED bytes=" + s.plaintextBytes() + " sha256=" + s.plaintextSha256());
        } catch (BackupEnvelope.NotOpenable e) {
            Files.deleteIfExists(target);                                         // 반쯤 연 평문을 남기지 않는다
            throw new CliFailure(e.getMessage());
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private void upload(CliArguments args) {
        Instant until = LocalDate.parse(args.required("retain-until")).atStartOfDay(ZoneOffset.UTC).toInstant();
        try (BackupStore store = new BackupStore(settings("ga.backup.s3"))) {
            Instant floor = store.upload(Path.of(args.required("file")), args.required("run"), until);
            out.println("BACKUP_UPLOADED run=" + args.required("run") + " retainUntil=" + until + " artifactRetentionFloor=" + floor);
        } catch (IllegalArgumentException e) {
            throw new CliFailure(e.getMessage());
        } catch (BackupStore.RetentionTooShort e) {
            throw new CliFailure(e.getMessage() + " — export objects first and keep the database backup at least that long");
        }
    }

    private void download(CliArguments args) throws IOException {
        Path target = Path.of(args.required("out"));
        long bytes;
        try (BackupStore store = new BackupStore(settings("ga.backup.s3")); OutputStream o = new BufferedOutputStream(createOwnerOnly(target))) {
            bytes = store.download(args.required("run"), o);
        }
        out.println("BACKUP_DOWNLOADED bytes=" + bytes);
    }

    private void objects(CliArguments args, boolean export) {
        S3StorageSettings artifacts = settings("ga.storage.s3");
        String run = args.required("run");
        try (BackupStore store = new BackupStore(settings("ga.backup.s3"))) {
            ObjectVersionCopier.Report r = export ? store.exportObjects(artifacts, run) : store.importObjects(artifacts, run);
            out.println((export ? "BACKUP_OBJECTS_EXPORTED" : "BACKUP_OBJECTS_IMPORTED") + " run=" + run + " keys=" + r.keys() + " versions=" + r.versions()
                    + " deleteMarkers=" + r.deleteMarkers() + " retained=" + r.retained() + " held=" + r.held()
                    + " maxRetainUntil=" + r.maxRetainUntil().map(Instant::toString).orElse("-"));
        }
    }

    private byte[] key() {
        String dir = env.getProperty("ga.secrets.dir", "");
        if (dir.isBlank()) {
            throw new CliFailure("ga.secrets.dir is not set — the backup key is the secret " + BACKUP_KEY);
        }
        byte[] encoded = new FileSecretSource(Path.of(dir), env.getProperty("ga.secrets.allow-group-read", Boolean.class, false)).read(BACKUP_KEY);
        try {
            return Base64.getDecoder().decode(new String(encoded, java.nio.charset.StandardCharsets.US_ASCII).strip());
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    private S3StorageSettings settings(String prefix) {
        return new S3StorageSettings(URI.create(env.getRequiredProperty(prefix + ".endpoint")), env.getProperty(prefix + ".region", "us-east-1"),
                env.getRequiredProperty(prefix + ".bucket"), env.getRequiredProperty(prefix + ".access-key-id"),
                env.getRequiredProperty(prefix + ".secret-access-key"), env.getProperty(prefix + ".path-style", Boolean.class, true));
    }

    /**
     * 새 파일을 소유자 전용(600)으로 <b>한 번에</b> 연다 — 만들기와 열기가 하나의 {@code O_CREAT|O_EXCL}이라 경로에 무엇이든(심볼릭 링크 포함) 이미 있으면
     * 실패한다. 만든 뒤 경로로 다시 여는 방식은 그 사이 링크로 바꿔치기되면 평문 백업을 링크 대상에 쓴다(10단계 커밋 보안 검토 — TOCTOU).
     */
    static OutputStream createOwnerOnly(Path file) throws IOException {
        java.util.Set<java.nio.file.OpenOption> options = java.util.Set.of(java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
        try {
            java.nio.channels.SeekableByteChannel channel = file.getFileSystem().supportedFileAttributeViews().contains("posix")
                    ? Files.newByteChannel(file, options, PosixFilePermissions.asFileAttribute(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)))
                    : Files.newByteChannel(file, options);
            return java.nio.channels.Channels.newOutputStream(channel);
        } catch (FileAlreadyExistsException e) {
            throw new CliFailure("refusing to overwrite " + file.getFileName());
        }
    }
}
