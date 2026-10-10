package com.ga.disclosure.infra.storage;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ObjectLockRetention;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * 백업 저장소(Phase 8 ⑤, 승인 Q3): Object Lock 버킷(산출물 저장소와 다른 버킷·자격 증명). 백업 한 번 = 실행 이름 하나 — DB 백업 봉투는
 * {@code runs/<실행>/db.gabk}, 산출물 버전 복제는 {@code runs/<실행>/objects/}(실행마다 빈 접두 — 정기 백업이 서로 겹치지 않는다. 증분 복제는 하지 않는다 —
 * 운영 문서의 비용 항목). DB 백업의 보존 기한은 같은 실행 {@code objects/}의 가장 늦은 보존 기한보다 이를 수 없다 — 산출물 보존 기한은 룰의 보존기간에서
 * 나오므로 DB 백업이 그것이 가리키는 문서보다 먼저 풀리지 않는다.
 */
public final class BackupStore implements AutoCloseable {

    private static final java.util.regex.Pattern RUN = java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    /** 실행의 산출물 복제 접두. */
    public static String objects(String run) {
        return "runs/" + checked(run) + "/objects/";
    }

    /** 실행의 DB 백업 봉투 키. */
    public static String database(String run) {
        return "runs/" + checked(run) + "/db.gabk";
    }

    private static String checked(String run) {
        if (run == null || !RUN.matcher(run).matches()) {
            throw new IllegalArgumentException("backup run name must be [A-Za-z0-9._-], 1..64");
        }
        return run;
    }

    /** 보존 기한 하한보다 이른 업로드. */
    public static final class RetentionTooShort extends RuntimeException {
        private final Instant floor;

        RetentionTooShort(Instant floor) {
            super("retain-until is earlier than the latest artifact retention in the backup (" + floor + ")");
            this.floor = floor;
        }

        public Instant floor() {
            return floor;
        }
    }

    private final S3Client s3;
    private final String bucket;

    public BackupStore(S3StorageSettings settings) {
        this(settings, false);
    }

    /** {@code createIfMissing}: 개발·kind 전용(Object Lock 활성으로 만든다) — 운영 백업 버킷은 인프라가 만든다. */
    public BackupStore(S3StorageSettings settings, boolean createIfMissing) {
        this.s3 = S3ArtifactStore.client(Objects.requireNonNull(settings, "settings"));
        this.bucket = settings.bucket();
        ArtifactStoreBootstrap bootstrap = new ArtifactStoreBootstrap(s3, bucket);
        if (createIfMissing) {
            bootstrap.createIfMissing();
        }
        bootstrap.verify();
    }

    /** 복구 대상 산출물 버킷을 개발·kind에서 만든다(운영 금지 — 복구 환경의 버킷도 인프라가 만든다). */
    public static void createArtifactBucketIfMissing(S3StorageSettings artifacts) {
        try (S3Client a = S3ArtifactStore.client(artifacts)) {
            new ArtifactStoreBootstrap(a, artifacts.bucket()).createIfMissing();
        }
    }

    /** 산출물 저장소 → 실행의 {@code objects/}(비어 있어야 한다 — 같은 실행 이름을 두 번 쓰지 않는다). */
    public ObjectVersionCopier.Report exportObjects(S3StorageSettings artifacts, String run) {
        try (S3Client a = S3ArtifactStore.client(artifacts)) {
            new ArtifactStoreBootstrap(a, artifacts.bucket()).verify();
            return ObjectVersionCopier.copy(a, artifacts.bucket(), "", s3, bucket, objects(run));
        }
    }

    /** 복구 결과: 복제한 모양과, 대상이 이미 그 실행과 같은 모양이라 아무것도 쓰지 않았는지. */
    public record Imported(ObjectVersionCopier.Report report, boolean alreadyPresent) {
    }

    /**
     * 실행의 {@code objects/} → 산출물 저장소(복구 환경). 대상이 비어 있으면 복제한다. 비어 있지 않으면 — 이전 복구 시도가 끝까지 갔다면 — 그 모양(키·버전 순서·
     * 바이트 해시·보존·보류)이 실행과 같을 때만 아무것도 쓰지 않고 성공한다(복구 절차를 다시 돌릴 수 있게). 반쯤 들어간 대상은 거부한다(잠긴 객체는 지울 수
     * 없으니 새 버킷으로).
     */
    public Imported importObjects(S3StorageSettings artifacts, String run) {
        try (S3Client a = S3ArtifactStore.client(artifacts)) {
            new ArtifactStoreBootstrap(a, artifacts.bucket()).verify();
            ObjectVersionCopier.Report present = ObjectVersionCopier.describe(a, artifacts.bucket(), "");
            if (!present.entries().isEmpty()) {
                ObjectVersionCopier.Report source = ObjectVersionCopier.describe(s3, bucket, objects(run));
                if (present.equals(source)) {
                    return new Imported(source, true);
                }
                throw new IllegalStateException("target " + artifacts.bucket() + " is not empty and does not match backup run " + run
                        + " — restore into a new bucket");
            }
            return new Imported(ObjectVersionCopier.copy(s3, bucket, objects(run), a, artifacts.bucket(), ""), false);
        }
    }

    public Instant retentionFloor(String run) {
        return ObjectVersionCopier.describe(s3, bucket, objects(run)).maxRetainUntil().orElse(Instant.EPOCH);
    }

    /** 실행의 DB 백업 봉투를 COMPLIANCE 보존으로. 같은 실행의 산출물 보존 하한보다 이르면 올리지 않는다(산출물을 먼저 복제해야 한다). */
    public Instant upload(Path file, String run, Instant retainUntil) {
        Instant floor = retentionFloor(run);
        if (retainUntil.isBefore(floor)) {
            throw new RetentionTooShort(floor);
        }
        String key = database(run);
        String version = s3.putObject(b -> b.bucket(bucket).key(key).contentType("application/octet-stream"), RequestBody.fromFile(file)).versionId();
        s3.putObjectRetention(b -> b.bucket(bucket).key(key).versionId(version)
                .retention(ObjectLockRetention.builder().mode(ObjectLockRetentionMode.COMPLIANCE).retainUntilDate(retainUntil).build()));
        return floor;
    }

    public long download(String run, OutputStream out) throws IOException {
        try (InputStream in = s3.getObject(b -> b.bucket(bucket).key(database(run)))) {
            return in.transferTo(out);
        }
    }

    @Override
    public void close() {
        s3.close();
    }
}
