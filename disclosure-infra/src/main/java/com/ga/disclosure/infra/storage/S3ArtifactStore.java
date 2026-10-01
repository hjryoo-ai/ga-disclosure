package com.ga.disclosure.infra.storage;

import com.ga.disclosure.workflow.artifact.ArtifactMissingException;
import com.ga.disclosure.workflow.artifact.ArtifactStore;
import com.ga.disclosure.workflow.artifact.ObjectLockedException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRetentionResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectLockRetention;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ArtifactStore}의 S3 어댑터(AWS SDK v2 표준 API만). 잠금은 {@code PutObjectRetention}(COMPLIANCE)으로 최신 버전에 걸고, 삭제는 키의
 * 모든 버전을 버전 ID로 지운다(삭제 표식이 아니라 실제 삭제 — 잠긴 버전은 저장소가 거부한다). 거부(403·Object Lock)는
 * {@link ObjectLockedException}으로 옮긴다.
 */
public final class S3ArtifactStore implements ArtifactStore, AutoCloseable {

    private final S3Client s3;
    private final String bucket;

    public S3ArtifactStore(S3Client s3, String bucket) {
        this.s3 = Objects.requireNonNull(s3, "s3");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
    }

    /** 설정으로 클라이언트를 만든다: URLConnection HTTP 클라이언트, 경로 형식, 체크섬은 필요할 때만(호환 저장소 공통 분모). */
    public static S3Client client(S3StorageSettings settings) {
        return S3Client.builder()
                .endpointOverride(settings.endpoint())
                .region(Region.of(settings.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(settings.accessKeyId(), settings.secretAccessKey())))
                .forcePathStyle(settings.pathStyle())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(30)))
                .build();
    }

    public static S3ArtifactStore of(S3StorageSettings settings) {
        return new S3ArtifactStore(client(settings), settings.bucket());
    }

    @Override
    public void put(String key, byte[] bytes) {
        s3.putObject(b -> b.bucket(bucket).key(key).contentType("application/octet-stream"), RequestBody.fromBytes(bytes));
    }

    @Override
    public byte[] get(String key) {
        try {
            return s3.getObjectAsBytes(b -> b.bucket(bucket).key(key)).asByteArray();
        } catch (NoSuchKeyException e) {
            throw new ArtifactMissingException(key);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            s3.headObject(b -> b.bucket(bucket).key(key));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public void applyRetention(String key, Instant until) {
        String version = latestVersion(key);
        try {
            s3.putObjectRetention(b -> b.bucket(bucket).key(key).versionId(version)
                    .retention(ObjectLockRetention.builder().mode(ObjectLockRetentionMode.COMPLIANCE).retainUntilDate(until).build()));
        } catch (S3Exception e) {
            throw locked(key, e);
        }
    }

    @Override
    public Optional<Instant> retention(String key) {
        String version = latestVersion(key);
        try {
            GetObjectRetentionResponse r = s3.getObjectRetention(b -> b.bucket(bucket).key(key).versionId(version));
            return Optional.ofNullable(r.retention()).map(ObjectLockRetention::retainUntilDate);
        } catch (S3Exception e) {
            if (e.statusCode() == 404 || e.statusCode() == 400) {
                return Optional.empty();                    // 보존 설정이 없는 버전
            }
            throw e;
        }
    }

    @Override
    public List<StoredObject> list(String prefix) {
        List<StoredObject> out = new ArrayList<>();
        for (S3Object o : s3.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(prefix)).contents()) {
            out.add(new StoredObject(o.key(), o.lastModified(), o.size()));
        }
        return out;
    }

    @Override
    public void delete(String key) {
        for (ObjectVersion v : versions(key)) {
            try {
                s3.deleteObject(b -> b.bucket(bucket).key(key).versionId(v.versionId()));
            } catch (S3Exception e) {
                throw locked(key, e);
            }
        }
    }

    private List<ObjectVersion> versions(String key) {
        List<ObjectVersion> out = new ArrayList<>();
        for (ListObjectVersionsResponse page : s3.listObjectVersionsPaginator(b -> b.bucket(bucket).prefix(key))) {
            page.versions().stream().filter(v -> v.key().equals(key)).forEach(out::add);
        }
        return out;
    }

    private String latestVersion(String key) {
        return versions(key).stream().filter(ObjectVersion::isLatest).map(ObjectVersion::versionId).findFirst()
                .orElseThrow(() -> new ArtifactMissingException(key));
    }

    private static RuntimeException locked(String key, S3Exception e) {
        return e.statusCode() == 403 || e.statusCode() == 400 ? new ObjectLockedException(key, e) : e;
    }

    public String bucket() {
        return bucket;
    }

    @Override
    public void close() {
        s3.close();
    }
}
